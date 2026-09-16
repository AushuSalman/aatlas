package com.aatlas.buy.internal;

import com.aatlas.buy.BuyIntel;
import com.aatlas.buy.BuyWhatIfResult;
import com.aatlas.buy.DecisionScore;
import com.aatlas.buy.Delivery;
import com.aatlas.buy.ProcurementOption;
import com.aatlas.buy.ProcurementPlan;
import com.aatlas.buy.ProcurementPlanReader;
import com.aatlas.buy.Route;
import com.aatlas.buy.ScoredSupplier;
import com.aatlas.buy.SupplierEval;
import com.aatlas.buy.SupplierRisk;
import com.aatlas.buy.Tradeoff;
import com.aatlas.buy.Weights;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.seed.Seeded;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The best procurement decision, not the lowest price: a port of {@code intel/buy2.ts}
 * (urgency, weights, delivery odds, routes, scoring, the plan itself, and the what-if
 * scenarios).
 *
 * <p>Every supplier is scored on cost, speed, reliability, risk and relationship; what the
 * buyer says matters most, and how urgent the order is, set the weights.
 */
@Component
public class ProcurementEngine implements ProcurementPlanReader {

    private final SupplierGateway supplierGateway;
    private final CatalogGateway catalog;

    ProcurementEngine(SupplierGateway supplierGateway, CatalogGateway catalog) {
        this.supplierGateway = supplierGateway;
        this.catalog = catalog;
    }

    // -- Urgency ------------------------------------------------------------------------------

    public static int urgencyDays(String urgency) {
        return switch (urgency) {
            case "urgent" -> 2;
            case "flexible" -> 30;
            default -> 7; // "standard", and the frontend's own fallback for anything else
        };
    }

    public static String urgencyFromDays(int days) {
        return days <= 3 ? "urgent" : days <= 10 ? "standard" : "flexible";
    }

    // -- What matters most ----------------------------------------------------------------------

    private static final Map<String, Weights> PRIORITY_WEIGHTS = Map.of(
            "cost", new Weights(0.6, 0.1, 0.15, 0.1, 0.05),
            "speed", new Weights(0.1, 0.55, 0.2, 0.1, 0.05),
            "reliability", new Weights(0.1, 0.15, 0.5, 0.2, 0.05),
            "balanced", new Weights(0.3, 0.25, 0.25, 0.15, 0.05),
            "risk", new Weights(0.1, 0.15, 0.3, 0.4, 0.05),
            "longterm", new Weights(0.2, 0.1, 0.25, 0.15, 0.3));

    private static Weights normalise(Weights w) {
        double sum = w.cost() + w.speed() + w.reliability() + w.risk() + w.relationship();
        if (sum == 0) {
            sum = 1;
        }
        return new Weights(w.cost() / sum, w.speed() / sum, w.reliability() / sum, w.risk() / sum,
                w.relationship() / sum);
    }

    /** The weights in force: the priority's, tilted toward speed and reliability when the order is urgent. */
    public static Weights effectiveWeights(String priority, String urgency, Weights custom) {
        if ("custom".equals(priority) && custom != null) {
            return normalise(custom);
        }
        Weights base = PRIORITY_WEIGHTS.get("custom".equals(priority) ? "balanced" : priority);
        if (base == null) {
            throw ApiException.badRequest("invalid_priority", "Unknown priority: " + priority);
        }
        if ("balanced".equals(priority) && "urgent".equals(urgency)) {
            return new Weights(0.15, 0.4, 0.3, 0.1, 0.05);
        }
        if ("balanced".equals(priority) && "flexible".equals(urgency)) {
            return new Weights(0.45, 0.1, 0.25, 0.15, 0.05);
        }
        return base;
    }

    // -- Delivery odds ----------------------------------------------------------------------------

    private static double logistic(double z) {
        return 1 / (1 + Math.exp(-z));
    }

