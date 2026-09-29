package com.aatlas.competition.internal;

import com.aatlas.history.PricingMath;
import com.aatlas.history.Reference;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * "How is this priced?" in plain words, with the numbers the engines actually use.
 *
 * <p>The blend weights and the commodity pass-through are read from {@link PricingMath}, and
 * the guardrails are the tenant's own, so the page cannot drift from the single-item and bulk
 * engines. The price wizard's two weights live in {@code prices.internal.SuggestionEngine},
 * which this module may not read; they are restated here and must move with it.
 */
@Component
class PricingMethod {

    /** {@code SuggestionEngine.BENCHMARK_WEIGHT_WITH_COMPETITOR} and {@code ..._WITH_PEER}. */
    static final String WIZARD_WEIGHT_COMPETITOR = "50";
    static final String WIZARD_WEIGHT_PEER = "40";

    private final Reference reference;

    PricingMethod(Reference reference) {
        this.reference = reference;
    }

    record MethodView(List<Section> competition, List<Step> singleItem, List<Step> bulk, List<Step> wizard,
            Guardrails guardrails) {
    }

    record Section(String title, String detail) {
    }

    record Step(String title, String formula, String detail) {
    }

    record Guardrails(BigDecimal minMarginPct, BigDecimal maxMarketDeviationPct, BigDecimal maxDiscountPct,
            BigDecimal maxSpeedPremiumPct) {
    }

    MethodView describe(List<ShoppingProvider> providers) {
        Reference.Guardrails g = reference.guardrails();
        String live = providers.stream().filter(ShoppingProvider::available).map(ShoppingProvider::label)
                .reduce((a, b) -> a + ", " + b).orElse("none configured yet");

        List<Section> competition = List.of(
                new Section("1. Live shopping data (" + live + ")",
                        "\"Fetch live prices\" searches the item's description on Google Shopping (SerpApi or Oxylabs), "
                                + "eBay and Amazon (Rainforest). Each listing is kept or dropped with a reason, and what is "
                                + "kept is saved as today's observation for that store, with its link."),
                new Section("Your own competitors' sites (DataForSEO + Oxylabs)",
                        "DataForSEO finds who you compete with online - the sites ranking on Google for your items, or "
                                + "sharing your website's keywords. You track the real stores; on every lookup Oxylabs finds "
                                + "the item's page on each tracked site and the price is read from the structured data the "
                                + "store publishes for Google. A page with no published price is shown, not guessed."),
                new Section("2. Your competitor-price file",
                        "A CSV of prices you or a scraping service collected (competitor, item, price, date, region, URL), "
                                + "uploaded under Your data. Region- or branch-tagged rows are preferred for that branch."),
                new Section("3. What counts",
                        "The latest price per competitor within " + com.aatlas.history.CompetitorPrices.MAX_AGE_DAYS
                                + " days. The competitor anchor is the median of those - region- or branch-matched "
                                + "ones when there are any, otherwise all of them."),
                new Section("How listings are filtered",
                        "Dropped: no price or another currency; a title sharing under "
                                + pct(ListingFilter.MIN_MATCH.multiply(BigDecimal.valueOf(100)))
                                + " of the item's words; a second listing from a store already counted; and, with three "
                                + "or more left, anything under a third or over three times their median (a pack, a part, "
                                + "a different size)."));

        List<Step> single = List.of(
                new Step("Market anchor", "competitor median → peer median → benchmark → last price",
                        "The first one available: the competitor median; else what your other branches charge "
                                + "(median); else cost ÷ (1 − the category's benchmark margin); else this branch's last "
                                + "price."),
                new Step("Blend with your own last price",
                        "base = anchor × w + your last price × (1 − w)",
                        "w depends on how much the anchor is worth: competitor " + weight(PricingMath.WEIGHT_COMPETITOR)
                                + ", peer " + weight(PricingMath.WEIGHT_PEER) + ", benchmark "
                                + weight(PricingMath.WEIGHT_BENCHMARK) + ". With only one of the two, that one is used."),
                new Step("Demand", "× (1 + move%), move = clamp(trend% × 0.15, −3%, +3%) × confidence",
                        "Trend is recent vs prior sales at this branch; confidence grows from 0.35 to 0.95 with up to "
                                + "60 transactions."),
                new Step("Commodity", "× (1 + 90-day index move × " + weight(PricingMath.COMMODITY_PASS_THROUGH) + ")",
                        "A quarter of the item's commodity index move (copper, plastics pipe, steel…) over three months "
                                + "reaches the price. The move is the US producer price index from FRED when connected, "
                                + "else the shipped reference figure; the same move drives buy-now-vs-wait."),
                new Step("Region", "× (1 − (RPP − 100)/100 × 0.55)",
                        "Regional price parity of the branch's metro. Skipped when the anchor is a competitor price - "
                                + "that is already a local market price."),
                new Step("Floor", "cost ÷ (1 − " + g.minMarginPct().stripTrailingZeros().toPlainString() + "%)",
                        "Never under your minimum margin. With no cost on file: never under your last price or the "
                                + "lower quartile of your own prices."),
                new Step("Ceiling", "max(peers' upper quartile, anchor × (1 + "
                                + g.maxMarketDeviationPct().stripTrailingZeros().toPlainString() + "%))",
                        "Never more than your max market deviation above the market."),
                new Step("Round", "0.01 under $10 · 0.05 under $100 · 0.50 under $1,000 · 1.00 above",
                        "That is the optimal price. The aggressive price is a step above it (4–15%, smaller for "
                                + "price-sensitive items), capped at the ceiling."));

        List<Step> bulk = List.of(
                new Step("Every line priced as above",
                        "the single-item recommendation, line by line",
                        "Each item in the basket gets its own anchor, blend, adjustments, floor and ceiling."),
                new Step("Balanced", "the recommended price on every line", "Margin and velocity together."),
                new Step("Max profit", "max(recommended, aggressive price)", "Highest margin the market supports."),
                new Step("Fast movement", "max(margin floor, min(recommended, current) × 0.965)",
                        "Moves stock; never under the margin floor."),
                new Step("Which one is suggested",
                        "fast movement if average cover > 14 weeks and its profit ≥ 94% of balanced; max profit if its "
                                + "profit > 110% of balanced and turnover ≥ 50%; else balanced",
                        "Volume is projected with units = today's units × (new price ÷ current price)^elasticity."));

        List<Step> wizard = List.of(
                new Step("Needs a cost", "no cost → \"needs cost\"", "The price wizard sets list prices from cost."),
                new Step("Benchmark price", "cost ÷ (1 − target margin) × region × commodity",
                        "Target margin is your per-category margin if you set one, else the category's benchmark."),
                new Step("Blend", "with competitors: " + WIZARD_WEIGHT_COMPETITOR + "% benchmark + "
                                + WIZARD_WEIGHT_COMPETITOR + "% competitor median; else 60% benchmark + "
                                + WIZARD_WEIGHT_PEER + "% peer median",
                        "The competitor median is the same one live lookups feed."),
                new Step("Clamp and round", "between the category's low and high margin, then rounded",
                        "Every suggestion shows this basis line by line before you apply it."));

        return new MethodView(competition, single, bulk, wizard,
                new Guardrails(g.minMarginPct(), g.maxMarketDeviationPct(), g.maxDiscountPct(), g.maxSpeedPremiumPct()));
    }

    private static String weight(double w) {
        return pct(BigDecimal.valueOf(w).multiply(BigDecimal.valueOf(100)));
    }

    private static String pct(BigDecimal p) {
        return p.setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
