package com.aatlas.buy.internal;

import com.aatlas.buy.BuyModelSummary;
import com.aatlas.buy.CalcStep;
import com.aatlas.buy.MarketEvidence;
import com.aatlas.history.DecisionPatterns;
import com.aatlas.history.PricingModel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The target-cost chain, as the tenant's buying model configures it: which evidence counts,
 * where between the best price and the market median the target sits, what the tenant's own
 * decisions and today's cost do to it, and the guardrails. Pure - comparable prices and a
 * track record in, a target and its derivation out - so the composition is testable without
 * a database, and every step records whether it {@code applied}, was {@code skipped} for
 * want of its input, or is {@code off} in the model.
 *
 * <p>Order, which is also the order of {@link Result#steps()}: open-market evidence (which of
 * it is plausible, and whether it clearly disagrees with what the suppliers quote), the
 * evidence range and median, the aim between the best price and the median, never above
 * today's cost, the lean from past decisions, the phase-in from today's cost, the move cap,
 * the floor at the lowest real price, the result. The floor is the one guardrail nothing here
 * can switch off.
 *
 * <p>A <b>market gap</b> is the case the plausibility filter must not swallow: several
 * open-market prices that agree with each other but sit far from the suppliers' quotes (or,
 * without quotes, from today's cost). Then the market is the going rate and the suppliers are
 * the outlier: the target is composed over the market set alone and shown at once, past the
 * phase-in and the move cap.
 */
final class BuyTargetChain {

    static final String APPLIED = "applied";
    static final String SKIPPED = "skipped";
    static final String OFF = "off";

    private static final BigDecimal THREE = BigDecimal.valueOf(3);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int WORK_SCALE = 6;

    private BuyTargetChain() {
    }

    /**
     * The pair's decision history, as the chain reads it.
     *
     * @param priorApplied buy decisions already recorded for this item at this branch (the phase-in's n)
     * @param learning the lean from past decisions, or null when none was read
     * @param available false when the decision tables could not be read: the chain then
     *                  phases in from zero and skips the lean, saying so
     */
    record Track(long priorApplied, DecisionPatterns.Learning learning, boolean available) {

        static Track none() {
            return new Track(0, null, false);
        }

        static Track of(long priorApplied, DecisionPatterns.Learning learning) {
            return new Track(priorApplied, learning, true);
        }
    }

    /**
     * A credible open market that clearly disagrees with the reference price: the suppliers'
     * quote median when there are quotes, else what the tenant pays today.
     *
     * @param median the market set's median, which the target is composed toward
     * @param low the lowest market price in the set
     * @param high the highest market price in the set
     * @param count how many market prices agree
     * @param gapPct how far the market median sits from the reference, as a percentage of the
     *               reference, 2 dp: {@code |median / reference - 1| * 100}
     * @param direction {@link #BELOW} or {@link #ABOVE}: where the market sits relative to the reference
     */
    record MarketGap(BigDecimal median, BigDecimal low, BigDecimal high, int count, BigDecimal gapPct,
            String direction) {

        static final String BELOW = "below";
        static final String ABOVE = "above";
    }

    /** Whether the counted market prices read as one market, and if not, why. */
    private record Agreement(boolean credible, String reason) {
    }

    /**
     * What the chain produced.
     *
     * @param points the comparable prices that counted, in the order they were given
     * @param dropped market points left out as implausible
     * @param marketLow the lowest evidence the target was composed over (the market set alone under a market gap)
     * @param marketMedian the median of that evidence
     * @param marketHigh the highest of that evidence
     * @param target the target shown, rounded to 4 dp; null with no evidence
     * @param fullTarget the target before the phase-in, rounded to 4 dp; equals {@code target} when nothing is phased in
     * @param maturity 0-1 when the phase-in acted, else null
     * @param steps the derivation from the evidence to the result
     * @param flags {@link BuyModelSummary#FLAG_TARGET_AT_FLOOR}, {@link BuyModelSummary#FLAG_MOVE_CAPPED},
     *              {@link BuyModelSummary#FLAG_COMPETITOR_IMPLAUSIBLE}, {@link BuyModelSummary#FLAG_MARKET_GAP}
     * @param marketGap the market gap the target followed, or null when there was none
     */
    record Result(List<MarketEvidence.Point> points, List<MarketEvidence.Point> dropped, BigDecimal marketLow,
            BigDecimal marketMedian, BigDecimal marketHigh, BigDecimal target, BigDecimal fullTarget,
            BigDecimal maturity, List<CalcStep> steps, List<String> flags, MarketGap marketGap) {

        boolean fromMarket() {
            return points.stream().anyMatch(p -> !MarketEvidence.SUPPLIER.equals(p.source()));
        }

        boolean fromPanel() {
            return points.stream().anyMatch(p -> MarketEvidence.SUPPLIER.equals(p.source()));
        }

        /**
         * {@code panel}, {@code market}, {@code panel+market}, or null with nothing to target
         * from. Under a market gap the target is composed over the market alone, so
         * {@code market} even when supplier quotes are listed.
         */
        String targetBasis() {
            if (marketGap != null) {
                return "market";
            }
            boolean panel = fromPanel();
            boolean market = fromMarket();
            return panel && market ? "panel+market" : panel ? "panel" : market ? "market" : null;
        }
    }