    /** The odds of arriving by {@code requiredDays} on a supplier's own (standard) lead time. */
    public static Delivery deliveryFor(SupplierEval s, SupplierRisk risk, int requiredDays) {
        int expected = s.leadDays();
        double v = Math.max(0.5, risk.leadVarianceDays());
        int rangeLow = (int) Math.max(1, Math.round(expected - v * 0.5));
        int rangeHigh = (int) Math.round(expected + v * 1.6);
        double z = (requiredDays - expected) / v;
        double onTime = Js.clamp(logistic(z * 1.3) * (0.55 + s.otifPct() / 220.0) * 100, 2, 99);
        return new Delivery(expected, rangeLow, rangeHigh, requiredDays, requiredDays - expected, Js.round1(onTime),
                Js.round2(1 - onTime / 100));
    }

    private static Delivery deliveryForRoute(SupplierEval s, Route route, int requiredDays) {
        double v = Math.max(0.5, route.varianceDays());
        int rangeLow = (int) Math.max(1, Math.round(route.days() - v * 0.5));
        int rangeHigh = (int) Math.round(route.days() + v * 1.6);
        double z = (requiredDays - route.days() + 1) / v;
        double onTime = Js.clamp(logistic(z * 1.3) * (0.55 + s.otifPct() / 220.0) * 100, 2, 99);
        return new Delivery(route.days(), rangeLow, rangeHigh, requiredDays, requiredDays - route.days(),
                Js.round1(onTime), Js.round2(1 - onTime / 100));
    }

    private static String dayWord(int n) {
        return n + " " + (n == 1 ? "day" : "days");
    }

    /**
     * How a supplier can get the goods here. Standard is the quoted lead time. Expedited is
     * shipping from stock (domestic), a truck rush (Mexico) or air freight (ocean origins), at
     * a surcharge - and not offered by suppliers short on capacity.
     */
    public static List<Route> routesFor(SupplierEval s, SupplierRisk risk) {
        String seed = "route:" + s.supplierId();
        Route standard = new Route("standard", "Standard, " + dayWord(s.leadDays()), s.leadDays(),
                risk.leadVarianceDays(), s.landed(), 0);
        boolean stocking = Seeded.rand(seed, "stkD") > 0.42 && !"Low".equals(risk.capacity());
        if (!stocking) {
            return List.of(standard);
        }
        int days;
        double surcharge;
        String label;
        if ("USA".equals(s.country())) {
            days = (int) Math.round(Seeded.randRange(seed, "xd1", 1, 3));
            surcharge = Js.round1(Seeded.randRange(seed, "xs", 8, 15));
            label = "From stock, " + dayWord(days);
        } else if ("Mexico".equals(s.country())) {
            days = (int) Math.round(Seeded.randRange(seed, "xd", 4, 7));
            surcharge = Js.round1(Seeded.randRange(seed, "xs", 12, 18));
            label = "Truck rush, " + dayWord(days);
        } else {
            days = (int) Math.round(Seeded.randRange(seed, "xd", 6, 10));
            surcharge = Js.round1(Seeded.randRange(seed, "xs", 22, 32));
            label = "Air freight, " + dayWord(days);
        }
        Route expedited = new Route("expedited", label, days, Math.max(0.5, Js.round1(days * 0.18)),
                Js.round2(s.landed() * (1 + surcharge / 100)), surcharge);
        return List.of(standard, expedited);
    }

    /** What a late unit costs on this order: a lot when the customer is waiting, little when nobody is. */
    public static double lateCostFactor(int requiredDays) {
        return requiredDays <= 3 ? 0.14 : requiredDays <= 10 ? 0.07 : 0.025;
    }

    private record RouteEval(Route route, Delivery delivery, double lateCostPerUnit, double recoveryPerUnit,
            double situationalCost) {
    }

    private static RouteEval evaluateRoute(SupplierEval s, Route route, int requiredDays) {
        Delivery delivery = deliveryForRoute(s, route, requiredDays);
        double lateCostPerUnit = Js.round2(route.unitCost() * lateCostFactor(requiredDays));
        double recoveryPerUnit = Js.round2(Math.min(s.penaltyRecoveryPerUnit(), lateCostPerUnit));
        double situationalCost = Js.round2(s.effective() + (route.unitCost() - s.landed())
                + delivery.lateProbability() * (lateCostPerUnit - recoveryPerUnit));
        return new RouteEval(route, delivery, lateCostPerUnit, recoveryPerUnit, situationalCost);
    }

