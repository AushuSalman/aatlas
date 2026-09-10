package com.aatlas.assistant.internal;

import com.aatlas.assistant.internal.AssistantCatalog.MarketRegion;
import com.aatlas.assistant.internal.AssistantCatalog.SeedProduct;
import com.aatlas.assistant.internal.AssistantCatalog.SeedStore;
import com.aatlas.assistant.internal.AssistantDtos.AnswerView;
import com.aatlas.assistant.internal.AssistantDtos.CtaView;
import com.aatlas.assistant.internal.AssistantDtos.HistoryEntryView;
import com.aatlas.assistant.internal.AssistantDtos.LineView;
import com.aatlas.bulk.BuyLine;
import com.aatlas.bulk.BuyLineReader;
import com.aatlas.bulk.SellLine;
import com.aatlas.bulk.SellLineReader;
import com.aatlas.bulk.SupplierEval;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.policy.Persona;
import com.aatlas.policy.PolicyReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ask Aatlas: a question in, a decision out. Port of {@code src/lib/intel/assistant.ts}.
 *
 * <p>Every intent matches the same regexes the TypeScript uses; what differs is where the
 * answer's numbers come from, and that varies intent by intent - see each branch's own
 * comment. In short: <b>raise-prices</b>, <b>demand-by-region</b> and <b>a store</b> are
 * real, computed the same way the screens would; <b>which-supplier</b>,
 * <b>liquidate</b>, <b>hold-vs-sell</b> and <b>what-changed</b> are simplified stand-ins
 * for engines (buy2's procurement planner, sell2's liquidation/hold signals, insights'
 * overview) that live in other tracks' worktrees - each is a small, honest computation
 * over real seeded data, not fabricated, but not the real engine's formula either. See
 * this module's section of the wave-2 report for the full list.
 */
@Service
public class AssistantService {

    private static final String DEMO_ITEM = "HRD118902";
    private static final String DEMO_STORE = "100959";

    private static final Pattern RAISE_PRICES = Pattern.compile(
            "(increase|raise|higher|up)\\b.*price|price.*(increase|raise)|which products");
    private static final Pattern SUPPLIER = Pattern.compile("supplier|order|buy|source|procure");
    private static final Pattern LIQUIDATE = Pattern.compile(
            "liquidat|overstock|too much stock|clear|dead stock|excess");
    private static final Pattern HOLD_VS_SELL = Pattern.compile("hold|wait|sell now|keep");
    private static final Pattern WHAT_CHANGED = Pattern.compile("changed|today|happen|news|yesterday");
    private static final Pattern DEMAND_REGION = Pattern.compile("demand|region|where|growing|market");
    private static final Pattern URGENT = Pattern.compile("urgent|asap|48|today|tomorrow|rush|this week");
    private static final Pattern QUANTITY = Pattern.compile("(\\d{2,7})");

    private final SellLineReader sellLines;
    private final BuyLineReader buyLines;
    private final AssistantCatalog catalog;
    private final PolicyReader personas;
    private final AssistantQuestionRepository history;
    private final AatlasClock clock;

    AssistantService(SellLineReader sellLines, BuyLineReader buyLines, AssistantCatalog catalog,
            PolicyReader personas, AssistantQuestionRepository history, AatlasClock clock) {
        this.sellLines = sellLines;
        this.buyLines = buyLines;
        this.catalog = catalog;
        this.personas = personas;
        this.history = history;
        this.clock = clock;
    }

    public List<String> suggestedQuestions() {
        return List.of(
                "Which products should I increase prices on today?",
                "Which supplier should we use for a 50,000-unit copper order?",
                "What should I liquidate?",
                "Should I hold copper tube at " + catalog.storeCity(DEMO_STORE) + " or sell now?",
                "What changed today?",
                "Where is demand growing?");
    }

    /** Persona-aware reordering: the seat's own side of the business comes first. */
    @Transactional(readOnly = true)
    public List<String> suggestionsFor(UUID tenantId, String role) {
        List<String> all = suggestedQuestions();
        Persona persona = personas.personaFor(tenantId, role);
        return switch (persona.side()) {
            case SELL -> List.of(all.get(0), all.get(2), all.get(3), all.get(4), all.get(5), all.get(1));
            case BUY -> List.of(all.get(1), all.get(4), all.get(5), all.get(0), all.get(2), all.get(3));
            default -> all;
        };
    }

