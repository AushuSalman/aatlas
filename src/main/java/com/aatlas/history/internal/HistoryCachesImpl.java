package com.aatlas.history.internal;

import com.aatlas.common.cache.CacheNames;
import com.aatlas.history.HistoryCaches;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Evicts a tenant's entries from every tenant-scoped cache.
 *
 * <p>Keys are {@code t:{tenant}:...} (see {@link CacheNames#key}), so a tenant's namespace can
 * be dropped without touching anyone else's. Under Caffeine that is a filter over the native
 * map; under Redis a pattern clean. Any other cache, or any failure, falls back to clearing
 * the whole cache: a write must never fail on an eviction, and a broader flush is the safe
 * side of that trade.
 */
@Component
class HistoryCachesImpl implements HistoryCaches {

    private static final Logger log = LoggerFactory.getLogger(HistoryCachesImpl.class);

    private final CacheManager manager;

    HistoryCachesImpl(CacheManager manager) {
        this.manager = manager;
    }

    @Override
    public void evict(UUID tenantId) {
        String prefix = "t:" + tenantId + ":";
        for (String name : CacheNames.TENANT_SCOPED) {
            Cache cache = manager.getCache(name);
            if (cache == null) {
                continue;
            }
            try {
                Object nativeCache = cache.getNativeCache();
                if (nativeCache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeine) {
                    caffeine.asMap().keySet().removeIf(k -> String.valueOf(k).startsWith(prefix));
                } else if (nativeCache instanceof RedisCacheWriter writer) {
                    writer.clean(name, (name + "::" + prefix + "*").getBytes(StandardCharsets.UTF_8));
                } else {
                    cache.clear();
                }
            } catch (RuntimeException ex) {
                log.warn("Eviction of cache {} for tenant {} failed; clearing it", name, tenantId, ex);
                cache.clear();
            }
        }
    }

    @Override
    public void evictAfterCommit(UUID tenantId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(tenantId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(tenantId);
            }
        });
    }
}