    private record RowCalc(SupplierEval s, SupplierRisk risk, List<Route> routes, Route route, Delivery delivery,
            double situationalCost, double lateCostPerUnit, double recoveryPerUnit) {
    }

    public List<ScoredSupplier> scoreSuppliers(BuyIntel intel, int requiredDays, Weights weights) {
        return scoreSuppliers(intel, requiredDays, weights, null);
    }

    public List<ScoredSupplier> scoreSuppliers(BuyIntel intel, int requiredDays, Weights weights, String forceMode) {
        CatalogGateway.LogisticsRef logisticsRef = catalog.logistics();
        Map<String, SupplierGateway.SupplierRow> byId = new LinkedHashMap<>();
        for (SupplierGateway.SupplierRow row : supplierGateway.panel()) {
            byId.put(row.id(), row);
        }

        List<RowCalc> rows = new ArrayList<>();
        for (SupplierEval s : intel.suppliers()) {
            SupplierRisk risk = RiskEngine.supplierRisk(logisticsRef,
                    new RiskEngine.RiskInput(s.supplierId(), s.leadDays(), s.otifPct(), s.defectPct()),
                    byId.get(s.supplierId()));
            List<Route> routes = routesFor(s, risk);
            List<RouteEval> evaluated = new ArrayList<>();
            for (Route r : routes) {
                if (forceMode == null || forceMode.equals(r.mode()) || routes.size() == 1) {
                    evaluated.add(evaluateRoute(s, r, requiredDays));
                }
            }
            RouteEval comfortable = evaluated.stream().filter(e -> e.delivery().onTimePct() >= 85)
                    .min(Comparator.comparingDouble(RouteEval::situationalCost)).orElse(null);
            RouteEval chosen = comfortable != null ? comfortable
                    : evaluated.stream().min(Comparator.comparingDouble(RouteEval::situationalCost)).orElseThrow();
            rows.add(new RowCalc(s, risk, routes, chosen.route(), chosen.delivery(), chosen.situationalCost(),
                    chosen.lateCostPerUnit(), chosen.recoveryPerUnit()));
        }

        double minCost = rows.stream().mapToDouble(RowCalc::situationalCost).min().orElse(0);
        int minLead = rows.stream().mapToInt(r -> r.route().days()).min().orElse(1);

        List<ScoredSupplier> out = new ArrayList<>();
        for (RowCalc r : rows) {
            SupplierGateway.SupplierRow meta = byId.get(r.s().supplierId());
            double rating = 3.8;
            if (meta != null) {
                rating = RatingEngine.profileFor(logisticsRef, meta.id(), meta.country(), meta.leadTimeDays(),
                        meta.otifPct(), meta.priceIndex(), meta.defectPct()).rating();
            }
            double costSub = Math.round((minCost / r.situationalCost()) * 100);
            double speedSub = Math.round(0.6 * r.delivery().onTimePct() + 0.4 * (minLead / (double) r.route().days()) * 100);
            double reliabilitySub = Math.round(Js.clamp(
                    (r.s().otifPct() * 0.75 + (100 - r.s().defectPct() * 10) * 0.25) * 0.85 + (rating / 5) * 100 * 0.15,
                    0, 100));
            double riskSub = 100 - r.risk().score();
            double relationshipSub = Math.round(
                    Js.clamp(35 + r.s().relationshipYears() * 8 + (r.s().isIncumbent() ? 12 : 0), 0, 100));
            ScoredSupplier.SubScores sub = new ScoredSupplier.SubScores(costSub, speedSub, reliabilitySub, riskSub,
                    relationshipSub);
            int score = (int) Math.round(weights.cost() * sub.cost() + weights.speed() * sub.speed()
                    + weights.reliability() * sub.reliability() + weights.risk() * sub.risk()
                    + weights.relationship() * sub.relationship());
            out.add(new ScoredSupplier(r.s(), r.risk(), r.route(), r.routes(), r.delivery(), r.situationalCost(),
                    r.lateCostPerUnit(), r.recoveryPerUnit(), score, sub, Js.round1(rating),
                    RatingEngine.ratingLabel(rating)));
        }
        return out.stream().sorted(Comparator.comparingInt(ScoredSupplier::score).reversed()).toList();
    }

