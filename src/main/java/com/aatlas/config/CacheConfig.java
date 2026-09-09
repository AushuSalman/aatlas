package com.aatlas.config;

import com.aatlas.common.cache.CacheNames;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Two cache tiers, as the blueprint sets out.
 *
 * <p>Redis holds the tenant-scoped hot reads that every pod must agree on: the overview,
 * region and store intel, the supplier panel, guardrails, settings. Caffeine holds
 * reference data that is the same for everyone and cheap to hold per pod: regions, lanes,
 * currencies, personas.
 *
 * <p>TTLs here are a backstop, not the invalidation strategy. Engine workers evict what
 * they recompute; a TTL only limits how long a missed eviction can be wrong for.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Redis-backed, for everything a second pod must see. Enabled where Redis exists. */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "aatlas.cache.mode", havingValue = "redis")
    CacheManager redisCacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(15))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(objectMapper)));

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        // Rewritten by a worker after every decision; a short TTL is only a safety net.
        perCache.put(CacheNames.OVERVIEW, defaults.entryTtl(Duration.ofMinutes(5)));
        perCache.put(CacheNames.SELL_RECOMMENDATION, defaults.entryTtl(Duration.ofMinutes(30)));
        perCache.put(CacheNames.BUY_RECOMMENDATION, defaults.entryTtl(Duration.ofMinutes(30)));
        // Quantity-dependent, so many more keys, each cheap to rebuild.
        perCache.put(CacheNames.BUY_INTEL, defaults.entryTtl(Duration.ofMinutes(10)));
        perCache.put(CacheNames.PROCUREMENT_ANALYTICS, defaults.entryTtl(Duration.ofMinutes(10)));
        perCache.put(CacheNames.DEMOGRAPHICS, defaults.entryTtl(Duration.ofMinutes(30)));
        // Policy: read on nearly every request, changed rarely, evicted on save.
        perCache.put(CacheNames.GUARDRAILS, defaults.entryTtl(Duration.ofHours(6)));
        perCache.put(CacheNames.TENANT_SETTINGS, defaults.entryTtl(Duration.ofHours(6)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(perCache)
                .transactionAware()
                .build();
    }

    /**
     * In-process fallback. It is also the local-development default, so the app runs
     * without Redis on the machine.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "aatlas.cache.mode", havingValue = "caffeine", matchIfMissing = true)
    CacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(15))
                .recordStats());
        manager.setAllowNullValues(false);
        return manager;
    }

    /**
     * Reference data: countries, regions, lanes, currencies, personas. Identical for
     * every tenant, so it stays in-process even when Redis is the primary manager.
     */
    @Bean
    CacheManager referenceCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(CacheNames.REFERENCE);
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(2_000)
                .expireAfterWrite(Duration.ofHours(12))
                .recordStats());
        return manager;
    }
}
