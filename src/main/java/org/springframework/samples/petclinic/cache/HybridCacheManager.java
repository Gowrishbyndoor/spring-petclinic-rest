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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Hybrid cache manager that coordinates between L1 (local) and L2 (distributed) caches.
 * Implements read-through and write-through patterns with fallback support.
 */
public class HybridCacheManager implements CacheManager {

    private static final Logger logger = LoggerFactory.getLogger(HybridCacheManager.class);

    private final CacheManager l1CacheManager;
    private final CacheManager l2CacheManager;
    private final ConcurrentMap<String, Cache> cacheMap = new ConcurrentHashMap<>();

    public HybridCacheManager(CacheManager l1CacheManager,
                             CacheManager l2CacheManager) {
        this.l1CacheManager = l1CacheManager;
        this.l2CacheManager = l2CacheManager;
    }

    @Override
    public Cache getCache(String name) {
        return cacheMap.computeIfAbsent(name, this::createHybridCache);
    }

    @Override
    public Collection<String> getCacheNames() {
        return l1CacheManager.getCacheNames();
    }

    private Cache createHybridCache(String name) {
        Cache l1Cache = l1CacheManager.getCache(name);
        Cache l2Cache = l2CacheManager.getCache(name);

        if (l1Cache == null) {
            logger.warn("L1 cache '{}' not found, using L2 cache only", name);
            return l2Cache;
        }

        if (l2Cache == null) { // Always enable fallback
            logger.warn("L2 cache '{}' not found, using L1 cache only", name);
            return l1Cache;
        }

        return new HybridCache(name, l1Cache, l2Cache);
    }
}