    @Transactional
    public AnswerView ask(UUID tenantId, UUID userId, String question) {
        history.save(new AssistantQuestionEntity(tenantId, userId, question, clock.now()));
        return answer(question);
    }

    @Transactional(readOnly = true)
    public List<HistoryEntryView> recentQuestions(UUID tenantId, UUID userId, int limit) {
        return history.findByTenantIdAndUserIdOrderByAskedAtDesc(tenantId, userId, PageRequest.of(0, limit))
                .stream()
                .map(q -> new HistoryEntryView(q.getQuestion(), q.getAskedAt()))
                .toList();
    }

    // ---- the router --------------------------------------------------------------------------

    private AnswerView answer(String question) {
        String q = question.toLowerCase(Locale.ROOT).strip();

        if (RAISE_PRICES.matcher(q).find()) {
            return raisePrices();
        }
        if (SUPPLIER.matcher(q).find()) {
            return whichSupplier(q);
        }
        if (LIQUIDATE.matcher(q).find()) {
            return liquidate();
        }
        if (HOLD_VS_SELL.matcher(q).find()) {
            return holdVsSell(q);
        }
        if (WHAT_CHANGED.matcher(q).find()) {
            return whatChanged();
        }
        if (DEMAND_REGION.matcher(q).find()) {
            return demandByRegion();
        }
        String store = findStore(q);
        if (store != null) {
            return new AnswerView(catalog.storeLabel(store), null, List.of(),
                    "Branch health, opportunities and every product at this branch.",
                    new CtaView("Open the branch", "/app/stores?store=" + store), null);
        }
        return new AnswerView("I can answer these", null,
                suggestedQuestions().stream().map(sq -> new LineView("", sq, null)).toList(),
                "Ask about prices to raise, which supplier to use for an order, what to liquidate, whether to "
                        + "hold stock, what changed, or where demand is growing.",
                new CtaView("Open the overview", "/app"), null);
    }

    /** Real: exactly {@code getSellIntel} across every (sellable item, branch) pair, as the TypeScript does. */
    private AnswerView raisePrices() {
        Set<String> seen = new LinkedHashSet<>();
        double total = 0;
        SellLine best = null;
        for (SeedProduct p : catalog.sellableProducts()) {
            for (SeedStore t : catalog.stores()) {
                SellLine s = sellLines.read(p.itemNumber(), t.storeId());
                if (!s.priceable() || s.monthlyOpportunity() <= 0) {
                    continue;
                }
                seen.add(p.itemNumber());
                total += s.monthlyOpportunity();
                if (best == null || s.monthlyOpportunity() > best.monthlyOpportunity()) {
                    best = s;
                }
            }
        }
        int count = seen.size();
        List<LineView> lines = best == null ? List.of() : List.of(
                new LineView("Highest opportunity", best.name() + " at " + best.storeLabel(), null),
                new LineView("Current", MoneyFormat.money(best.currentPrice()), "muted"),
                new LineView("Recommended", MoneyFormat.money(best.recommended()), "good"),
                new LineView("Confidence", best.confidence() + "%", null));
        CtaView secondary = best == null ? null
                : new CtaView("Price " + best.name(), "/app/sell?item=" + best.itemNumber() + "&store=" + best.storeId());
        return new AnswerView(count + " products identified", "+" + MoneyFormat.compact(total) + " a month", lines,
                "Potential margin opportunity of " + MoneyFormat.compact(total) + " a month across " + count
                        + " products priced under their recommendation.",
                new CtaView("Review recommendations", "/app/sell/bulk?preset=raise"), secondary);
    }

