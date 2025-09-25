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
package org.springframework.samples.petclinic.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.samples.petclinic.cache.HybridCacheManager;

import java.time.Duration;

/**
 * Configuration for hybrid caching system combining L1 (Caffeine) and L2 (Redis) caches.
 */
@Configuration
@EnableCaching
@Profile("hybrid-cache")
@ConditionalOnProperty(prefix = "petclinic.cache.hybrid", name = "enabled", havingValue = "true")
public class HybridCacheConfig {


    /**
     * L1 Cache Manager using Caffeine for fast local access with TTL and size limits.
     */
    @Bean("l1CacheManager")
    public CacheManager l1CacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        // Configure Caffeine cache spec with default TTL and size limits
        String cacheSpec = "maximumSize=1000,expireAfterWrite=600s,expireAfterAccess=30s,recordStats";

        cacheManager.setCacheSpecification(cacheSpec);

        // Pre-configure cache names
        cacheManager.setCacheNames(java.util.Arrays.asList(
            "vets", "vetById", "petTypes", "petTypeById",
            "specialties", "specialtyById", "owners", "ownerById"
        ));

        return cacheManager;
    }

    /**
     * L2 Cache Manager using Redis for distributed caching.
     */
    @Bean("l2CacheManager")
    @ConditionalOnProperty(name = "spring.data.redis.host")
    public CacheManager l2RedisCacheManager(RedisConnectionFactory redisConnectionFactory) {
        // Configure ObjectMapper with JSR310 module for LocalDate support
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.activateDefaultTyping(
            objectMapper.getPolymorphicTypeValidator(),
            com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping.NON_FINAL,
            com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisCacheConfiguration cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofSeconds(3600)) // 1 hour default TTL
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer))
            .disableCachingNullValues(); // Don't cache null values

        return RedisCacheManager.builder(redisConnectionFactory)
            .cacheDefaults(cacheConfiguration)
            .build();
    }

    /**
     * L2 Cache Manager fallback using ConcurrentMap when Redis is not available.
     */
    @Bean("l2CacheManager")
    @ConditionalOnMissingBean(name = "l2CacheManager")
    public CacheManager l2FallbackCacheManager() {
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager();

        // Pre-configure cache names
        cacheManager.setCacheNames(java.util.Arrays.asList(
            "vets", "vetById", "petTypes", "petTypeById",
            "specialties", "specialtyById", "owners", "ownerById"
        ));

        return cacheManager;
    }

    /**
     * Primary hybrid cache manager that coordinates between L1 and L2 caches.
     */
    @Bean
    @Primary
    public CacheManager hybridCacheManager(
            CacheManager l1CacheManager,
            CacheManager l2CacheManager) {
        return new HybridCacheManager(l1CacheManager, l2CacheManager);
    }
}
