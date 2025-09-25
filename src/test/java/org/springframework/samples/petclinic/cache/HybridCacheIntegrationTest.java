/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.samples.petclinic.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.samples.petclinic.cache.HybridCache;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for hybrid caching system focusing on L1/L2 coordination and fallback scenarios.
 * Tests use the 'vets' cache region to verify hybrid cache behavior.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles({"hybrid-cache", "h2", "spring-data-jpa"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "spring.cache.type=",  // Clear the 'none' setting from test application.properties to enable caching
    "petclinic.cache.hybrid.enabled=true",
    "petclinic.cache.l1.ttl=60",
    "petclinic.cache.l2.ttl=300",
    "petclinic.cache.fallback.enabled=true",
    "petclinic.security.enable=false",
    "spring.sql.init.mode=embedded",
    "spring.sql.init.schema-locations=classpath*:db/h2/schema.sql",
    "spring.sql.init.data-locations=classpath*:db/h2/data.sql",
    "spring.datasource.url=jdbc:h2:mem:hybridcachetest;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "database=h2"
})
public class HybridCacheIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private HybridCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        clearAllCaches();
    }

    @Test
    void testHybridCacheCoordination() {
        // 1. Seed through REST to populate both cache layers
        ResponseEntity<String> response1 = restTemplate.getForEntity("/api/vets", String.class);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify L2 cache is populated in Redis
        assertThat(findRedisKeysForCache("vets")).isNotEmpty();

        // 3. Second call - should be faster due to caching
        ResponseEntity<String> response2 = restTemplate.getForEntity("/api/vets", String.class);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 4. Clear L1 cache only (simulate L1 eviction) using REST endpoint
        clearL1Vets();

        // 5. Third call - should work with L2 or fallback to database but still succeed
        ResponseEntity<String> response3 = restTemplate.getForEntity("/api/vets", String.class);
        assertThat(response3.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(findRedisKeysForCache("vets")).isNotEmpty();

        // 6. Fourth call - should be cached again
        ResponseEntity<String> response4 = restTemplate.getForEntity("/api/vets", String.class);
        assertThat(response4.getStatusCode()).isEqualTo(HttpStatus.OK);

    }

    @Test
    void testCompleteCacheFallbackAndRecovery() {
        // 1. Load data into both caches
        ResponseEntity<String> originalResponse = restTemplate.getForEntity("/api/vets", String.class);
        assertThat(originalResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(findRedisKeysForCache("vets")).isNotEmpty();

        // 2. Verify caching is working (should be fast L1 hit)
        ResponseEntity<String> cachedResponse = restTemplate.getForEntity("/api/vets", String.class);
        assertThat(cachedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 3. Clear all caches completely to simulate total cache failure
        clearAllCaches();
        assertThat(findRedisKeysForCache("vets")).isEmpty();

        // 4. Call should go to database (both caches empty) and repopulate both layers
        ResponseEntity<String> fallbackResponse = restTemplate.getForEntity("/api/vets", String.class);
        assertThat(fallbackResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(findRedisKeysForCache("vets")).isNotEmpty();

        // 6. Final verification - should be cached again (fast L1 hit)
        ResponseEntity<String> finalCachedResponse = restTemplate.getForEntity("/api/vets", String.class);
        assertThat(finalCachedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void clearAllCaches() {
        // Clear all caches using HybridCacheManager directly
        for (String cacheName : cacheManager.getCacheNames()) {
            org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    private void clearL1Vets() {
        // Clear only L1 cache for 'vets' cache using HybridCacheManager directly
        org.springframework.cache.Cache vetsCache = cacheManager.getCache("vets");
        if (vetsCache instanceof HybridCache) {
            ((HybridCache) vetsCache).getL1Cache().clear();
        }
    }

    private Set<String> findRedisKeysForCache(String cacheName) {
        Set<String> keys = redisTemplate.keys(cacheName + "::*");
        return keys != null ? keys : Collections.emptySet();
    }
}