    /**
     * Simplified stand-in for {@code getBuyIntel} + {@code procurementPlan}
     * (src/lib/intel/buy2.ts, not ported - see class doc). Picks a supplier from
     * {@link BuyLineReader}'s real panel by the question's priority word rather than
     * running the real multi-factor procurement planner.
     */
    private AnswerView whichSupplier(String q) {
        String item = orDefault(findProduct(q), DEMO_ITEM);
        String region = orDefault(findRegion(q), "south");
        Integer qtyFound = findQty(q);
        int qty = qtyFound == null ? 10000 : qtyFound;
        boolean urgent = URGENT.matcher(q).find();

        BuyLine intel = buyLines.read(item, region, qty);
        String priority = q.contains("cheap") || q.contains("lowest cost") || q.contains("cost") ? "cost"
                : q.contains("fast") || q.contains("quick") || q.contains("speed") ? "speed"
                : q.contains("reliab") ? "reliability" : "balanced";

        SupplierEval rec = switch (priority) {
            case "cost" -> intel.suppliers().stream().min(Comparator.comparingDouble(SupplierEval::landed))
                    .orElse(intel.recommendedSupplier());
            case "speed" -> intel.suppliers().stream().filter(s -> s.otifPct() >= 80)
                    .min(Comparator.comparingInt(SupplierEval::leadDays)).orElse(intel.recommendedSupplier());
            case "reliability" -> intel.suppliers().stream()
                    .max(Comparator.comparingDouble(SupplierEval::otifPct)).orElse(intel.recommendedSupplier());
            default -> intel.recommendedSupplier();
        };

        double totalCost = round2(rec.landed() * qty);
        double currentTotal = round2(intel.currentCost() * qty);
        double vsCurrent = round2(totalCost - currentTotal);
        String reason = rec.supplierId().equals(intel.cheapestQuoted().supplierId())
                ? rec.name() + " is both the lowest landed cost and the lowest all-in cost."
                : rec.name() + " lands at " + MoneyFormat.money(rec.landed()) + " all-in once reliability and "
                        + "lead time are priced in.";

        return new AnswerView("Recommended: " + rec.name(),
                vsCurrent < 0 ? MoneyFormat.compact(-vsCurrent) + " saved" : MoneyFormat.compact(vsCurrent) + " more than today",
                List.of(
                        new LineView("Cost", MoneyFormat.money(rec.landed()) + " a unit · "
                                + MoneyFormat.money(totalCost, 0) + " total", null),
                        new LineView("Reliability", Math.round(rec.otifPct()) + "% on-time record",
                                rec.otifPct() >= 90 ? "good" : rec.otifPct() < 82 ? "bad" : null),
                        new LineView("Lead time", rec.leadDays() + " days", null),
                        new LineView("Risk", rec.risk(), "Low".equals(rec.risk()) ? "good"
                                : "High".equals(rec.risk()) ? "bad" : null)),
                intel.name() + ", " + qty + " units into the " + intel.regionLabel() + ", "
                        + (urgent ? "needed as soon as possible" : "within the buying window") + ". " + reason,
                new CtaView("View procurement strategy",
                        "/app/buy?item=" + item + "&region=" + region + "&qty=" + qty),
                new CtaView("Buy in bulk", "/app/buy/bulk?region=" + region));
    }

    /**
     * Simplified stand-in for {@code liquidationSignal} (src/lib/intel/sell2.ts, not
     * ported). Flags a line when it is overstocked (16+ weeks of cover) and demand is not
     * high, which is the same shape of signal the real function uses; the erosion estimate
     * (12% of inventory value) is a stated flat assumption, not the real function's
     * elasticity-based projection.
     */
    private AnswerView liquidate() {
        record Hit(String name, String store, double erosion, String item, String storeId, double units,
                double valueNow) {
        }
        List<Hit> hits = new ArrayList<>();
        for (SeedProduct p : catalog.sellableProducts()) {
            for (SeedStore t : catalog.stores()) {
                SellLine s = sellLines.read(p.itemNumber(), t.storeId());
                if (!s.priceable() || s.weeksOfCover() <= 16 || "high".equals(s.demandLevel())) {
                    continue;
                }
                double erosion = round2(s.inventoryValue() * 0.12);
                hits.add(new Hit(s.name(), s.storeLabel(), erosion, s.itemNumber(), s.storeId(), s.inventoryUnits(),
                        s.inventoryValue()));
            }
        }
        hits.sort((a, b) -> Double.compare(b.erosion(), a.erosion()));
        Hit top = hits.isEmpty() ? null : hits.get(0);
        double totalErosion = hits.stream().mapToDouble(Hit::erosion).sum();

        List<LineView> lines = top == null ? List.of() : List.of(
                new LineView("Largest", top.name() + " at " + top.store(), null),
                new LineView("Stock", Math.round(top.units()) + " units · " + MoneyFormat.compact(top.valueNow()),
                        null),
                new LineView("Value erosion in 60 days", "−" + MoneyFormat.compact(top.erosion()), "bad"));
        return new AnswerView(
                hits.isEmpty() ? "Nothing needs liquidating"
                        : hits.size() + " liquidation " + (hits.size() == 1 ? "opportunity" : "opportunities"),
                hits.isEmpty() ? null : MoneyFormat.compact(totalErosion) + " at risk in 60 days",
                lines,
                hits.isEmpty()
                        ? "No line combines falling demand, a soft market and heavy stock right now."
                        : "Falling demand and heavy stock on these lines. Discounting now beats holding.",
                top != null ? new CtaView("Price to move", "/app/sell/bulk?store=" + top.storeId() + "&preset=overstock")
                        : new CtaView("See products", "/app/products?filter=risk"),
                top != null ? new CtaView("Open " + top.name(), "/app/sell?item=" + top.item() + "&store=" + top.storeId())
                        : null);
    }

