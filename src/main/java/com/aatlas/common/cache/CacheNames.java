package com.aatlas.common.cache;

import java.util.Set;
import java.util.UUID;

/**
 * Cache names and key shapes, in one place so invalidation can be reasoned about.
 *
 * <p>Two tiers, as the blueprint sets out. {@link #REFERENCE} entries are the same for
 * every tenant (regions, lanes, currencies, personas) and live in an in-process Caffeine
 * cache. Everything else is tenant-scoped, lives in Redis, and is keyed
 * {@code t:{tenant}:...} so a tenant's whole namespace can be dropped at once.
 *
 * <p>Invalidation is by event, not by TTL alone: engine workers evict what they recompute.
 * TTLs are only a backstop against a missed event.
 */
public final class CacheNames {

    private CacheNames() {
    }

    // --- tenant-scoped, Redis ------------------------------------------------
    public static final String OVERVIEW = "overview";
    public static final String REGION_INTEL = "regionIntel";
    public static final String STORE_INTEL = "storeIntel";
    public static final String PRODUCT_SCORES = "productScores";
    public static final String SELL_RECOMMENDATION = "sellRecommendation";
    public static final String BUY_RECOMMENDATION = "buyRecommendation";
    public static final String BUY_INTEL = "buyIntel";
    public static final String SUPPLIER_PANEL = "supplierPanel";
    public static final String SUPPLIER_RATING = "supplierRating";
    public static final String PROCUREMENT_ANALYTICS = "procurementAnalytics";
    public static final String DEMOGRAPHICS = "demographics";
    public static final String GUARDRAILS = "guardrails";
    public static final String TENANT_SETTINGS = "tenantSettings";

    /** Same for every tenant; in-process, never evicted by a tenant event. */
    public static final String REFERENCE = "reference";

    public static final Set<String> TENANT_SCOPED = Set.of(
            OVERVIEW, REGION_INTEL, STORE_INTEL, PRODUCT_SCORES,
            SELL_RECOMMENDATION, BUY_RECOMMENDATION, BUY_INTEL,
            SUPPLIER_PANEL, SUPPLIER_RATING, PROCUREMENT_ANALYTICS,
            DEMOGRAPHICS, GUARDRAILS, TENANT_SETTINGS);

    /** {@code t:{tenant}:{parts joined by ':'}} */
    public static String key(UUID tenantId, Object... parts) {
        StringBuilder key = new StringBuilder("t:").append(tenantId);
        for (Object part : parts) {
            key.append(':').append(part);
        }
        return key.toString();
    }
}
