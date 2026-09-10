package com.aatlas.insights.internal;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A port of {@code platform/logistics.ts}'s lane resolution: which freight-lane region a
 * branch sits in, and what it costs to land a supplier's quote there. These are the six
 * freight lanes (pacific-northwest, southwest, mountain, south-central, midwest,
 * southeast) - a different grouping from the four market regions
 * ({@code south}/{@code west}/{@code north}/{@code east}) {@code geo.ts} and
 * {@code demographics.ts} read; both live in wave 1's reference tables
 * ({@code logistics_lanes}/{@code logistics_origins} and {@code regions}/{@code subdivisions}
 * respectively), read by {@link CatalogSnapshotReader}.
 */
final class LogisticsEngine {

    private LogisticsEngine() {
    }

    record Lane(double freightPct, double dutyPct, int transitDays) {
    }

    /** {@code REGIONS[4]}: Midwest, the network's centre of gravity. */
    private static final String FALLBACK_LANE_KEY = "midwest";

    private static final LogisticsOriginRef FALLBACK_ORIGIN =
            new LogisticsOriginRef("east", "ocean", "Savannah, GA", BigDecimal.valueOf(7.5), 30, BigDecimal.valueOf(5.0));

    /**
     * A UK region rides the US lane its branch stands in for (see {@code logistics.ts}'s
     * {@code UK_LANES}); only exercised for a UK tenant.
     */
    private static final Map<String, String> UK_LANES = Map.ofEntries(
            Map.entry("LDN", "south-central"), Map.entry("SE", "southeast"), Map.entry("SW", "southeast"),
            Map.entry("WLS", "mountain"), Map.entry("WM", "southeast"), Map.entry("EM", "midwest"),
            Map.entry("EE", "midwest"), Map.entry("NW", "southwest"), Map.entry("YH", "midwest"),
            Map.entry("NE", "midwest"), Map.entry("SCT", "pacific-northwest"), Map.entry("NI", "south-central"));

    static LogisticsLaneRef regionForState(String state, CatalogSnapshot snapshot) {
        LogisticsLaneRef fallback = fallback(snapshot);
        if (state == null) {
            return fallback;
        }
        for (LogisticsLaneRef lane : snapshot.logisticsLanes()) {
            if (lane.states().contains(state)) {
                return lane;
            }
        }
        String ukLaneKey = UK_LANES.get(state);
        if (ukLaneKey != null) {
            for (LogisticsLaneRef lane : snapshot.logisticsLanes()) {
                if (lane.key().equals(ukLaneKey)) {
                    return lane;
                }
            }
        }
        return fallback;
    }

    static LogisticsLaneRef regionForStore(String storeCode, CatalogSnapshot snapshot) {
        StoreRef store = snapshot.store(storeCode).orElse(null);
        return regionForState(store == null ? null : store.state(), snapshot);
    }

    static Lane laneFor(String originCountry, LogisticsLaneRef region, CatalogSnapshot snapshot) {
        LogisticsOriginRef origin = snapshot.logisticsOrigins().getOrDefault(originCountry, FALLBACK_ORIGIN);
        BigDecimal inlandPct = region.inlandPct().getOrDefault(origin.entry(), BigDecimal.ZERO);
        Integer inlandDays = region.inlandDays().getOrDefault(origin.entry(), 0);
        double freightPct = Fmt.round1(origin.inboundPct().doubleValue() + inlandPct.doubleValue());
        int transitDays = origin.inboundDays() + inlandDays;
        return new Lane(freightPct, origin.dutyPct().doubleValue(), transitDays);
    }

    private static LogisticsLaneRef fallback(CatalogSnapshot snapshot) {
        return snapshot.logisticsLanes().stream()
                .filter(l -> l.key().equals(FALLBACK_LANE_KEY))
                .findFirst()
                .orElseGet(() -> snapshot.logisticsLanes().get(Math.min(4, snapshot.logisticsLanes().size() - 1)));
    }
}