    /**
     * Simplified stand-in for {@code holdVsSell} (src/lib/intel/sell2.ts, not ported).
     * Extrapolates the same demand signal {@link SellLine} already carries into a rough
     * 30-day price move rather than the real function's own forecast, and prices a month
     * of holding at a stated 8%-a-year carrying cost.
     */
    private AnswerView holdVsSell(String q) {
        String item = orDefault(findProduct(q), DEMO_ITEM);
        String store = orDefault(findStore(q), DEMO_STORE);
        SellLine s = sellLines.read(item, store);

        double priceNow = s.recommended();
        double appreciationPct = round1(s.demandPct() * 2);
        double priceLater = round2(priceNow * (1 + appreciationPct / 100));
        double holdingCost = round2(priceNow * 0.08 / 12);
        boolean hold = appreciationPct > 0 && (priceLater - priceNow) > holdingCost;
        double net = hold ? round2(priceLater - priceNow - holdingCost) : round2(-holdingCost);
        String uncertainty = "High".equals(s.confidenceLabel()) ? "Low"
                : "Medium".equals(s.confidenceLabel()) ? "Medium" : "High";

        return new AnswerView(
                hold ? "Hold " + s.name() + " 30 days" : "Sell " + s.name() + " now",
                (net >= 0 ? "+" : "−") + MoneyFormat.money(Math.abs(net)) + " a unit net",
                List.of(
                        new LineView("Price now", MoneyFormat.money(priceNow), null),
                        new LineView("Forecast in 30 days", MoneyFormat.money(priceLater),
                                appreciationPct >= 0 ? "good" : "bad"),
                        new LineView("Holding cost", "−" + MoneyFormat.money(holdingCost), "bad"),
                        new LineView("Demand uncertainty", uncertainty, "Low".equals(uncertainty) ? "good" : "bad")),
                s.storeLabel() + ". " + (hold
                        ? "Rising demand points to a higher price within the month."
                        : "Nothing points to a meaningfully higher price soon; take the margin now."),
                new CtaView("Open the decision", "/app/sell?item=" + item + "&store=" + store + "&panel=timing"),
                null);
    }

    /**
     * Simplified stand-in for {@code getOverview} (src/lib/intel/overview.ts, the
     * insights module's engine - not ported). Aggregates the same demand signal every
     * other intent reads across the flagship basket rather than reading a real
     * change-event ledger, which does not exist yet (see the wave-2 report).
     */
    private AnswerView whatChanged() {
        int up = 0;
        int down = 0;
        double opportunityTotal = 0;
        for (SeedProduct p : catalog.sellableProducts()) {
            for (SeedStore t : catalog.stores()) {
                SellLine s = sellLines.read(p.itemNumber(), t.storeId());
                if (!s.priceable()) {
                    continue;
                }
                if ("high".equals(s.demandLevel())) {
                    up++;
                } else if ("low".equals(s.demandLevel())) {
                    down++;
                }
                if (s.monthlyOpportunity() > 0) {
                    opportunityTotal += s.monthlyOpportunity();
                }
            }
        }
        return new AnswerView("Since yesterday", MoneyFormat.compact(opportunityTotal) + " a month to go after",
                List.of(
                        new LineView("Demand", up + " products trending up", "good"),
                        new LineView("Demand", down + " products trending down", down > 0 ? "bad" : "muted")),
                up + " products show rising demand and " + down + " show falling demand across the catalogue "
                        + "right now.",
                new CtaView("Open the overview", "/app"), null);
    }

