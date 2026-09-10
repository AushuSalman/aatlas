package com.aatlas.buy.internal;

import com.aatlas.buy.Lane;
import java.util.Map;

/**
 * Inbound lanes: what it costs to get an item from a supplier's country to a branch. A port
 * of the frontend's {@code platform/logistics.ts}, reading the rate card from
 * {@link CatalogGateway#logistics()} (the real {@code logistics_lanes}/{@code
 * logistics_origins} tables {@code catalog} seeded in wave 1) instead of a second constant
 * table, so a change to the rate card is felt here too.
 *
 * <p>{@code UK_LANES} is not data - it is the frontend's own hard-coded fallback ("a UK
 * region rides the lane of the US state its branch stands in for") - so it is ported here
 * verbatim rather than invented.
 */
final class LogisticsEngine {

    private LogisticsEngine() {
    }

    private record UkLane(String lane, String label) {
    }

    private static final Map<String, UkLane> UK_LANES = Map.ofEntries(
            Map.entry("LDN", new UkLane("south-central", "South East")),
            Map.entry("SE", new UkLane("southeast", "South East")),
            Map.entry("SW", new UkLane("southeast", "South West")),
            Map.entry("WLS", new UkLane("mountain", "South Wales corridor")),
            Map.entry("WM", new UkLane("southeast", "Midlands")),
            Map.entry("EM", new UkLane("midwest", "Midlands")),
            Map.entry("EE", new UkLane("midwest", "East of England")),
            Map.entry("NW", new UkLane("southwest", "North West")),
            Map.entry("YH", new UkLane("midwest", "Yorkshire & Humber")),
            Map.entry("NE", new UkLane("midwest", "North East")),
            Map.entry("SCT", new UkLane("pacific-northwest", "Central Belt")),
            Map.entry("NI", new UkLane("south-central", "Northern Irish corridor")));

    private static final CatalogGateway.Origin FALLBACK_ORIGIN = new CatalogGateway.Origin(
            "east", "ocean", "Savannah, GA", 7.5, 30, 5.0, "MFN rate, unlisted origin");

    /** Midwest: the network's centre of gravity, same as the frontend's {@code REGIONS[4]}. */
    private static CatalogGateway.LaneRef fallbackRegion(CatalogGateway.LogisticsRef ref) {
        return ref.regions().stream().filter(r -> "midwest".equals(r.key())).findFirst()
                .orElseThrow(() -> new IllegalStateException("logistics_lanes has no midwest row"));
    }

    /** The market region a store's state/subdivision rides the freight lane of. */
    static CatalogGateway.LaneRef regionForState(CatalogGateway.LogisticsRef ref, String state) {
        if (state == null) {
            return fallbackRegion(ref);
        }
        for (CatalogGateway.LaneRef r : ref.regions()) {
            if (r.states().contains(state)) {
                return r;
            }
        }
        UkLane uk = UK_LANES.get(state);
        if (uk != null) {
            CatalogGateway.LaneRef base = ref.regions().stream().filter(r -> r.key().equals(uk.lane()))
                    .findFirst().orElse(fallbackRegion(ref));
            return new CatalogGateway.LaneRef(base.key(), uk.label(), base.states(), base.inlandPct(), base.inlandDays());
        }
        return fallbackRegion(ref);
    }

    /** Ex-works plus freight and duty on this origin's lane into this region. */
    static Lane laneFor(CatalogGateway.LogisticsRef ref, String originCountry, CatalogGateway.LaneRef region) {
        CatalogGateway.Origin o = ref.origins().getOrDefault(originCountry, FALLBACK_ORIGIN);
        double inlandPct = region.inlandPct().getOrDefault(o.entry(), java.math.BigDecimal.ZERO).doubleValue();
        int inlandDays = region.inlandDays().getOrDefault(o.entry(), 0);
        double freightPct = Math.round((o.inboundPct() + inlandPct) * 10.0) / 10.0;
        int transitDays = o.inboundDays() + inlandDays;
        String routeNote = "domestic".equals(o.mode())
                ? "Domestic haul to the " + region.label() + ", " + transitDays + "d"
                : ("ocean".equals(o.mode()) ? "Ocean" : "Overland") + " from " + originCountry + " via " + o.gateway()
                        + ", then inland to the " + region.label() + " - " + o.inboundDays() + "d + " + inlandDays + "d";

        return new Lane(originCountry, region.key(), region.label(), o.mode(), o.gateway(), freightPct, o.dutyPct(),
                o.dutyNote(), transitDays, routeNote);
    }

    /** Ex-works plus freight and duty on that lane. Unused by the buy engine directly, kept for parity. */
    static double landedCost(double exWorks, Lane lane) {
        return Math.round(exWorks * (1 + (lane.freightPct() + lane.dutyPct()) / 100.0) * 100.0) / 100.0;
    }
}
