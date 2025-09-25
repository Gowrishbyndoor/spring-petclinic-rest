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

import java.util.concurrent.Callable;

/**
 * Hybrid cache implementation that coordinates between L1 (local) and L2 (distributed) caches.
 * Implements read-through, write-through patterns.
 *
 * Cache Strategy:
 * - Read: Check L1 first, if miss then check L2, if hit then populate L1
 * - Write: Write to both L1 and L2 (write-through)
 * - Evict: Evict from both L1 and L2
 * - Clear: Clear both L1 and L2
 */
public class HybridCache implements Cache {

    private static final Logger logger = LoggerFactory.getLogger(HybridCache.class);

    private final String name;
    private final Cache l1Cache;
    private final Cache l2Cache;

    public HybridCache(String name, Cache l1Cache, Cache l2Cache) {
        this.name = name;
        this.l1Cache = l1Cache;
        this.l2Cache = l2Cache;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return this;
    }

    @Override
    public ValueWrapper get(Object key) {
        // L1 cache hit
        ValueWrapper l1Value = l1Cache.get(key);
        if (l1Value != null) {
            logger.debug("L1 cache hit for key: {} in cache: {}", key, name);
            return l1Value;
        }

        // L1 cache miss, check L2
        ValueWrapper l2Value = l2Cache.get(key);
        if (l2Value != null) {
            logger.debug("L2 cache hit for key: {} in cache: {}, promoting to L1", key, name);
            // Promote to L1 cache (read-through pattern)
            l1Cache.put(key, l2Value.get());
            return l2Value;
        }

        logger.debug("Cache miss for key: {} in cache: {}", key, name);
        return null;
    }

    @Override
    public void put(Object key, Object value) {
        // Write-through pattern: write to both L1 and L2
        l1Cache.put(key, value);
        l2Cache.put(key, value);
        logger.debug("Successfully wrote to both L1 and L2 caches for key: {} in cache: {}", key, name);
    }

    @Override
    public void evict(Object key) {
        l1Cache.evict(key);
        l2Cache.evict(key);
    }

    @Override
    public void clear() {
        l1Cache.clear();
        l2Cache.clear();
        logger.debug("Successfully cleared both L1 and L2 caches for cache: {}", name);
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        return (wrapper != null) ? type.cast(wrapper.get()) : null;
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper wrapper = get(key);
        if (wrapper != null) {
            return (T) wrapper.get();
        }

        try {
            T value = valueLoader.call();
            put(key, value);
            return value;
        } catch (Exception e) {
            throw new RuntimeException("Value loader failed", e);
        }
    }

    /**
     * Get the L1 cache for L1-specific operations.
     * @return the L1 cache instance
     */
    public Cache getL1Cache() {
        return l1Cache;
    }

}
