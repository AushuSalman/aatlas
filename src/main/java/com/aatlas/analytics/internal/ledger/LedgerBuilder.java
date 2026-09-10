package com.aatlas.analytics.internal.ledger;

import com.aatlas.analytics.internal.fixtures.Fixtures;
import com.aatlas.analytics.internal.fixtures.Lane;
import com.aatlas.analytics.internal.fixtures.ProductFixture;
import com.aatlas.analytics.internal.fixtures.RegionFixture;
import com.aatlas.analytics.internal.fixtures.StoreFixture;
import com.aatlas.analytics.internal.fixtures.SupplierFixture;
import com.aatlas.common.seed.Seeded;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the procurement ledger: ~800 purchase order lines over 26 months, one pass, month by
 * month, newest first. Ported field for field and call for call from
 * {@code platform/procurement.ts}'s {@code buildLedger} - every {@link Seeded} key matches the
 * TypeScript exactly, so the same tenant clock produces the same ledger to the cent, which is
 * what {@code golden/procurement-analytics.json}'s first-50-rows slice pins.
 */
public final class LedgerBuilder {

    /** Seasonality index by calendar month (0 = Jan), mean ~1.0. */
    private static final double[] SEASON = {0.78, 0.82, 0.95, 1.08, 1.18, 1.21, 1.14, 1.09, 1.05, 1.02, 0.9, 0.78};

    public static final int LEDGER_MONTHS = 26;

    private LedgerBuilder() {
    }

    private static double marketDrift(int monthsAgo) {
        double trend = 1 - monthsAgo * 0.0032;
        double squeeze = 0.045 * Math.exp(-Math.pow(monthsAgo - 12, 2) / 26);
        return Rounding.round4(trend + squeeze);
    }

    private static double adoptionRate(int monthsAgo) {
        return 0.4 + 0.38 * (1 - (double) monthsAgo / LEDGER_MONTHS);
    }

    /** @param today the tenant's "now" (the fixed demo clock, or the system clock for a real tenant). */
    public static List<PoRow> build(LocalDate today) {
        List<PoRow> rows = new ArrayList<>();
        List<StoreFixture> buyingBranches = Fixtures.TENANTS;
        int seq = 0;

        for (int m = 0; m < LEDGER_MONTHS; m++) {
            YearMonth monthStart = YearMonth.from(today).minusMonths(m);
            // JS getUTCMonth() is 0-indexed; the key and SEASON lookup must match it exactly.
            int jsMonth = monthStart.getMonthValue() - 1;
            String monthKey = monthStart.getYear() + "-" + jsMonth;
            double season = SEASON[jsMonth];
            double growth = 1 + (LEDGER_MONTHS - m) * 0.004;
            double drift = marketDrift(m);
            double adoption = adoptionRate(m);

            int daysInMonth = monthStart.lengthOfMonth();
            int lastDay = m == 0 ? today.getDayOfMonth() : daysInMonth;
            int full = (int) Math.max(6, Math.round(Seeded.randRange(monthKey, "n", 26, 34) * season * growth));
            int orders = m == 0
                    ? (int) Math.max(3, Math.round((double) full * lastDay / daysInMonth))
                    : full;

            for (int i = 0; i < orders; i++) {
                String k = "po:" + monthKey + ":" + i;
                ProductFixture product = Seeded.pick(k, "item", Fixtures.SELLABLE_PRODUCTS);
                StoreFixture branch = Seeded.pick(k, "branch", buyingBranches);

                String priceStore = product.defaultTenant() != null && !product.defaultTenant().isBlank()
                        ? product.defaultTenant()
                        : Fixtures.TENANTS.get(0).storeId();
                if (!PricingCost.priceable(product.itemNumber(), priceStore)) {
                    continue;
                }
                double cost = PricingCost.baseCost(product.itemNumber());

                SupplierFixture incumbent = Fixtures.currentSupplierFor(product.itemNumber());
                SupplierFixture supplier = Seeded.rand(k, "alt") > 0.26
                        ? incumbent
                        : Seeded.pick(k, "alt-who", Fixtures.SUPPLIERS);

                RegionFixture region = Fixtures.regionForStore(branch.storeId());
                Lane lane = Fixtures.laneFor(supplier.country(), region);
                double laneMult = 1 + (lane.freightPct() + lane.dutyPct()) / 100;

                double quoteEx = cost * (supplier.priceIndex() / 100) * drift;
                double baselineEx = quoteEx * Seeded.randRange(k, "base", 1.03, 1.12);
                double targetEx = quoteEx * Seeded.randRange(k, "target", 0.87, 0.97);

                boolean onTarget = Seeded.rand(k, "followed") < adoption;
                double actualEx = onTarget
                        ? targetEx * Seeded.randRange(k, "good", 0.975, 1.002)
                        : Math.min(baselineEx, targetEx * Seeded.randRange(k, "bad", 1.03, 1.15));

                double exWorks = Rounding.round2(actualEx);
                double freight = Rounding.round2(exWorks * (lane.freightPct() / 100));
                double duty = Rounding.round2(exWorks * (lane.dutyPct() / 100));
                double landed = Rounding.round2(exWorks + freight + duty);
                double baseline = Rounding.round2(baselineEx * laneMult);
                double target = Rounding.round2(targetEx * laneMult);

                int qty = cost > 300
                        ? Seeded.randInt(k, "qty", 4, 40)
                        : cost > 40
                                ? Seeded.randInt(k, "qty", 25, 260)
                                : Seeded.randInt(k, "qty", 120, 1400);

                int day = Seeded.randInt(k, "day", 1, lastDay);
                LocalDate date = LocalDate.of(monthStart.getYear(), monthStart.getMonthValue(), day);

                int promisedDays = supplier.leadTimeDays() + lane.transitDays();
                boolean late = Seeded.rand(k, "otif") * 100 > supplier.otifPct();
                int slip = late ? Seeded.randInt(k, "slip", 2, 21) : -Seeded.randInt(k, "early", 0, 3);
                int actualDays = Math.max(1, promisedDays + slip);

                long age = ChronoUnit.DAYS.between(date, today);
                String status = age >= actualDays ? "received" : age >= promisedDays * 0.35 ? "in-transit" : "open";

                String id = "PO-" + String.valueOf(monthStart.getYear()).substring(2)
                        + String.format("%02d", monthStart.getMonthValue())
                        + "-" + (1000 + i);

                boolean followed = landed <= target + 0.005;

                rows.add(new PoRow(
                        seq++,
                        id,
                        date,
                        supplier.id(),
                        supplier.name(),
                        supplier.country(),
                        product.itemNumber(),
                        product.description() == null ? product.itemNumber() : product.description(),
                        Categories.categoryOf(product.itemNumber()),
                        branch.storeId(),
                        Fixtures.storeName(branch.storeId()),
                        region.key(),
                        region.label(),
                        qty,
                        exWorks, freight, duty, landed, baseline, target, followed,
                        Rounding.round2(landed * qty),
                        Rounding.round2(baseline * qty),
                        Rounding.round2(Math.max(0, baseline - landed) * qty),
                        Rounding.round2(Math.max(0, landed - target) * qty),
                        status,
                        promisedDays,
                        actualDays,
                        actualDays - promisedDays,
                        !late,
                        date.plusDays(promisedDays),
                        "received".equals(status) ? date.plusDays(actualDays) : null));
            }
        }

        // Stable: List.sort uses a stable mergesort, so a tied date keeps build order - the
        // same guarantee Array.prototype.sort((a,b) => b.date.localeCompare(a.date)) gives.
        rows.sort(Comparator.comparing(PoRow::date).reversed());
        return rows;
    }
}