    /**
     * @param candidates every comparable price on file: supplier quotes landed, bulk lots, a should-cost
     * @param currentCost what the tenant pays today, or null
     * @param track the pair's decision history; {@link Track#none()} when it could not be read
     * @param regionLabel the destination region, for the panel note
     */
    static Result compose(List<MarketEvidence.Point> candidates, BigDecimal currentCost, Track track,
            PricingModel.Config cfg, String regionLabel) {
        List<CalcStep> steps = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        Track t = track == null ? Track.none() : track;

        // ---- 1. which evidence counts ----------------------------------------------------
        List<MarketEvidence.Point> supplier = new ArrayList<>();
        List<MarketEvidence.Point> market = new ArrayList<>();
        for (MarketEvidence.Point p : candidates) {
            if (p.value() == null) {
                continue;
            }
            if (MarketEvidence.SUPPLIER.equals(p.source())) {
                supplier.add(p);
            } else {
                market.add(p);
            }
        }
        List<MarketEvidence.Point> kept = new ArrayList<>(supplier);
        List<MarketEvidence.Point> dropped = new ArrayList<>();
        List<MarketEvidence.Point> counted = new ArrayList<>();
        BigDecimal supplierMedian = median(supplier.stream().map(MarketEvidence.Point::value).sorted().toList());
        MarketGap gap = null;

        if (!cfg.on(PricingModel.BUY_MARKET)) {
            steps.add(new CalcStep("Open-market evidence", market.isEmpty() ? "—" : market.size() + " left out",
                    "Only your suppliers' quotes count; bulk-lot and shop prices are not used by your buying model.",
                    OFF));
        } else {
            boolean bulkOn = cfg.on(PricingModel.BUY_MARKET_BULK_LOTS);
            boolean retailOn = cfg.on(PricingModel.BUY_MARKET_RETAIL_DERIVED);
            List<String> leftOut = new ArrayList<>();
            for (MarketEvidence.Point p : market) {
                if (MarketEvidence.BULK_LOTS.equals(p.source()) && !bulkOn) {
                    leftOut.add("Bulk-lot prices are switched off in your buying model.");
                } else if (MarketEvidence.RETAIL_DERIVED.equals(p.source()) && !retailOn) {
                    leftOut.add("The should-cost from shop prices is switched off in your buying model.");
                } else {
                    counted.add(p);
                }
            }
            String switchedOff = leftOut.isEmpty() ? "" : " " + String.join(" ", leftOut);
            if (counted.isEmpty()) {
                steps.add(new CalcStep("Open-market evidence", "—",
                        (market.isEmpty() ? "No bulk-lot or shop prices on file yet; only your suppliers' quotes count."
                                : "Only your suppliers' quotes count.") + switchedOff, SKIPPED));
            } else {
                steps.add(new CalcStep("Open-market evidence", counted.size() + (counted.size() == 1 ? " price" : " prices"),
                        describe(counted) + (supplier.isEmpty() ? " - the only evidence, with no supplier quotes on file."
                                : (counted.size() == 1 ? " joins" : " join") + " your suppliers' quotes as evidence of the going rate.")
                                + switchedOff, APPLIED));
            }
            for (MarketEvidence.Point p : counted) {
                steps.add(new CalcStep(p.label(), Js.fmtMoney(p.value().doubleValue()), p.detail(), "step"));
            }

            // ---- 1b. do the market prices read as one market? Enough of them, close to each other.
            List<BigDecimal> marketValues = counted.stream().map(MarketEvidence.Point::value).sorted().toList();
            Agreement agreement = agreement(marketValues, cfg);

            // ---- 1c. plausibility: a lone market price far from what suppliers quote is left out, said as
            // how far it sits and never as a guess at why. Prices that agree with each other are never dropped
            // for being far away.
            if (!cfg.on(PricingModel.BUY_MARKET_PLAUSIBILITY)) {
                kept.addAll(counted);
                steps.add(new CalcStep("Market prices far from your quotes", "—",
                        "Market prices are used as they come.", OFF));
            } else if (supplierMedian == null) {
                kept.addAll(counted);
                steps.add(new CalcStep("Market prices far from your quotes", "—",
                        "No supplier quotes to check the market prices against.", SKIPPED));
            } else if (counted.isEmpty()) {
                steps.add(new CalcStep("Market prices far from your quotes", "—", "No market prices to check.", SKIPPED));
            } else if (agreement.credible()) {
                kept.addAll(counted);
                steps.add(new CalcStep("Market prices far from your quotes", "none",
                        "All " + counted.size() + " market prices agree with each other (from "
                                + Js.fmtMoney(marketValues.get(0).doubleValue()) + " to "
                                + Js.fmtMoney(marketValues.get(marketValues.size() - 1).doubleValue())
                                + "), so none is left out for being far from your suppliers' median "
                                + Js.fmtMoney(supplierMedian.doubleValue()) + ".", APPLIED));
            } else {
                BigDecimal lo = supplierMedian.divide(THREE, WORK_SCALE, RoundingMode.HALF_UP);
                BigDecimal hi = supplierMedian.multiply(THREE);
                String medianText = "your suppliers' median " + Js.fmtMoney(supplierMedian.doubleValue());
                List<String> why = new ArrayList<>();
                for (MarketEvidence.Point p : counted) {
                    String what = MarketEvidence.BULK_LOTS.equals(p.source()) ? "bulk lots"
                            : MarketEvidence.RETAIL_DERIVED.equals(p.source()) ? "the should-cost from shop prices"
                            : p.label();
                    if (p.value().compareTo(lo) < 0) {
                        dropped.add(p);
                        why.add(what + " at " + Js.fmtMoney(p.value().doubleValue()) + ", under a third of " + medianText);
                    } else if (p.value().compareTo(hi) > 0) {
                        dropped.add(p);
                        why.add(what + " at " + Js.fmtMoney(p.value().doubleValue()) + ", over three times " + medianText);
                    } else {
                        kept.add(p);
                    }
                }
                if (dropped.isEmpty()) {
                    steps.add(new CalcStep("Market prices far from your quotes", "none",
                            "All " + counted.size() + " market " + (counted.size() == 1 ? "price sits" : "prices sit")
                                    + " between a third and three times your suppliers' median "
                                    + Js.fmtMoney(supplierMedian.doubleValue()) + ".", APPLIED));
                } else {
                    flags.add(BuyModelSummary.FLAG_COMPETITOR_IMPLAUSIBLE);
                    steps.add(new CalcStep("Market prices far from your quotes", dropped.size() + " left out",
                            "Left out " + String.join("; ", why) + ".",
                            APPLIED));
                }
            }

            // ---- 1d. market gap: a credible market that clearly disagrees with what you pay ----
            if (!cfg.on(PricingModel.BUY_MARKET_GAP)) {
                steps.add(new CalcStep("Market gap", "—",
                        "Open-market prices that clearly disagree with your suppliers are not followed by your buying model.",
                        OFF));
            } else if (counted.isEmpty()) {
                steps.add(new CalcStep("Market gap", "—",
                        "No open-market prices on file to compare with what you pay.", SKIPPED));
            } else if (!agreement.credible()) {
                steps.add(new CalcStep("Market gap", "—", agreement.reason(), SKIPPED));
            } else {
                int count = marketValues.size();
                BigDecimal mLow = marketValues.get(0);
                BigDecimal mHigh = marketValues.get(count - 1);
                BigDecimal mMedian = median(marketValues);
                BigDecimal ref = supplierMedian != null ? supplierMedian : currentCost;
                String agree = count == 1 ? "1 open-market price at " + Js.fmtMoney(mMedian.doubleValue())
                        : count + " open-market prices agree at around " + Js.fmtMoney(mMedian.doubleValue()) + " (from "
                                + Js.fmtMoney(mLow.doubleValue()) + " to " + Js.fmtMoney(mHigh.doubleValue()) + ")";
                if (ref == null || ref.signum() <= 0) {
                    steps.add(new CalcStep("Market gap", "—", capitalize(agree)
                            + ", but there are no supplier quotes and no cost on file to compare the market with.", SKIPPED));
                } else {
                    String refText = supplierMedian != null ? "your suppliers quote " + Js.fmtMoney(ref.doubleValue())
                            : "you pay " + Js.fmtMoney(ref.doubleValue()) + " today";
                    BigDecimal threshold = cfg.value(PricingModel.BUY_MARKET_GAP_THRESHOLD);
                    BigDecimal gapPct = mMedian.divide(ref, WORK_SCALE, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                            .abs().multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
                    String direction = mMedian.compareTo(ref) < 0 ? MarketGap.BELOW : MarketGap.ABOVE;
                    if (gapPct.compareTo(threshold) <= 0) {
                        steps.add(new CalcStep("Market gap", "—", capitalize(agree) + "; " + refText + ", within "
                                + plain(threshold) + "% of it, so the market and your suppliers agree.", SKIPPED));
                    } else {
                        gap = new MarketGap(mMedian, mLow, mHigh, count, gapPct, direction);
                        flags.add(BuyModelSummary.FLAG_MARKET_GAP);
                        boolean goodNews = MarketGap.ABOVE.equals(direction) && currentCost != null
                                && cfg.on(PricingModel.BUY_NEVER_ABOVE_CURRENT) && currentCost.compareTo(mLow) <= 0;
                        String outcome = goodNews
                                ? "Good news: you already buy under the market, so the target stays at what you pay."
                                : "The target follows the market.";
                        steps.add(new CalcStep("Market gap", Js.fmtMoney(mMedian.doubleValue()),
                                capitalize(agree) + "; " + refText + ", and the market sits "
                                        + Js.toFixed(gapPct.doubleValue(), 0) + "% " + direction + " that. " + outcome,
                                APPLIED));
                    }
                }
            }
        }

        // ---- 2. the evidence range and median ---------------------------------------------
        // Under a market gap the target is composed over the market set alone; the suppliers'
        // quotes are still listed, and still count toward the floor at the lowest real price.
        List<BigDecimal> all = kept.stream().map(MarketEvidence.Point::value).sorted().toList();
        BigDecimal floor = all.isEmpty() ? null : all.get(0);
        List<BigDecimal> evidence = gap != null ? counted.stream().map(MarketEvidence.Point::value).sorted().toList() : all;
        int n = evidence.size();
        BigDecimal low = n == 0 ? null : evidence.get(0);
        BigDecimal high = n == 0 ? null : evidence.get(n - 1);
        BigDecimal median = median(evidence);
        boolean withMarket = kept.stream().anyMatch(p -> !MarketEvidence.SUPPLIER.equals(p.source()));
        if (n == 0) {
            steps.add(new CalcStep("Between the best price and the median", "—",
                    "No comparable prices on file yet, so there is no target. Add supplier prices or check the "
                            + "retail and bulk market.", cfg.on(PricingModel.BUY_TARGET) ? SKIPPED : OFF));
            return new Result(List.copyOf(kept), List.copyOf(dropped), null, null, null, null, null, null,
                    List.copyOf(steps), List.copyOf(flags), null);
        }
        String rangeNote;
        if (gap != null) {
            rangeNote = n + " open-market price" + (n == 1 ? "" : "s") + " that agree with each other"
                    + (supplierMedian == null ? ", far from what you pay today"
                            : "; your suppliers' quotes, median " + Js.fmtMoney(supplierMedian.doubleValue())
                                    + ", are listed but the target no longer starts from them");
        } else if (withMarket) {
            rangeNote = n + " comparable prices: your suppliers' quotes and the open market";
        } else {
            rangeNote = "Suppliers able to serve this item, each on its own lane into the " + regionLabel;
        }
        steps.add(new CalcStep(withMarket ? "Market evidence, per unit" : "Panel quotes, landed here",
                Js.fmtMoney(low.doubleValue()) + " - " + Js.fmtMoney(high.doubleValue()), rangeNote, "step"));
        steps.add(new CalcStep("Market median", Js.fmtMoney(median.doubleValue()),
                gap != null ? "Middle of the open market" : withMarket ? "Middle of the evidence" : "Middle of the panel",
                "step"));

        // ---- 3. where the target starts ---------------------------------------------------
        BigDecimal running;
        String startNote;
        String lowestWhat = gap != null ? "the lowest open-market price" : "the lowest comparable price";
        if (!cfg.on(PricingModel.BUY_TARGET)) {
            running = low;
            startNote = lowestWhat;
            steps.add(new CalcStep("Between the best price and the median", Js.fmtMoney(running.doubleValue()),
                    "The target starts at " + lowestWhat + ", " + Js.fmtMoney(low.doubleValue()) + ".", OFF));
        } else if (n < 2) {
            running = low;
            startNote = gap != null ? "the one open-market price on file" : "the one comparable price on file";
            steps.add(new CalcStep("Between the best price and the median", Js.fmtMoney(running.doubleValue()),
                    "Only one " + (gap != null ? "open-market" : "comparable") + " price on file, so the target starts there.",
                    SKIPPED));
        } else {
            BigDecimal share = cfg.value(PricingModel.BUY_TARGET_GAP_SHARE);
            running = low.add(median.subtract(low).multiply(share).divide(HUNDRED, WORK_SCALE, RoundingMode.HALF_UP));
            startNote = lowestWhat + " plus " + plain(share) + "% of the gap to the " + (gap != null ? "market median" : "median");
            steps.add(new CalcStep("Between the best price and the median", Js.fmtMoney(running.doubleValue()),
                    (gap != null ? "Lowest open-market price " : "Lowest ") + Js.fmtMoney(low.doubleValue()) + " plus "
                            + plain(share) + "% of the gap to the " + (gap != null ? "market median " : "median ")
                            + Js.fmtMoney(median.doubleValue()) + ": achievable, not the single best listing.", APPLIED));
        }

        // ---- 4. never above what you pay today --------------------------------------------
        boolean heldAtCurrent = false;
        if (!cfg.on(PricingModel.BUY_NEVER_ABOVE_CURRENT)) {
            steps.add(new CalcStep("Never above what you pay today", Js.fmtMoney(running.doubleValue()),
                    "The target may rise to the evidence even when that is above what you pay today.", OFF));
        } else if (currentCost == null) {
            steps.add(new CalcStep("Never above what you pay today", Js.fmtMoney(running.doubleValue()),
                    "No cost on file for what you pay today.", SKIPPED));
        } else if (running.compareTo(currentCost) > 0) {
            running = currentCost;
            heldAtCurrent = true;
            steps.add(new CalcStep("Never above what you pay today", Js.fmtMoney(running.doubleValue()),
                    gap != null && MarketGap.ABOVE.equals(gap.direction())
                            ? "The open market sits above what you pay today, " + Js.fmtMoney(currentCost.doubleValue())
                                    + ": good news, you already buy under the market, so the target is held there."
                            : "The evidence sits above what you pay today, " + Js.fmtMoney(currentCost.doubleValue())
                                    + ", so the target is held there.", APPLIED));
        } else {
            steps.add(new CalcStep("Never above what you pay today", Js.fmtMoney(running.doubleValue()),
                    "Already under what you pay today, " + Js.fmtMoney(currentCost.doubleValue()) + ".", APPLIED));
        }

        // ---- 5. the lean from past decisions ----------------------------------------------
        DecisionPatterns.Learning learning = t.learning();
        if (!cfg.on(PricingModel.BUY_LEARNING)) {
            steps.add(new CalcStep("Your decisions", Js.fmtMoney(running.doubleValue()),
                    "Your past purchase decisions are not used by your buying model.", OFF));
        } else if (!t.available()) {
            steps.add(new CalcStep("Your decisions", Js.fmtMoney(running.doubleValue()),
                    "Decision history unavailable; the target stands.", SKIPPED));
        } else if (learning == null || !learning.available()) {
            steps.add(new CalcStep("Your decisions", Js.fmtMoney(running.doubleValue()),
                    learning == null ? "No purchase decisions recorded yet." : learning.note(), SKIPPED));
        } else {
            running = running.multiply(BigDecimal.valueOf(1 + learning.movePct() / 100))
                    .setScale(WORK_SCALE, RoundingMode.HALF_UP);
            steps.add(new CalcStep("Your decisions", Js.fmtMoney(running.doubleValue()), learning.note(), APPLIED));
        }

        // The full target: what the model aims at before it is phased in. It never goes under the
        // lowest real price either.
        boolean fullAtFloor = running.compareTo(floor) < 0;
        BigDecimal full = fullAtFloor ? floor : running;

        // ---- 6. phase-in from today's cost ------------------------------------------------
        BigDecimal maturity = null;
        if (!cfg.on(PricingModel.BUY_PHASE_IN)) {
            running = full;
            steps.add(new CalcStep("Phase-in", Js.fmtMoney(running.doubleValue()),
                    "The full target is shown at once.", OFF));
        } else if (gap != null) {
            running = full;
            steps.add(new CalcStep("Phase-in", Js.fmtMoney(running.doubleValue()),
                    "Market gap: the target follows the market at once, without phasing in from today's cost.", SKIPPED));
        } else if (currentCost == null) {
            running = full;
            steps.add(new CalcStep("Phase-in", Js.fmtMoney(running.doubleValue()),
                    "No cost on file to phase in from; the full target is shown.", SKIPPED));
        } else {
            long prior = t.priorApplied();
            double m = DecisionPatterns.maturity(prior, cfg.number(PricingModel.BUY_PHASE_IN_HALF_POINT),
                    cfg.number(PricingModel.BUY_PHASE_IN_LAUNCH) / 100);
            maturity = BigDecimal.valueOf(m).setScale(2, RoundingMode.HALF_UP);
            running = currentCost.add(full.subtract(currentCost).multiply(BigDecimal.valueOf(m)))
                    .setScale(WORK_SCALE, RoundingMode.HALF_UP);
            steps.add(new CalcStep("Phase-in", Js.fmtMoney(running.doubleValue()),
                    prior + " prior decision" + (prior == 1 ? "" : "s") + " for this item here → "
                            + Js.toFixed(m * 100, 0) + "% of the way from today's " + Js.fmtMoney(currentCost.doubleValue())
                            + " to the full target " + Js.fmtMoney(full.doubleValue()) + "."
                            + (t.available() ? "" : " (Decision history unavailable, so counted as none.)"), APPLIED));
        }

        // ---- 7. the move cap --------------------------------------------------------------
        boolean capped = false;
        if (!cfg.on(PricingModel.BUY_MOVE_CAP)) {
            steps.add(new CalcStep("Move cap", Js.fmtMoney(running.doubleValue()),
                    "No limit on how far the target moves from what you pay today.", OFF));
        } else if (gap != null) {
            steps.add(new CalcStep("Move cap", Js.fmtMoney(running.doubleValue()),
                    "Market gap: the target follows the market at once, past the usual move limit.", SKIPPED));
        } else if (currentCost == null) {
            steps.add(new CalcStep("Move cap", Js.fmtMoney(running.doubleValue()),
                    "No cost on file to measure the move from.", SKIPPED));
        } else {
            BigDecimal capPct = cfg.value(PricingModel.BUY_MOVE_CAP_MAX_PCT);
            BigDecimal cap = capPct.divide(HUNDRED, WORK_SCALE, RoundingMode.HALF_UP);
            BigDecimal capLo = currentCost.multiply(BigDecimal.ONE.subtract(cap)).setScale(WORK_SCALE, RoundingMode.HALF_UP);
            BigDecimal capHi = currentCost.multiply(BigDecimal.ONE.add(cap)).setScale(WORK_SCALE, RoundingMode.HALF_UP);
            if (running.compareTo(capLo) < 0) {
                running = capLo;
                capped = true;
            } else if (running.compareTo(capHi) > 0) {
                running = capHi;
                capped = true;
            }
            if (capped) {
                flags.add(BuyModelSummary.FLAG_MOVE_CAPPED);
            }
            steps.add(new CalcStep("Move cap", Js.fmtMoney(running.doubleValue()),
                    capped ? "Held within " + plain(capPct) + "% of today's " + Js.fmtMoney(currentCost.doubleValue())
                            + " for this run; the rest comes as you record decisions."
                            : "Within " + plain(capPct) + "% of today's " + Js.fmtMoney(currentCost.doubleValue()) + ".",
                    APPLIED));
        }

        // ---- 8. the floor nothing switches off --------------------------------------------
        boolean atFloor = fullAtFloor;
        if (running.compareTo(floor) < 0) {
            running = floor;
            atFloor = true;
        }
        if (atFloor) {
            steps.add(new CalcStep("Lowest real price", Js.fmtMoney(floor.doubleValue()),
                    "The target never goes under the lowest real price on file.", "step"));
            flags.add(BuyModelSummary.FLAG_TARGET_AT_FLOOR);
        }

        BigDecimal target = running.setScale(4, RoundingMode.HALF_UP);
        BigDecimal fullTarget = full.setScale(4, RoundingMode.HALF_UP);

        // ---- 9. the result ----------------------------------------------------------------
        StringBuilder note = new StringBuilder(capitalize(startNote));
        if (heldAtCurrent) {
            note.append(", held at what you pay today");
        }
        if (gap != null) {
            note.append("; a market gap, so it follows the open market at once");
        }
        if (maturity != null && fullTarget.compareTo(target) != 0) {
            note.append("; shown ").append(Js.toFixed(maturity.doubleValue() * 100, 0))
                    .append("% of the way from today's cost to the full target ")
                    .append(Js.fmtMoney(fullTarget.doubleValue()));
        }
        if (capped) {
            note.append("; move capped this run");
        }
        if (atFloor) {
            note.append("; held at the lowest real price on file");
        }
        note.append('.');
        steps.add(new CalcStep("Target set at", Js.fmtMoney(target.doubleValue()), note.toString(), "result"));

        return new Result(List.copyOf(kept), List.copyOf(dropped), low, median, high, target, fullTarget, maturity,
                List.copyOf(steps), List.copyOf(flags), gap);
    }

    /**
     * Whether the counted market prices read as one market: at least the model's number of
     * them, and the highest no more than the model's agreement above the lowest. Only judged
     * when the market-gap step is on; off, each price is judged on its own as before.
     */
    private static Agreement agreement(List<BigDecimal> sortedMarket, PricingModel.Config cfg) {
        if (!cfg.on(PricingModel.BUY_MARKET_GAP) || sortedMarket.isEmpty()) {
            return new Agreement(false, null);
        }
        int count = sortedMarket.size();
        int minAgreeing = cfg.value(PricingModel.BUY_MARKET_GAP_MIN_AGREEING).intValue();
        if (count < minAgreeing) {
            return new Agreement(false, "Only " + count + " open-market price" + (count == 1 ? "" : "s") + " on file; "
                    + minAgreeing + " must agree with each other before the target follows the market.");
        }
        BigDecimal low = sortedMarket.get(0);
        BigDecimal high = sortedMarket.get(count - 1);
        BigDecimal agreementPct = cfg.value(PricingModel.BUY_MARKET_GAP_AGREEMENT);
        BigDecimal limit = low.multiply(BigDecimal.ONE.add(agreementPct.divide(HUNDRED, WORK_SCALE, RoundingMode.HALF_UP)));
        if (high.compareTo(limit) > 0) {
            return new Agreement(false, "The " + count + " open-market prices disagree with each other ("
                    + Js.fmtMoney(low.doubleValue()) + " to " + Js.fmtMoney(high.doubleValue()) + ", more than "
                    + plain(agreementPct) + "% apart), so they are not read as one market and each is judged on its own.");
        }
        return new Agreement(true, null);
    }

    static BigDecimal median(List<BigDecimal> sorted) {
        if (sorted.isEmpty()) {
            return null;
        }
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return sorted.get(mid - 1).add(sorted.get(mid)).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    private static String describe(List<MarketEvidence.Point> counted) {
        List<String> parts = new ArrayList<>();
        for (MarketEvidence.Point p : counted) {
            String what = MarketEvidence.BULK_LOTS.equals(p.source()) ? "bulk lots at "
                    : MarketEvidence.RETAIL_DERIVED.equals(p.source()) ? "a should-cost from shop prices of " : p.label() + " ";
            parts.add(what + Js.fmtMoney(p.value().doubleValue()));
        }
        return capitalize(String.join(" and ", parts));
    }

    private static String plain(BigDecimal v) {
        BigDecimal stripped = v.stripTrailingZeros();
        return stripped.scale() <= 0 ? stripped.toBigInteger().toString() : stripped.toPlainString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