    // -- Options for this order ------------------------------------------------------------------

    private record Blend(double unitCost, double situationalCost, int deliveryDays, int rangeLow, int rangeHigh,
            double onTimePct, double reliabilityPct, int riskScore, double subCost, double subSpeed,
            double subReliability, double subRisk, double subRelationship) {
    }

    private record Share(ScoredSupplier r, double share) {
    }

    private static Blend blend(List<Share> rows) {
        double unitCost = 0, situationalCost = 0, riskScore = 0;
        double reliabilityPct = 0;
        double subCost = 0, subSpeed = 0, subReliability = 0, subRisk = 0, subRelationship = 0;
        int deliveryDays = 0, rangeLow = Integer.MAX_VALUE, rangeHigh = Integer.MIN_VALUE;
        // Onwards from 101: no onTimePct is ever above 100, so this sentinel is never mistaken
        // for a real value. (java.lang.Double.MAX_VALUE would also work but this package may
        // not depend on java.lang.Double - see ArchitectureRulesTest.noFloatingPointMoney.)
        double minOnTime = 101;
        for (Share x : rows) {
            unitCost += x.r().route().unitCost() * x.share();
            situationalCost += x.r().situationalCost() * x.share();
            deliveryDays = Math.max(deliveryDays, x.r().delivery().expectedDays());
            rangeLow = Math.min(rangeLow, x.r().delivery().rangeLow());
            rangeHigh = Math.max(rangeHigh, x.r().delivery().rangeHigh());
            minOnTime = Math.min(minOnTime, x.r().delivery().onTimePct());
            reliabilityPct += x.r().s().otifPct() * x.share();
            riskScore += x.r().risk().score() * x.share();
            subCost += x.r().sub().cost() * x.share();
            subSpeed += x.r().sub().speed() * x.share();
            subReliability += x.r().sub().reliability() * x.share();
            subRisk += x.r().sub().risk() * x.share();
            subRelationship += x.r().sub().relationship() * x.share();
        }
        return new Blend(Js.round2(unitCost), Js.round2(situationalCost), deliveryDays, rangeLow, rangeHigh,
                Js.round1(minOnTime), Js.round1(reliabilityPct), (int) Math.round(riskScore), subCost, subSpeed,
                subReliability, subRisk, subRelationship);
    }

    private static ProcurementOption make(Weights weights, double qty, double currentTotal, String key, String title,
            List<Share> rows, String bestFor, String cta) {
        Blend b = blend(rows);
        int score = (int) Math.round(weights.cost() * b.subCost() + weights.speed() * b.subSpeed()
                + weights.reliability() * b.subReliability() + weights.risk() * b.subRisk()
                + weights.relationship() * b.subRelationship());
        String risk = b.riskScore() < 30 ? "Low" : b.riskScore() < 60 ? "Medium" : "High";
        List<ProcurementOption.SupplierShare> suppliers = rows.stream()
                .map(x -> new ProcurementOption.SupplierShare(x.r().s().supplierId(), x.r().s().name(),
                        (int) Math.round(x.share() * 100), x.r().route().label()))
                .toList();
        double totalCost = Js.round2(b.unitCost() * qty);
        return new ProcurementOption(key, title, suppliers, b.unitCost(), b.situationalCost(), b.deliveryDays(),
                b.rangeLow(), b.rangeHigh(), b.onTimePct(), b.reliabilityPct(), Js.round1(100 - b.reliabilityPct()),
                totalCost, Js.round2(totalCost - currentTotal), 0, risk, b.riskScore(), bestFor, cta, score, false, "");
    }