    /**
     * Real, but a simplified aggregate: averages {@link SellLine#demandPct()} - the same
     * per-item demand signal {@code geo.ts}'s {@code allRegions} itself reads - across
     * every store in a region, rather than reproducing that file's own region-level
     * scaling formula.
     */
    private AnswerView demandByRegion() {
        record RegionDemand(String key, String label, double avgPct, String topItem) {
        }
        List<RegionDemand> regions = new ArrayList<>();
        for (MarketRegion r : AssistantCatalog.MARKET_REGIONS) {
            List<SeedStore> inRegion = catalog.stores().stream()
                    .filter(t -> r.key().equals(t.regionKey())).toList();
            double sum = 0;
            int n = 0;
            String bestItem = null;
            double bestPct = Double.NEGATIVE_INFINITY;
            for (SeedStore t : inRegion) {
                for (SeedProduct p : catalog.sellableProducts()) {
                    SellLine s = sellLines.read(p.itemNumber(), t.storeId());
                    if (!s.priceable()) {
                        continue;
                    }
                    sum += s.demandPct();
                    n++;
                    if (s.demandPct() > bestPct) {
                        bestPct = s.demandPct();
                        bestItem = s.name();
                    }
                }
            }
            regions.add(new RegionDemand(r.key(), r.label(), n == 0 ? 0 : round1(sum / n), bestItem));
        }
        regions.sort((a, b) -> Double.compare(b.avgPct(), a.avgPct()));
        RegionDemand top = regions.get(0);

        List<LineView> lines = regions.stream()
                .map(r -> new LineView(r.label(),
                        (r.avgPct() >= 0 ? "+" : "") + r.avgPct() + "% · " + (r.topItem() == null ? "—" : r.topItem()),
                        r.avgPct() >= 2 ? "good" : r.avgPct() < 0 ? "bad" : null))
                .toList();
        return new AnswerView("Demand is growing fastest in the " + top.label(),
                (top.avgPct() >= 0 ? "+" : "") + top.avgPct() + "%", lines,
                (top.topItem() == null ? "Demand" : top.topItem()) + " is the fastest-growing line in the "
                        + top.label() + ".",
                new CtaView("See the region", "/app/insights?region=" + top.key()),
                new CtaView("Reprice the region", "/app/sell/bulk?region=" + top.key()));
    }

    // ---- intent-matching helpers, ported from assistant.ts's find* functions -----------------

    private String findProduct(String q) {
        for (SeedProduct p : catalog.sellableProducts()) {
            String name = AssistantCatalog.lower(p.shortName());
            List<String> words = List.of(name.replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+")).stream()
                    .filter(w -> w.length() > 3).toList();
            if (!words.isEmpty() && words.stream().allMatch(q::contains)) {
                return p.itemNumber();
            }
            if (!words.isEmpty() && q.contains(words.get(0)) && (words.size() < 2 || q.contains(words.get(1)))) {
                return p.itemNumber();
            }
        }
        return q.contains("copper") ? DEMO_ITEM : null;
    }

    private String findStore(String q) {
        for (SeedStore t : catalog.stores()) {
            String source = t.msaName() != null ? t.msaName() : t.legalName();
            String city = source == null ? "" : AssistantCatalog.lower(source.split("-")[0]).trim();
            if (!city.isEmpty() && q.contains(city)) {
                return t.storeId();
            }
        }
        return null;
    }

    private String findRegion(String q) {
        for (MarketRegion r : AssistantCatalog.MARKET_REGIONS) {
            List<String> words = List.of(AssistantCatalog.lower(r.label()).split("[^a-z]+")).stream()
                    .filter(w -> w.length() > 3).toList();
            if (words.stream().anyMatch(q::contains)) {
                return r.key();
            }
        }
        return null;
    }

    private static Integer findQty(String q) {
        java.util.regex.Matcher m = QUANTITY.matcher(q.replace(",", ""));
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static double round2(double n) {
        return Math.round(n * 100) / 100.0;
    }

    private static double round1(double n) {
        return Math.round(n * 10) / 10.0;
    }
}
