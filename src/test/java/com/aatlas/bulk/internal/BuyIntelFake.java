package com.aatlas.bulk.internal;

import com.aatlas.buy.BuyIntel;
import com.aatlas.buy.BuyIntelReader;
import com.aatlas.buy.CommercialTerms;
import com.aatlas.buy.SupplierEval;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A deterministic fake of {@code buy.BuyIntelReader} for {@link BulkBuyEngineTest}: eight
 * suppliers per item, spread apart on cost, lead time and OTIF so the five bulk strategies
 * genuinely diverge, with no database and no dependency on the real buy engine (a
 * different worktree, not yet visible here).
 */
final class BuyIntelFake implements BuyIntelReader {

    private static final List<String> COUNTRIES = List.of(
            "USA", "USA", "Mexico", "Canada", "China", "Germany", "USA", "Vietnam");

    @Override
    public BuyIntel getBuyIntel(String itemNumber, String regionKey, int qty, String destinationId) {
        int base = Math.floorMod(itemNumber.hashCode(), 50) + 10; // 10..59

        List<SupplierEval> suppliers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double quoted = base * (1 + i * 0.07);
            double freightAndDuty = quoted * (0.03 + i * 0.01);
            double landed = quoted + freightAndDuty;
            int leadDays = 12 + i * 4;
            double otifPct = 96 - i * 3.5;
            double defectPct = 0.4 + i * 0.2;
            double fulfilmentPct = 95 - i;
            double effective = landed * (1 + i * 0.01);
            String risk = otifPct < 82 ? "High" : otifPct < 90 ? "Medium" : "Low";
            CommercialTerms terms = new CommercialTerms(30 + i, "Net " + (30 + i), bd(i == 0 ? 2 : 0), 10,
                    bd(1.5), bd(8), 12, 30, "FOB", bd(97), 5000);

            suppliers.add(new SupplierEval("sup-" + (i + 1), "Supplier " + (i + 1), COUNTRIES.get(i),
                    bd(round2(quoted)), bd(round2(landed)), bd(round2(effective)), bd(round2(freightAndDuty)),
                    leadDays, bd(otifPct), bd(defectPct), bd(fulfilmentPct), terms.termsLabel(), terms,
                    BigDecimal.ZERO, 50, true, i == 0 ? 6 : 1, List.of(), i == 0, i == 0, risk, risk + " risk",
                    true, null));
        }

        SupplierEval incumbent = suppliers.get(0);
        SupplierEval recommendedSupplier = suppliers.stream()
                .min(Comparator.comparing(SupplierEval::effective)).orElseThrow();
        SupplierEval cheapestQuoted = suppliers.stream()
                .min(Comparator.comparing(SupplierEval::landed)).orElseThrow();

        return new BuyIntel(itemNumber, itemNumber, itemNumber, true, regionKey, BuyMath.regionLabel(regionKey),
                destinationId != null ? destinationId : "100959", "Dallas #100959", qty,
                incumbent.landed(), recommendedSupplier.landed(), BigDecimal.ZERO, BigDecimal.ZERO,
                cheapestQuoted.landed(), BigDecimal.ZERO, BigDecimal.ZERO,
                bd(round2(Math.max(0, incumbent.landed().doubleValue() - recommendedSupplier.landed().doubleValue()))),
                BigDecimal.ZERO, BigDecimal.ZERO, 70, "Medium",
                suppliers, incumbent, recommendedSupplier, cheapestQuoted, "fake",
                List.of(), null, null, null, base * 500, Map.of(), List.of());
    }

    private static BigDecimal bd(double n) {
        return BigDecimal.valueOf(n);
    }

    private static double round2(double n) {
        return Math.round(n * 100) / 100.0;
    }
}