    public ProcurementPlan procurementPlan(BuyIntel intel, int requiredDays, String priority, Weights custom) {
        String urgency = urgencyFromDays(requiredDays);
        Weights weights = effectiveWeights(priority, urgency, custom);
        List<ScoredSupplier> ranked = scoreSuppliers(intel, requiredDays, weights);
        double qty = intel.qty();
        double currentTotal = intel.incumbent().landed() * qty;

        ScoredSupplier cheapest = scoreSuppliers(intel, requiredDays, weights, "standard").stream()
                .sorted(Comparator.comparingDouble((ScoredSupplier r) -> r.route().unitCost())
                        .thenComparingDouble(ScoredSupplier::situationalCost))
                .findFirst().orElseThrow();
        ScoredSupplier fastest = scoreSuppliers(intel, requiredDays, weights, "expedited").stream()
                .sorted(Comparator.comparingInt((ScoredSupplier r) -> r.route().days())
                        .thenComparing(Comparator.comparingDouble((ScoredSupplier r) -> r.s().otifPct()).reversed())
                        .thenComparingDouble((ScoredSupplier r) -> r.route().unitCost()))
                .findFirst().orElseThrow();
        List<ScoredSupplier> canMake = ranked.stream().filter(r -> r.delivery().onTimePct() >= 80).toList();
        List<ScoredSupplier> reliableBase = !canMake.isEmpty() ? canMake
                : ranked.stream().sorted(Comparator.comparingDouble((ScoredSupplier r) -> r.delivery().onTimePct()).reversed())
                        .limit(3).toList();
        List<ScoredSupplier> reliablePool = reliableBase.stream()
                .sorted(Comparator.comparingDouble((ScoredSupplier r) -> r.s().otifPct()).reversed()
                        .thenComparingDouble(ScoredSupplier::situationalCost))
                .toList();
        ScoredSupplier mostReliable = reliablePool.stream()
                .filter(r -> !r.s().supplierId().equals(fastest.s().supplierId()) && r.delivery().onTimePct() >= 80)
                .findFirst().orElse(reliablePool.get(0));
        List<ScoredSupplier> balancedTop = scoreSuppliers(intel, requiredDays, PRIORITY_WEIGHTS.get("balanced"))
                .stream().limit(2).toList();

        List<ProcurementOption> options = new ArrayList<>();
        options.add(make(weights, qty, currentTotal, "cost", "Lowest cost", List.of(new Share(cheapest, 1)),
                "Cost-sensitive orders where the delivery date has room.", "Select lowest cost"));
        options.add(make(weights, qty, currentTotal, "speed", "Fastest fulfilment", List.of(new Share(fastest, 1)),
                "Urgent customer orders and SLA-bound fulfilment.", "Select fastest"));
        options.add(make(weights, qty, currentTotal, "reliability", "Highest reliability",
                List.of(new Share(mostReliable, 1)), "Enterprise customers and high-SLA orders.",
                "Select most reliable"));
        boolean splitBalanced = balancedTop.size() > 1
                && !balancedTop.get(0).s().supplierId().equals(balancedTop.get(1).s().supplierId());
        options.add(make(weights, qty, currentTotal, "balanced", splitBalanced ? "Balanced split" : "Balanced",
                splitBalanced
                        ? List.of(new Share(balancedTop.get(0), 0.6), new Share(balancedTop.get(1), 0.4))
                        : List.of(new Share(balancedTop.get(0), 1)),
                "Balancing cost, speed and supply risk on one order.", "Select balanced"));

        double minTotal = options.stream().mapToDouble(ProcurementOption::totalCost).min().orElse(0);
        for (int i = 0; i < options.size(); i++) {
            ProcurementOption o = options.get(i);
            options.set(i, withVsCheapest(o, Js.round2(o.totalCost() - minTotal)));
        }

        ProcurementOption best = options.stream().max(Comparator.comparingInt(ProcurementOption::score)).orElseThrow();
        ProcurementOption cheapestOpt = options.get(0);
        String why = switch (best.key()) {
            case "cost" -> best.suppliers().get(0).name() + " is the lowest all-in cost and still arrives by day "
                    + requiredDays + " " + Js.toFixed(best.onTimePct(), 0) + "% of the time.";
            case "speed" -> best.suppliers().get(0).name() + " (" + best.suppliers().get(0).route().toLowerCase()
                    + ") costs " + Js.fmtCompact(Math.abs(best.vsCheapest())) + " more than the cheapest option, but "
                    + "with " + requiredDays + " days to deliver, missing the date would cost more than that.";
            case "reliability" -> best.suppliers().get(0).name() + " misses only "
                    + Js.toFixed(best.missedPct(), 1) + "% of dates; on this order that certainty is worth the "
                    + Js.fmtCompact(Math.abs(best.vsCheapest())) + " over the cheapest option.";
            default -> "Splitting "
                    + best.suppliers().stream().map(s -> s.name() + " " + s.sharePct() + "%")
                            .reduce((a, b) -> a + " and " + b).orElse("")
                    + " keeps cost within " + Js.fmtCompact(Math.abs(best.vsCheapest()))
                    + " of the cheapest option while lifting on-time delivery to " + Js.toFixed(best.onTimePct(), 0)
                    + "% and removing single-supplier dependency.";
        };
        best = withRecommendedAndReason(best, true, why);
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).key().equals(best.key())) {
                options.set(i, best);
            }
        }

        ProcurementPlan.NotInAHurry notInAHurry = null;
        if (requiredDays >= 21) {
            List<ScoredSupplier> standardOnly = scoreSuppliers(intel, requiredDays, weights, "standard");
            ScoredSupplier calm = standardOnly.stream()
                    .filter(r -> r.s().otifPct() >= 88 && r.delivery().onTimePct() >= 85)
                    .min(Comparator.comparingDouble(ScoredSupplier::situationalCost)).orElse(null);
            if (calm != null) {
                double savings = Js.round2((best.unitCost() - calm.route().unitCost()) * qty);
                String text = savings > 0
                        ? "Delivery is slower at " + dayWord(calm.delivery().expectedDays())
                                + ", but your required date has " + calm.delivery().bufferDays()
                                + " days of buffer. " + calm.s().name() + " on its standard lead time is the lowest "
                                + "effective cost without creating fulfilment risk: " + Js.fmtCompact(savings)
                                + " less than paying for speed."
                        : "Your date has " + calm.delivery().bufferDays() + " days of buffer, but the slower "
                                + "standard routes do not save money here: " + best.suppliers().get(0).name()
                                + " is already the lowest all-in cost.";
                notInAHurry = new ProcurementPlan.NotInAHurry(calm.s().name(), calm.route().unitCost(),
                        calm.delivery().expectedDays(), calm.s().otifPct(), savings, calm.delivery().bufferDays(),
                        text);
            }
        }

        ProcurementOption fastestOpt = options.get(1);
        ProcurementOption balancedOpt = options.get(3);
        List<Tradeoff> tradeoffs = List.of(
                new Tradeoff("cost", "Lower cost",
                        List.of(Js.fmtCompact(Math.max(0, best.totalCost() - cheapestOpt.totalCost()))
                                + " saved against the recommendation"),
                        List.of(dayWord(Math.max(0, cheapestOpt.deliveryDays() - fastestOpt.deliveryDays()))
                                + " slower than the fastest option",
                                Js.toFixed(cheapestOpt.onTimePct(), 0) + "% chance of arriving on time")),
                new Tradeoff("speed", "Faster",
                        List.of(Js.toFixed(fastestOpt.onTimePct(), 0) + "% chance of arriving on time",
                                dayWord(fastestOpt.deliveryDays()) + " door to door"),
                        List.of(Js.fmtCompact(Math.max(0, fastestOpt.totalCost() - cheapestOpt.totalCost()))
                                + " more than the cheapest option")),
                new Tradeoff("balanced", "Balanced",
                        List.of(Js.toFixed(balancedOpt.onTimePct(), 0) + "% on time",
                                Js.fmtCompact(Math.max(0, currentTotal - balancedOpt.totalCost()))
                                        + " saved against today"),
                        balancedOpt.suppliers().size() > 1
                                ? List.of("Two suppliers to manage on one order")
                                : List.of(Js.fmtCompact(Math.max(0, balancedOpt.totalCost() - cheapestOpt.totalCost()))
                                        + " more than the cheapest option")));

        double bestSupplierCostSub = 0;
        for (ScoredSupplier r : ranked) {
            if (r.s().supplierId().equals(best.suppliers().get(0).id())) {
                bestSupplierCostSub = r.sub().cost();
                break;
            }
        }
        DecisionScore decisionScore = new DecisionScore((int) best.score(), List.of(
                new DecisionScore.Part("Cost", (int) Math.round(bestSupplierCostSub)),
                new DecisionScore.Part("Speed", (int) Math.round(best.onTimePct())),
                new DecisionScore.Part("Reliability", (int) Math.round(best.reliabilityPct())),
                new DecisionScore.Part("Risk", 100 - best.riskScore())),
                best.risk());

        java.util.Set<String> recIds = best.suppliers().stream().map(ProcurementOption.SupplierShare::id)
                .collect(java.util.stream.Collectors.toSet());
        List<ProcurementPlan.MatrixRow> matrix = ranked.stream()
                .map(r -> new ProcurementPlan.MatrixRow(r.s().supplierId(), r.s().name(), r.route().days(),
                        r.situationalCost(), r.risk().level(), recIds.contains(r.s().supplierId())))
                .toList();

        return new ProcurementPlan(requiredDays, urgency, priority, weights, ranked, options, best, why,
                notInAHurry, tradeoffs, decisionScore, matrix);
    }

    private static ProcurementOption withVsCheapest(ProcurementOption o, double vsCheapest) {
        return new ProcurementOption(o.key(), o.title(), o.suppliers(), o.unitCost(), o.situationalCost(),
                o.deliveryDays(), o.rangeLow(), o.rangeHigh(), o.onTimePct(), o.reliabilityPct(), o.missedPct(),
                o.totalCost(), o.vsCurrent(), vsCheapest, o.risk(), o.riskScore(), o.bestFor(), o.cta(), o.score(),
                o.recommended(), o.reason());
    }

    private static ProcurementOption withRecommendedAndReason(ProcurementOption o, boolean recommended, String reason) {
        return new ProcurementOption(o.key(), o.title(), o.suppliers(), o.unitCost(), o.situationalCost(),
                o.deliveryDays(), o.rangeLow(), o.rangeHigh(), o.onTimePct(), o.reliabilityPct(), o.missedPct(),
                o.totalCost(), o.vsCurrent(), o.vsCheapest(), o.risk(), o.riskScore(), o.bestFor(), o.cta(),
                o.score(), recommended, reason);
    }

    // -- What if ----------------------------------------------------------------------------------

    public BuyWhatIfResult buyWhatIf(BuyIntel intel, ProcurementPlan plan, String scenario) {
        ProcurementOption rec = plan.recommended();
        double qty = intel.qty();
        switch (scenario) {
            case "supplier-up-3": {
                double newUnit = Js.round2(rec.unitCost() * 1.03);
                double newTotal = Js.round2(newUnit * qty);
                ProcurementOption next = plan.options().stream().filter(o -> !o.key().equals(rec.key()))
                        .max(Comparator.comparingInt(ProcurementOption::score)).orElseThrow();
                boolean stillBest = newTotal <= next.totalCost() * 1.02 || !"cost".equals(plan.priority());
                return new BuyWhatIfResult(rec.suppliers().get(0).name() + " raises its price 3%", List.of(
                        new BuyWhatIfResult.Row("New unit cost", Js.fmtMoney(newUnit), "bad"),
                        new BuyWhatIfResult.Row("Order total", Js.fmtMoney(newTotal, 0), "bad"),
                        new BuyWhatIfResult.Row("Extra cost", "+" + Js.fmtMoney(newTotal - rec.totalCost(), 0), "bad"),
                        new BuyWhatIfResult.Row("Still the recommendation?",
                                stillBest ? "Yes" : "No, " + next.title().toLowerCase() + " wins",
                                stillBest ? "good" : "bad")),
                        stillBest
                                ? "A 3% rise does not change the ranking; the gap to " + next.title().toLowerCase()
                                        + " was wider than that."
                                : "At +3% the " + next.title().toLowerCase() + " option becomes the better decision. "
                                        + "Use the negotiation assistant before accepting the increase.");
            }
            case "delay-7": {
                ScoredSupplier r = plan.ranked().stream()
                        .filter(x -> x.s().supplierId().equals(rec.suppliers().get(0).id())).findFirst()
                        .orElse(plan.ranked().get(0));
                Delivery late = deliveryForRoute(r.s(), r.route(), Math.max(1, plan.requiredDays() - 7));
                double lateCost = Js.round2(late.lateProbability() * r.lateCostPerUnit() * qty);
                return new BuyWhatIfResult("Delivery slips 7 days", List.of(
                        new BuyWhatIfResult.Row("Chance of still making the date", Js.toFixed(late.onTimePct(), 0) + "%",
                                late.onTimePct() >= 80 ? "good" : "bad"),
                        new BuyWhatIfResult.Row("Expected cost of being late", Js.fmtMoney(lateCost, 0), "bad"),
                        new BuyWhatIfResult.Row("Risk-adjusted arrival",
                                (late.rangeLow() + 7) + "–" + (late.rangeHigh() + 7) + " days", "muted"),
                        new BuyWhatIfResult.Row("Fastest alternative",
                                plan.options().get(1).suppliers().get(0).name() + ", " + plan.options().get(1).deliveryDays()
                                        + " days",
                                "muted")),
                        late.onTimePct() >= 80
                                ? "The buffer absorbs a week; no action needed unless it slips further."
                                : "A week's slip puts the date at risk. " + plan.options().get(1).suppliers().get(0).name()
                                        + " is the fallback, at " + Js.fmtMoney(plan.options().get(1).vsCheapest(), 0)
                                        + " more.");
            }
            case "split": {
                ProcurementOption split = plan.options().get(3);
                return new BuyWhatIfResult("Split the order across two suppliers", List.of(
                        new BuyWhatIfResult.Row("Blended unit cost", Js.fmtMoney(split.unitCost()), null),
                        new BuyWhatIfResult.Row("Against the recommendation",
                                (split.totalCost() - rec.totalCost() >= 0 ? "+" : Js.MINUS)
                                        + Js.fmtMoney(Math.abs(split.totalCost() - rec.totalCost()), 0),
                                split.totalCost() <= rec.totalCost() ? "good" : "bad"),
                        new BuyWhatIfResult.Row("On-time probability", Js.toFixed(split.onTimePct(), 0) + "%",
                                split.onTimePct() >= rec.onTimePct() ? "good" : "bad"),
                        new BuyWhatIfResult.Row("Single-supplier dependency",
                                split.suppliers().size() > 1 ? "Removed" : "Unchanged",
                                split.suppliers().size() > 1 ? "good" : "muted")),
                        split.suppliers().size() > 1
                                ? "Splitting reduces dependency on one supplier while keeping cost and reliability "
                                        + "competitive."
                                : "The panel has no second supplier close enough to split with on this order.");
            }
            case "faster": {
                ProcurementOption fast = plan.options().get(1);
                return new BuyWhatIfResult("Pay for the fastest supplier", List.of(
                        new BuyWhatIfResult.Row("Arrives in", fast.deliveryDays() + " days", "good"),
                        new BuyWhatIfResult.Row("On-time probability", Js.toFixed(fast.onTimePct(), 0) + "%", "good"),
                        new BuyWhatIfResult.Row("Extra cost",
                                "+" + Js.fmtMoney(Math.max(0, fast.totalCost() - rec.totalCost()), 0), "bad"),
                        new BuyWhatIfResult.Row("Days gained", String.valueOf(Math.max(0, rec.deliveryDays() - fast.deliveryDays())),
                                null)),
                        fast.key().equals(rec.key())
                                ? "The fastest supplier is already the recommendation."
                                : "Worth it only if the customer is waiting: each day gained costs about "
                                        + Js.fmtMoney(Math.max(0, fast.totalCost() - rec.totalCost())
                                                / Math.max(1, rec.deliveryDays() - fast.deliveryDays()), 0) + ".");
            }
            default:
                throw ApiException.badRequest("invalid_scenario", "Unknown what-if scenario: " + scenario);
        }
    }
}
