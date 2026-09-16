package com.aatlas.rfq.internal;

import com.aatlas.buy.ScoredSupplier;
import com.aatlas.buy.Weights;
import com.aatlas.common.seed.Seeded;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The two pure functions {@code intel/rfq.ts} carries: a deterministic supplier reply when
 * nobody has typed one in, and which live reply to take under the order's own priorities.
 * Ported exactly, seed for seed, against {@link ScoredSupplier} - the same panel {@code buy}
 * already ranked for this order, not a second scoring pass.
 */
final class RfqEngine {

    private RfqEngine() {
    }

    record SimulatedQuote(boolean declined, BigDecimal quoted, int leadDays, int validDays, double vsExpectedPct,
            String note) {
    }

    /**
     * {@code simulateQuote}: a reply near the invite's expected landed cost, or a decline,
     * deterministic from the round and the invite so the same round always simulates the
     * same panel.
     */
    static SimulatedQuote simulate(String itemNumber, int qty, int requiredDays, RfqInviteEntity invite) {
        String seed = "rfq:" + itemNumber + ":" + invite.getSupplierId() + ":" + qty + ":" + requiredDays;
        boolean declined = Seeded.rand(seed, "decline") > 0.86;
        double factor = Seeded.randRange(seed, "q", 0.965, 1.025);
        BigDecimal quoted = round2(invite.getExpectedLanded().doubleValue() * factor);
        int leadDays = Math.max(1, invite.getLeadDays() + (int) Math.round(Seeded.randRange(seed, "lead", -1, 2)));
        int validDays = List.of(7, 14, 30).get((int) Math.floor(Seeded.randRange(seed, "valid", 0, 2.999)));
        double vsExpectedPct = Math.round((factor - 1) * 1000) / 10.0;

        String note;
        if (declined) {
            note = "No capacity for this quantity this month.";
        } else if (leadDays > requiredDays) {
            note = "Earliest delivery " + leadDays + " days; cannot meet day " + requiredDays + ".";
        } else if (vsExpectedPct < -1.5) {
            note = "Sharpened for the volume.";
        } else if (vsExpectedPct > 1.5) {
            note = "Surcharge for the delivery window.";
        } else {
            note = "As expected.";
        }
        return new SimulatedQuote(declined, quoted, leadDays, validDays, vsExpectedPct, note);
    }

    record Recommendation(String supplierId, String reason, Map<String, Integer> scores) {
    }

    /**
     * {@code recommendQuote}: which live (non-declined) quote to take, under the plan's own
     * five weights, against the same {@link ScoredSupplier} sub-scores {@code buy} already
     * computed for this order.
     *
     * @param liveQuotes supplierId -&gt; quoted landed cost, declined quotes excluded
     * @param onTimePctBySupplier supplierId -&gt; on-time percent, for the reason text
     * @param namesBySupplier supplierId -&gt; display name
     */
    static Optional<Recommendation> recommend(Map<String, BigDecimal> liveQuotes,
            Map<String, Double> onTimePctBySupplier, Map<String, String> namesBySupplier, int requiredDays,
            Weights weights, List<ScoredSupplier> ranked) {
        if (liveQuotes.isEmpty()) {
            return Optional.empty();
        }
        double minQuote = liveQuotes.values().stream().mapToDouble(BigDecimal::doubleValue).min().orElseThrow();

        Map<String, ScoredSupplier> bySupplier = new LinkedHashMap<>();
        for (ScoredSupplier r : ranked) {
            bySupplier.put(r.s().supplierId(), r);
        }

        Map<String, Integer> scores = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : liveQuotes.entrySet()) {
            ScoredSupplier r = bySupplier.get(e.getKey());
            if (r == null) {
                continue;
            }
            double costSub = (minQuote / e.getValue().doubleValue()) * 100;
            int score = (int) Math.round(weights.cost() * costSub + weights.speed() * r.sub().speed()
                    + weights.reliability() * r.sub().reliability() + weights.risk() * r.sub().risk()
                    + weights.relationship() * r.sub().relationship());
            scores.put(e.getKey(), score);
        }
        if (scores.isEmpty()) {
            return Optional.empty();
        }

        String bestId = scores.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElseThrow();
        String cheapestId = liveQuotes.entrySet().stream()
                .min(Comparator.comparing(e -> e.getValue().doubleValue()))
                .map(Map.Entry::getKey)
                .orElseThrow();

        String bestName = namesBySupplier.get(bestId);
        double bestOnTime = onTimePctBySupplier.getOrDefault(bestId, 0.0);
        String reason;
        if (bestId.equals(cheapestId)) {
            reason = "%s quoted the lowest landed cost and still makes day %d %.0f%% of the time."
                    .formatted(bestName, requiredDays, bestOnTime);
        } else {
            String cheapestName = namesBySupplier.get(cheapestId);
            double cheapestOnTime = onTimePctBySupplier.getOrDefault(cheapestId, 0.0);
            reason = "%s quoted %s, but %s at %s arrives on time %.0f%% of the time against %.0f%%; "
                    + "on this order that is worth the difference.".formatted(cheapestName,
                            fmtMoney(liveQuotes.get(cheapestId)), bestName, fmtMoney(liveQuotes.get(bestId)),
                            bestOnTime, cheapestOnTime);
        }
        return Optional.of(new Recommendation(bestId, reason, scores));
    }

    private static BigDecimal round2(double n) {
        return BigDecimal.valueOf(n).setScale(2, RoundingMode.HALF_UP);
    }

    private static String fmtMoney(BigDecimal n) {
        return String.format(Locale.US, "$%,.2f", n);
    }

    static LocalDate closesIn(LocalDate today, int days) {
        return today.plusDays(days);
    }
}
