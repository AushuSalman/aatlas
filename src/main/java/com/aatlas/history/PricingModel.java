package com.aatlas.history;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The pricing model's parameters: every step the recommendation chains can take, what each
 * defaults to, and a tenant's overrides on top.
 *
 * <p>There are two chains, one per {@link Side}: <b>sell</b> (the price to charge) and
 * <b>buy</b> (the cost to aim for). Both live in this one registry, keyed apart by a
 * {@code buy.} prefix, and a tenant's overrides for both live in one stored map, so a
 * parameter is looked up the same way whichever chain reads it.
 *
 * <p>The registry is the single list. A parameter is either a <b>toggle</b> (a step that can
 * be switched off) or a <b>number</b> (a knob a step reads), and a number may belong to a
 * toggle ({@link Parameter#parent}) so that switching the step off silences its knobs too.
 * Adding a step or a knob is one entry here plus the line of the chain that reads it: the
 * settings screen renders whatever this list says, the store keeps only overrides keyed by
 * {@link Parameter#key}, and an override for a key that no longer exists is dropped on read
 * rather than failing.
 *
 * <p>Labels and descriptions are written for the customer, not the engineer: they are what
 * the settings screen, the derivations and the "How we price" page show. The section of the
 * pricing walkthrough each comes from is kept in {@link Parameter#doc} for the reader who
 * wants the maths.
 *
 * <p>The two guardrails every sell price is bounded by - the minimum-margin floor and the
 * market ceiling - are the tenant's guardrails ({@link Reference.Guardrails}) and are never
 * part of this list: nothing here can switch them off. On the buy side the equivalent is
 * the lowest real price on file: a target never goes under it.
 */
public final class PricingModel {

    public enum Type { TOGGLE, NUMBER }

    /** Which chain a parameter, group or preset belongs to. */
    public enum Side {
        SELL("sell"), BUY("buy");

        private final String key;

        Side(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Side of(String key) {
            if (key == null || key.isBlank()) {
                return SELL;
            }
            for (Side s : values()) {
                if (s.key.equalsIgnoreCase(key.strip())) {
                    return s;
                }
            }
            throw new IllegalArgumentException("Unknown pricing-model side '" + key + "'; expected sell or buy.");
        }
    }

    /** The settings screen's sections, in order, per side. */
    public enum Group {
        MEASURE(Side.SELL, "measure", "What we learn from your sales", "How each item sells, before a price is worked out."),
        COMPOSE(Side.SELL, "compose", "Where the price starts",
                "The starting point, and how the higher-margin price is found."),
        ADJUST(Side.SELL, "adjust", "Small adjustments",
                "Bounded nudges for demand, commodity costs and the local market, and moving to new prices gradually."),
        LEARN(Side.SELL, "learn", "Learning from your decisions", "What your own pricing decisions are allowed to change."),
        GUARD(Side.SELL, "guard", "Safety limits", "Checks every price passes before you see it."),

        BUY_MEASURE(Side.BUY, "buy.measure", "What we learn from your purchases",
                "Who you really buy from and what you really paid, before a target is worked out."),
        BUY_COMPOSE(Side.BUY, "buy.compose", "Where the target starts",
                "What counts as evidence of the going rate, and where between the best price and the market the target sits."),
        BUY_ADJUST(Side.BUY, "buy.adjust", "Comparing suppliers",
                "What besides the quote counts when suppliers are ranked, and when to buy."),
        BUY_LEARN(Side.BUY, "buy.learn", "Learning from your decisions",
                "What the prices you actually agreed are allowed to change."),
        BUY_GUARD(Side.BUY, "buy.guard", "Safety limits and reordering",
                "Checks every target passes, and the reorder advice.");

        private final Side side;
        private final String key;
        private final String label;
        private final String blurb;

        Group(Side side, String key, String label, String blurb) {
            this.side = side;
            this.key = key;
            this.label = label;
            this.blurb = blurb;
        }

        public Side side() {
            return side;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public String blurb() {
            return blurb;
        }

        public static List<Group> of(Side side) {
            List<Group> out = new ArrayList<>();
            for (Group g : values()) {
                if (g.side == side) {
                    out.add(g);
                }
            }
            return out;
        }
    }

    /**
     * One entry of the registry.
     *
     * @param parent the toggle this number belongs to, or null for a top-level parameter
     * @param unit what the number is in ("%", "orders", "days", "× cost", or "" for a bare ratio)
     * @param doc the section of the pricing walkthrough this comes from, for the reader who wants the maths
     */
    public record Parameter(String key, Group group, Type type, String label, String description,
            boolean defaultOn, BigDecimal defaultValue, BigDecimal min, BigDecimal max, String unit,
            String parent, String doc) {

        public boolean toggle() {
            return type == Type.TOGGLE;
        }

        public Side side() {
            return group.side();
        }
    }

    /**
     * A stored override: {@code {"on": false}} for a toggle, {@code {"value": 12}} for a number.
     * A null field means "the default".
     */
    public record Setting(Boolean on, BigDecimal value) {

        public static Setting on(boolean on) {
            return new Setting(on, null);
        }

        public static Setting value(BigDecimal value) {
            return new Setting(null, value);
        }
    }

    /**
     * A one-click set of overrides for people who do not want to tune knobs. Keys repeat per
     * side ({@code balanced} exists for sell and for buy); look one up with its side.
     *
     * @param settings the overrides on top of the defaults; empty for the recommended preset
     */
    public record Preset(Side side, String key, String label, String blurb, Map<String, Setting> settings) {

        public static final String BALANCED = "balanced";
        public static final String CAREFUL = "careful";
        public static final String DIRECT = "direct";
        public static final String CUSTOM = "custom";
    }

    // ---- keys: sell -------------------------------------------------------------------

    public static final String ELASTICITY = "elasticity";
    public static final String ELASTICITY_PRIOR = "elasticity.prior";
    public static final String ELASTICITY_PRIOR_WEIGHT = "elasticity.priorWeight";
    public static final String ELASTICITY_TRAINED_MODEL = "elasticity.trainedModel";
    public static final String SEGMENT = "segment";
    public static final String SEGMENT_REGULAR_MIN_ORDERS = "segment.regularMinOrders";

    public static final String ANCHOR_INTERNAL = "anchor.internal";
    public static final String ANCHOR_EXTERNAL_ADJUST_CAP = "anchor.externalAdjustCap";
    public static final String ANCHOR_EXTERNAL_DIVERGENCE = "anchor.externalDivergence";
    public static final String COMPETITORS = "competitors";
    public static final String COMPETITORS_PLAUSIBILITY = "competitors.plausibility";
    public static final String COMPETITORS_MARKET_GAP = "competitors.marketGap";
    public static final String COMPETITORS_MARKET_GAP_MIN_AGREEING = "competitors.marketGap.minAgreeing";
    public static final String COMPETITORS_MARKET_GAP_AGREEMENT = "competitors.marketGap.agreement";
    public static final String COMPETITORS_MARKET_GAP_THRESHOLD = "competitors.marketGap.threshold";
    public static final String COMPETITORS_MARKET_GAP_UNDERCUT = "competitors.marketGap.undercut";
    public static final String BLEND_OWN_PRICE = "blend.ownPrice";
    public static final String AGGRESSIVE_PROFIT_MAX = "aggressive.profitMax";
    public static final String AGGRESSIVE_RISK_AVERSION = "aggressive.riskAversion";
    public static final String TIER_GAP = "tierGap";
    public static final String TIER_GAP_BASE = "tierGap.base";
    public static final String TIER_GAP_CONTESTED = "tierGap.contested";
    public static final String MARGIN_LEARNED = "margin.learned";
    public static final String MARGIN_PRICE_LEVEL = "margin.priceLevel";
    public static final String MARGIN_ENTRY = "margin.entry";
    public static final String MARGIN_RAMP_DECISIONS = "margin.rampDecisions";
    public static final String MARGIN_FLOOR_LEARNED = "margin.floorLearned";

    public static final String DEMAND = "demand";
    public static final String DEMAND_MAX_MOVE = "demand.maxMove";
    public static final String DEMAND_MAX_AGE_DAYS = "demand.maxAgeDays";
    public static final String DEMAND_TRAINED_MODEL = "demand.trainedModel";
    public static final String STOCK = "stock";
    public static final String STOCK_MAX_MOVE = "stock.maxMove";
    public static final String STOCK_OVERSTOCK_WEEKS = "stock.overstockWeeks";
    public static final String STOCK_LOW_WEEKS = "stock.lowWeeks";
    public static final String COMMODITY = "commodity";
    public static final String COMMODITY_PASS_THROUGH = "commodity.passThrough";
    public static final String LOCAL_MARKET = "localMarket";
    public static final String LOCAL_MARKET_WEIGHT = "localMarket.weight";
    public static final String TRUST_RAMP = "trustRamp";
    public static final String TRUST_RAMP_HALF_POINT = "trustRamp.halfPoint";
    public static final String TRUST_RAMP_LAUNCH = "trustRamp.launch";
    public static final String TRUST_RAMP_WOBBLE = "trustRamp.wobble";

    public static final String LEARNING = "learning";
    public static final String LEARNING_MAX_MOVE = "learning.maxMove";
    public static final String LEARNING_MIN_DECISIONS = "learning.minDecisions";
    public static final String LEARNING_WINDOW_DAYS = "learning.windowDays";
    public static final String LEARNING_HALF_LIFE_DAYS = "learning.halfLifeDays";
    public static final String LEARNING_DEADBAND = "learning.deadband";
    public static final String LEARNING_STRATEGY = "learning.strategy";
    public static final String LEARNING_AUTO_TUNE = "learning.autoTune";

    public static final String CORRIDOR_HISTORY = "corridor.history";
    public static final String CORRIDOR_MIN_OBSERVATIONS = "corridor.minObservations";
    public static final String CEILING_PLAUSIBILITY = "ceiling.plausibility";
    public static final String CEILING_COST_MULTIPLE = "ceiling.costMultiple";
    public static final String MOVE_CAP = "moveCap";
    public static final String MOVE_CAP_MAX_PCT = "moveCap.maxPct";
    public static final String ROUNDING = "rounding";

    // ---- keys: buy --------------------------------------------------------------------

    public static final String BUY_INCUMBENT = "buy.incumbent";
    public static final String BUY_OBSERVED_LANDED = "buy.observedLanded";
    public static final String BUY_OBSERVED_LANDED_MIN_ORDERS = "buy.observedLanded.minOrders";

    public static final String BUY_MARKET = "buy.market";
    public static final String BUY_MARKET_BULK_LOTS = "buy.market.bulkLots";
    public static final String BUY_MARKET_RETAIL_DERIVED = "buy.market.retailDerived";
    public static final String BUY_MARKET_PLAUSIBILITY = "buy.market.plausibility";
    public static final String BUY_MARKET_GAP = "buy.market.marketGap";
    public static final String BUY_MARKET_GAP_MIN_AGREEING = "buy.market.marketGap.minAgreeing";
    public static final String BUY_MARKET_GAP_AGREEMENT = "buy.market.marketGap.agreement";
    public static final String BUY_MARKET_GAP_THRESHOLD = "buy.market.marketGap.threshold";
    public static final String BUY_TARGET = "buy.target";
    public static final String BUY_TARGET_GAP_SHARE = "buy.target.gapShare";
    public static final String BUY_NEVER_ABOVE_CURRENT = "buy.neverAboveCurrent";

    public static final String BUY_TERMS = "buy.terms";
    public static final String BUY_TERMS_COST_OF_CAPITAL = "buy.terms.costOfCapital";
    public static final String BUY_RELIABILITY = "buy.reliability";
    public static final String BUY_RELIABILITY_OTIF_WEIGHT = "buy.reliability.otifWeight";
    public static final String BUY_RELIABILITY_LEAD_PER_DAY = "buy.reliability.leadPerDay";
    public static final String BUY_RELIABILITY_DEFECT_WEIGHT = "buy.reliability.defectWeight";
    public static final String BUY_RELIABILITY_TRAINED_MODEL = "buy.reliability.trainedModel";
    public static final String BUY_MOQ = "buy.moq";
    public static final String BUY_MOQ_PENALTY = "buy.moq.penalty";
    public static final String BUY_COMMODITY = "buy.commodity";
    public static final String BUY_COMMODITY_PASS_THROUGH = "buy.commodity.passThrough";
    public static final String BUY_PHASE_IN = "buy.phaseIn";
    public static final String BUY_PHASE_IN_HALF_POINT = "buy.phaseIn.halfPoint";
    public static final String BUY_PHASE_IN_LAUNCH = "buy.phaseIn.launch";

    public static final String BUY_LEARNING = "buy.learning";
    public static final String BUY_LEARNING_MAX_MOVE = "buy.learning.maxMove";
    public static final String BUY_LEARNING_MIN_DECISIONS = "buy.learning.minDecisions";
    public static final String BUY_LEARNING_WINDOW_DAYS = "buy.learning.windowDays";
    public static final String BUY_LEARNING_HALF_LIFE_DAYS = "buy.learning.halfLifeDays";
    public static final String BUY_LEARNING_DEADBAND = "buy.learning.deadband";
    public static final String BUY_LEARNING_STRATEGY = "buy.learning.strategy";
    public static final String BUY_LEARNING_AUTO_TUNE = "buy.learning.autoTune";

    public static final String BUY_MOVE_CAP = "buy.moveCap";
    public static final String BUY_MOVE_CAP_MAX_PCT = "buy.moveCap.maxPct";
    public static final String BUY_FLAGS = "buy.flags";
    public static final String BUY_FLAGS_BULK_TOLERANCE = "buy.flags.bulkTolerance";
    public static final String BUY_REORDER = "buy.reorder";
    public static final String BUY_REORDER_COVER_WEEKS = "buy.reorder.coverWeeks";
    public static final String BUY_REORDER_SAFETY_SHARE = "buy.reorder.safetyShare";
    public static final String BUY_REORDER_OVERSTOCK_WEEKS = "buy.reorder.overstockWeeks";
    public static final String BUY_REORDER_SOON_DAYS = "buy.reorder.soonDays";
    public static final String BUY_REORDER_DEFAULT_LEAD_DAYS = "buy.reorder.defaultLeadDays";

    private static final List<Parameter> REGISTRY;
    private static final Map<String, Parameter> BY_KEY;
    private static final List<Preset> PRESETS;

    static {
        List<Parameter> r = new ArrayList<>();

        // ================================ SELL ===========================================

        // ---- what we learn from your sales ----------------------------------------------
        toggle(r, ELASTICITY, Group.MEASURE, "Learn how price-sensitive each item is",
                "Uses your sales history to see how much demand drops when a price goes up. Off: every item is "
                        + "treated as moderately sensitive.", true, null, "§3 step 2 and 4");
        number(r, ELASTICITY_PRIOR, Group.MEASURE, "Starting assumption",
                "−1 means a 1% price rise loses about 1% of sales. Used until an item has enough history of its own.",
                "-1.2", "-3", "-0.1", "", ELASTICITY, "§3 step 4");
        number(r, ELASTICITY_PRIOR_WEIGHT, Group.MEASURE, "History needed before the measurement is trusted",
                "Roughly how many months of sales it takes to outweigh the starting assumption.", "8", "0", "50",
                "months", ELASTICITY, "§3 step 4");
        toggle(r, ELASTICITY_TRAINED_MODEL, Group.MEASURE, "Use the trained demand model where it has proven itself",
                "Where the machine-learned demand model beat the baseline on an item's held-out weeks, its price "
                        + "sensitivity replaces the monthly measurement and is blended with the starting assumption "
                        + "the same way. Off: the monthly measurement only.", true, ELASTICITY, "§3 step 4");
        toggle(r, SEGMENT, Group.MEASURE, "Lead with the right price for how often an item sells",
                "Items you sell often lead with the competitive price; items you sell rarely lead with the "
                        + "higher-margin price. Off: always lead with the competitive price.", true, null, "§3 step 3");
        number(r, SEGMENT_REGULAR_MIN_ORDERS, Group.MEASURE, "Orders a year that make an item a frequent seller",
                "Invoice lines at the branch in the last twelve months.", "5", "1", "50", "orders", SEGMENT, "§3 step 3");

        // ---- where the price starts -----------------------------------------------------
        toggle(r, ANCHOR_INTERNAL, Group.COMPOSE, "Start from what your other branches charge",
                "The typical price across your other branches is the starting point, and competitor prices can "
                        + "only nudge it a little. Off: start from competitor prices when there are any, then your "
                        + "branches, then a margin on cost.", true, null, "§3 step 8b");
        number(r, ANCHOR_EXTERNAL_ADJUST_CAP, Group.COMPOSE, "Most a competitor price can move the start",
                "Even a competitor far below you moves the starting point by this much at most.", "10", "0", "30", "%",
                ANCHOR_INTERNAL, "§3 step 8b");
        number(r, ANCHOR_EXTERNAL_DIVERGENCE, Group.COMPOSE, "Ignore competitor prices further away than",
                "A competitor price further than this from your own starting point does not nudge it.", "30",
                "5", "100", "%", ANCHOR_INTERNAL, "§3 step 8b");
        toggle(r, COMPETITORS, Group.COMPOSE, "Use competitor prices",
                "Prices you imported, entered or fetched live count toward the starting point and the ceiling. "
                        + "Off: priced from your own history and the benchmarks only.", true, null, "§3 step 7");
        toggle(r, COMPETITORS_PLAUSIBILITY, Group.COMPOSE, "Leave out competitor prices far from your own",
                "Competitor prices under half or over twice what the item sells for today are left out, and the "
                        + "prices nearer yours are used. Several competitors that agree with each other are never "
                        + "left out for being far away. Off: every competitor price counts.",
                true, COMPETITORS, "§3 step 7");
        toggle(r, COMPETITORS_MARKET_GAP, Group.COMPOSE, "Follow the market when competitors clearly disagree with your price",
                "When several competitor prices agree with each other but sit far from your price, the "
                        + "recommendation follows the market instead of your own history, past the usual phase-in "
                        + "and move limits, and says so. Off: such prices are not used.",
                true, COMPETITORS, "§3 step 7 and 8b");
        number(r, COMPETITORS_MARKET_GAP_MIN_AGREEING, Group.COMPOSE, "Competitors that must agree", "", "2", "1",
                "10", "competitors", COMPETITORS_MARKET_GAP, "§3 step 7");
        number(r, COMPETITORS_MARKET_GAP_AGREEMENT, Group.COMPOSE, "How close their prices must be",
                "The highest of them no more than this above the lowest.", "50", "5", "300", "%",
                COMPETITORS_MARKET_GAP, "§3 step 7");
        number(r, COMPETITORS_MARKET_GAP_THRESHOLD, Group.COMPOSE, "How far from your price counts as a clear disagreement",
                "", "50", "10", "1000", "%", COMPETITORS_MARKET_GAP, "§3 step 8b");
        number(r, COMPETITORS_MARKET_GAP_UNDERCUT, Group.COMPOSE, "Price this much under the competitor median",
                "The competitive price when the market leads.", "3", "0", "20", "%", COMPETITORS_MARKET_GAP,
                "§3 step 8b");
        toggle(r, BLEND_OWN_PRICE, Group.COMPOSE, "Blend in your own last price",
                "When the start comes from competitors or benchmarks, your branch's last selling price is mixed "
                        + "in so prices do not jump. Off: use the market figure alone.", true, null, "single-item step 2");
        toggle(r, AGGRESSIVE_PROFIT_MAX, Group.COMPOSE, "Find the most profitable higher price",
                "Works out the price that earns the most per order from the item's demand curve, playing safe "
                        + "when the sensitivity is uncertain. Off: the higher price is a fixed step above the "
                        + "competitive one.", true, null, "§3 step 8a");
        number(r, AGGRESSIVE_RISK_AVERSION, Group.COMPOSE, "How cautious to be when unsure",
                "0 goes straight for the peak; 1 pulls back by one measure of uncertainty; higher pulls back more.",
                "1", "0", "3", "", AGGRESSIVE_PROFIT_MAX, "§3 step 8a");
        toggle(r, MARGIN_LEARNED, Group.COMPOSE, "Learn the target margin from your sales",
                "The margin to aim for is worked out from what you actually earn: this item's own margin, its "
                        + "category's, and how margin changes with price across your catalogue - not one fixed "
                        + "percentage. Used as the start when there are no competitor or branch prices. A category "
                        + "with no sales yet starts from an industry benchmark. Off: a fixed benchmark margin per "
                        + "category.", true, null, "dynamic margin");
        toggle(r, MARGIN_PRICE_LEVEL, Group.COMPOSE, "Lower margin % on pricier items, higher on cheaper ones",
                "A $100 item rarely carries the same percentage as a $1 one. How much the percentage changes with "
                        + "price is learned from your own items. Off: one percentage per category whatever the price.",
                true, MARGIN_LEARNED, "dynamic margin");
        number(r, MARGIN_ENTRY, Group.COMPOSE, "A new item starts at this share of its usual margin",
                "An item with little history of its own starts a little below its category's margin - 80 starts "
                        + "a 5% category at 4% - and works up as you apply prices for it.", "80", "50", "100", "%",
                MARGIN_LEARNED, "dynamic margin");
        number(r, MARGIN_RAMP_DECISIONS, Group.COMPOSE, "Decisions to reach the full margin", "", "6", "1", "30",
                "decisions", MARGIN_LEARNED, "dynamic margin");
        toggle(r, TIER_GAP, Group.COMPOSE, "Keep the two prices clearly apart",
                "The higher price always sits a set distance above the competitive one, more so for hotly "
                        + "contested items. Off: the two may come out the same.", true, null, "§3 step 8e and 10");
        number(r, TIER_GAP_BASE, Group.COMPOSE, "Minimum gap", "", "3", "0", "15", "%", TIER_GAP, "§3 step 8e");
        number(r, TIER_GAP_CONTESTED, Group.COMPOSE, "Extra gap for a hotly contested item",
                "Added in full when an item has many competitors and price-sensitive buyers.", "15", "0", "40", "%",
                TIER_GAP, "§3 step 8e");

        // ---- small adjustments ----------------------------------------------------------
        toggle(r, DEMAND, Group.ADJUST, "Adjust for how fast it is selling",
                "An item selling faster than usual moves up a little, slower moves down a little. Ignored when the "
                        + "last sale is too old to trust.", true, null, "§3 step 9");
        number(r, DEMAND_MAX_MOVE, Group.ADJUST, "Most this can change a price", "", "3", "0", "10", "%", DEMAND,
                "§3 step 9");
        number(r, DEMAND_MAX_AGE_DAYS, Group.ADJUST, "Ignore when the last sale is older than", "", "45", "7", "365",
                "days", DEMAND, "§3 step 9");
        toggle(r, DEMAND_TRAINED_MODEL, Group.ADJUST, "Use a trained forecast for demand",
                "A trained model's forecast of an item's next four weeks replaces the 90-day pace as the demand "
                        + "signal. Which model forecasts is chosen under Forecasting model, in Settings. The "
                        + "limits above still apply. Off: the 90-day pace.", true, DEMAND, "§3 step 9");
        toggle(r, STOCK, Group.ADJUST, "Adjust for stock on hand",
                "Weeks of stock at the current selling pace: well over-stocked lowers the price a little to move "
                        + "it, nearly out raises it a little. Needs a recent stock count.", true, null,
                "dynamic margin");
        number(r, STOCK_MAX_MOVE, Group.ADJUST, "Most this can change a price", "", "4", "0", "15", "%", STOCK,
                "dynamic margin");
        number(r, STOCK_OVERSTOCK_WEEKS, Group.ADJUST, "Over-stocked beyond", "The full cut applies at twice this.",
                "26", "4", "104", "weeks", STOCK, "dynamic margin");
        number(r, STOCK_LOW_WEEKS, Group.ADJUST, "Running low under", "", "4", "1", "26", "weeks", STOCK,
                "dynamic margin");
        toggle(r, COMMODITY, Group.ADJUST, "Follow commodity costs",
                "Part of a rise or fall in the item's raw material, such as copper or steel, is passed into the price.",
                true, null, "single-item step 4");
        number(r, COMMODITY_PASS_THROUGH, Group.ADJUST, "Share of the commodity move passed on", "", "25", "0", "100",
                "%", COMMODITY, "single-item step 4");
        toggle(r, LOCAL_MARKET, Group.ADJUST, "Adjust for the local market",
                "A pricier, more crowded area gets a slightly lower competitive price; a cheaper area supports a "
                        + "slightly higher one.", true, null, "§3 step 8c");
        number(r, LOCAL_MARKET_WEIGHT, Group.ADJUST, "How strongly the local index counts",
                "100 passes the whole difference through; 55 passes about half.", "55", "0", "100", "%", LOCAL_MARKET,
                "§3 step 8c");
        toggle(r, TRUST_RAMP, Group.ADJUST, "Move to new prices gradually",
                "Prices start near today's and move toward the target as you apply decisions for that item at "
                        + "that branch. Off: the full target is shown at once.", true, null, "§3 step 8d");
        number(r, TRUST_RAMP_HALF_POINT, Group.ADJUST, "Decisions to get halfway to the target", "", "12", "1", "60",
                "decisions", TRUST_RAMP, "§3 step 8d");
        number(r, TRUST_RAMP_LAUNCH, Group.ADJUST, "Starting point for a new item",
                "How far toward the target an item with no decisions yet is priced; the ramp never falls below it.",
                "25", "0", "100", "%", TRUST_RAMP, "§3 step 8d");
        number(r, TRUST_RAMP_WOBBLE, Group.ADJUST, "Variation",
                "A small, repeatable variation so prices do not all step up in lockstep. 0 turns it off.", "15", "0",
                "30", "%", TRUST_RAMP, "§3 step 8d");

        // ---- learning from your decisions ----------------------------------------------
        toggle(r, LEARNING, Group.LEARN, "Learn from the prices you actually apply",
                "If you keep applying prices above or below what was suggested, the next suggestion leans that "
                        + "way, within a small limit.", true, null, "§3 step 9b");
        number(r, LEARNING_MAX_MOVE, Group.LEARN, "Most this can change a price", "", "2", "0", "10", "%", LEARNING,
                "§3 step 9b");
        number(r, LEARNING_MIN_DECISIONS, Group.LEARN, "Decisions needed before it counts", "", "3", "1", "20",
                "decisions", LEARNING, "§3 step 9b");
        number(r, LEARNING_WINDOW_DAYS, Group.LEARN, "Look back over", "", "180", "30", "730", "days", LEARNING,
                "§3 step 9b");
        number(r, LEARNING_HALF_LIFE_DAYS, Group.LEARN, "Older decisions count half as much after", "", "60", "7",
                "365", "days", LEARNING, "§3 step 9b");
        number(r, LEARNING_DEADBAND, Group.LEARN, "Ignore differences smaller than", "", "1", "0", "10", "%",
                LEARNING, "§3 step 9b");
        toggle(r, LEARNING_STRATEGY, Group.LEARN, "Lead with the price you usually choose",
                "If you mostly pick max profit in the bulk basket, the higher price leads; otherwise the "
                        + "competitive one does.", true, LEARNING, "§1");
        toggle(r, LEARNING_AUTO_TUNE, Group.LEARN, "Retune the model from your results",
                "Every night the model re-fits its own settings from what you decided and what happened after "
                        + "- price sensitivity from measured outcomes, how fast prices phase in, how far your "
                        + "decisions may lean - within safe bounds, and tells you what changed. A setting you set "
                        + "yourself always wins. Off: learned settings are still worked out and shown, but not used.",
                true, LEARNING, "§3 step 4 and 9b");

        // ---- safety limits --------------------------------------------------------------
        toggle(r, CORRIDOR_HISTORY, Group.GUARD, "Stay within the prices this item has sold at",
                "The target floor is the lower end of what the item has actually sold for, and its upper end "
                        + "counts toward the ceiling. Trusted less with only a few sales.", true, null, "§3 step 6");
        number(r, CORRIDOR_MIN_OBSERVATIONS, Group.GUARD, "Sales needed to trust this", "", "8", "1", "50", "sales",
                CORRIDOR_HISTORY, "§3 step 6");
        toggle(r, MARGIN_FLOOR_LEARNED, Group.GUARD, "Learn the lowest margin from your sales",
                "The floor no price goes under is the low end of the margins you really sell this item and its "
                        + "category at - never below cost - instead of one fixed minimum for everything. Off: the "
                        + "fixed minimum margin under Pricing guardrails applies.", true, null, "dynamic margin");
        toggle(r, CEILING_PLAUSIBILITY, Group.GUARD, "Never price above a multiple of cost",
                "Whatever competitors or branches charge, the ceiling stops here.", true, null, "§3 step 6b");
        number(r, CEILING_COST_MULTIPLE, Group.GUARD, "Ceiling at most", "", "4", "1.5", "10", "× cost",
                CEILING_PLAUSIBILITY, "§3 step 6b");
        toggle(r, MOVE_CAP, Group.GUARD, "Limit how far a price moves at once",
                "Neither price moves further than this from today's price in one go. The minimum-margin floor "
                        + "and the market ceiling still come first.", true, null, "§3 step 10");
        number(r, MOVE_CAP_MAX_PCT, Group.GUARD, "Most a price moves at once", "", "25", "5", "100", "%", MOVE_CAP,
                "§3 step 10");
        toggle(r, ROUNDING, Group.GUARD, "Round to tidy price points",
                "Whole cents under $10, five cents under $100, fifty cents under $1,000, then whole dollars.", true,
                null, "single-item step 8");

        // ================================= BUY ===========================================

        // ---- what we learn from your purchases ------------------------------------------
        toggle(r, BUY_INCUMBENT, Group.BUY_MEASURE, "Start from the supplier you actually buy from",
                "Your reference supplier is the one with the largest share of your spend on the item in the last "
                        + "twelve months. Off: the cheapest quoted supplier is the reference instead.", true, null,
                "§3 step 1");
        toggle(r, BUY_OBSERVED_LANDED, Group.BUY_MEASURE, "Use what you really paid for landed cost",
                "A supplier's landed cost comes from your received orders when there are enough of them; otherwise "
                        + "it is the quote plus freight and duty for the lane. Off: always estimate from the lane.",
                true, null, "§3 step 1");
        number(r, BUY_OBSERVED_LANDED_MIN_ORDERS, Group.BUY_MEASURE, "Orders needed before real landed cost is trusted",
                "", "3", "1", "20", "orders", BUY_OBSERVED_LANDED, "§3 step 1");

        // ---- where the target starts ----------------------------------------------------
        toggle(r, BUY_MARKET, Group.BUY_COMPOSE, "Use open-market evidence",
                "Bulk-lot prices and a should-cost worked back from shop prices join your suppliers' quotes as "
                        + "evidence of the going rate. Off: only your suppliers' quotes count.", true, null, "§3 step 7");
        toggle(r, BUY_MARKET_BULK_LOTS, Group.BUY_COMPOSE, "Count bulk-lot prices",
                "Per-unit prices from open-market lots checked in the last ninety days.", true, BUY_MARKET, "§3 step 7");
        toggle(r, BUY_MARKET_RETAIL_DERIVED, Group.BUY_COMPOSE, "Work back from shop prices",
                "Competitors' median shop price less your category's target margin: what a seller at that price "
                        + "could afford to pay.", true, BUY_MARKET, "§3 step 7");
        toggle(r, BUY_MARKET_PLAUSIBILITY, Group.BUY_COMPOSE, "Leave out a lone market price far from your suppliers' quotes",
                "A single market price under a third or over three times your suppliers' median is left out. "
                        + "Several market prices that agree with each other are never dropped for being far away.",
                true, BUY_MARKET, "§3 step 7");
        toggle(r, BUY_MARKET_GAP, Group.BUY_COMPOSE, "Follow the market when it clearly disagrees with what you pay",
                "When open-market prices agree with each other but sit far below your suppliers' quotes, the "
                        + "target follows the market instead of leaving those prices out, past the usual "
                        + "phase-in and move limits, and says so. Off: such prices are not used.", true, BUY_MARKET,
                "§3 step 7 and 8b");
        number(r, BUY_MARKET_GAP_MIN_AGREEING, Group.BUY_COMPOSE, "Market prices that must agree", "", "2", "1", "10",
                "prices", BUY_MARKET_GAP, "§3 step 7");
        number(r, BUY_MARKET_GAP_AGREEMENT, Group.BUY_COMPOSE, "How close they must be",
                "The highest of them no more than this above the lowest.", "50", "5", "300", "%", BUY_MARKET_GAP,
                "§3 step 7");
        number(r, BUY_MARKET_GAP_THRESHOLD, Group.BUY_COMPOSE, "How far from your suppliers counts as a clear disagreement",
                "", "50", "10", "1000", "%", BUY_MARKET_GAP, "§3 step 8b");
        toggle(r, BUY_TARGET, Group.BUY_COMPOSE, "Aim between the best price and the market median",
                "The target sits part of the way from the lowest evidence toward the median, so it is achievable "
                        + "rather than the single best listing. Off: the target is the lowest evidence.", true, null,
                "§3 step 8b");
        number(r, BUY_TARGET_GAP_SHARE, Group.BUY_COMPOSE, "How far from the lowest price toward the median",
                "0 targets the best price seen; 100 targets the median.", "35", "0", "100", "%", BUY_TARGET,
                "§3 step 8b");
        toggle(r, BUY_NEVER_ABOVE_CURRENT, Group.BUY_COMPOSE, "Never target more than you pay today",
                "If the evidence sits above what you already pay, the target is what you pay today. Off: the "
                        + "target may rise to the evidence.", true, null, "§3 step 8b");

        // ---- comparing suppliers --------------------------------------------------------
        toggle(r, BUY_TERMS, Group.BUY_ADJUST, "Value payment terms",
                "Credit days, early-payment discounts and late-delivery clauses are priced into each supplier's "
                        + "effective cost. Off: compare landed cost alone.", true, null, "supplier comparison");
        number(r, BUY_TERMS_COST_OF_CAPITAL, Group.BUY_ADJUST, "Cost of capital a year",
                "What a day of credit is worth to you.", "8", "0", "30", "%", BUY_TERMS, "supplier comparison");
        toggle(r, BUY_RELIABILITY, Group.BUY_ADJUST, "Price in reliability, lead time and quality",
                "Late deliveries, long lead times, defects and short shipments add to a supplier's effective "
                        + "cost, so a cheap unreliable quote does not win on price alone. Off: compare landed cost alone.",
                true, null, "supplier comparison");
        number(r, BUY_RELIABILITY_OTIF_WEIGHT, Group.BUY_ADJUST, "Weight on late deliveries",
                "Share of the landed cost charged for a supplier that is late every time; 55 means a supplier "
                        + "on time 90% of the time carries 5.5% extra.", "55", "0", "200", "%", BUY_RELIABILITY,
                "supplier comparison");
        number(r, BUY_RELIABILITY_LEAD_PER_DAY, Group.BUY_ADJUST, "Cost of each day of lead time",
                "Share of the landed cost per day from order to delivery.", "0.06", "0", "1", "% a day",
                BUY_RELIABILITY, "supplier comparison");
        number(r, BUY_RELIABILITY_DEFECT_WEIGHT, Group.BUY_ADJUST, "Weight on defects",
                "Share of the landed cost charged per percent of defective units.", "150", "0", "500", "%",
                BUY_RELIABILITY, "supplier comparison");
        toggle(r, BUY_RELIABILITY_TRAINED_MODEL, Group.BUY_ADJUST,
                "Use the trained delivery model where it has proven itself",
                "Where the machine-learned delivery model beat a supplier's own record on held-out orders, its "
                        + "predicted lead time and chance of a late delivery for this order replace the supplier's "
                        + "averages. The weights above still apply. Off: the supplier's averages.", true,
                BUY_RELIABILITY, "supplier comparison");
        toggle(r, BUY_MOQ, Group.BUY_ADJUST, "Penalise orders under the minimum",
                "An order below a supplier's minimum carries a surcharge in the comparison.", true, null,
                "supplier comparison");
        number(r, BUY_MOQ_PENALTY, Group.BUY_ADJUST, "Surcharge for an order under the minimum", "", "4", "0", "20",
                "%", BUY_MOQ, "supplier comparison");
        toggle(r, BUY_COMMODITY, Group.BUY_ADJUST, "Follow commodity costs",
                "The item's commodity trend decides buy now versus wait: a rising index says buy now, a falling "
                        + "one says wait. Off: always buy now.", true, null, "buy now vs wait");
        number(r, BUY_COMMODITY_PASS_THROUGH, Group.BUY_ADJUST, "Share of the 90-day index move expected within 30 days",
                "", "38", "0", "100", "%", BUY_COMMODITY, "buy now vs wait");
        toggle(r, BUY_PHASE_IN, Group.BUY_ADJUST, "Move the target gradually",
                "The target starts near what you pay today and moves toward the full target as you record "
                        + "purchase decisions for the item at that branch. Off: the full target is shown at once.",
                true, null, "§3 step 8d");
        number(r, BUY_PHASE_IN_HALF_POINT, Group.BUY_ADJUST, "Decisions to get halfway to the target", "", "6", "1",
                "60", "decisions", BUY_PHASE_IN, "§3 step 8d");
        number(r, BUY_PHASE_IN_LAUNCH, Group.BUY_ADJUST, "Starting point for a new item",
                "How far toward the full target an item with no decisions yet is set; the ramp never falls below it.",
                "50", "0", "100", "%", BUY_PHASE_IN, "§3 step 8d");

        // ---- learning from your decisions ----------------------------------------------
        toggle(r, BUY_LEARNING, Group.BUY_LEARN, "Learn from the prices you actually agree",
                "If you keep agreeing prices above or below the target, the next target leans that way, within a "
                        + "small limit.", true, null, "§3 step 9b");
        number(r, BUY_LEARNING_MAX_MOVE, Group.BUY_LEARN, "Most this can change a target", "", "2", "0", "10", "%",
                BUY_LEARNING, "§3 step 9b");
        number(r, BUY_LEARNING_MIN_DECISIONS, Group.BUY_LEARN, "Decisions needed before it counts", "", "3", "1", "20",
                "decisions", BUY_LEARNING, "§3 step 9b");
        number(r, BUY_LEARNING_WINDOW_DAYS, Group.BUY_LEARN, "Look back over", "", "180", "30", "730", "days",
                BUY_LEARNING, "§3 step 9b");
        number(r, BUY_LEARNING_HALF_LIFE_DAYS, Group.BUY_LEARN, "Older decisions count half as much after", "", "60",
                "7", "365", "days", BUY_LEARNING, "§3 step 9b");
        number(r, BUY_LEARNING_DEADBAND, Group.BUY_LEARN, "Ignore differences smaller than", "", "1", "0", "10", "%",
                BUY_LEARNING, "§3 step 9b");
        toggle(r, BUY_LEARNING_STRATEGY, Group.BUY_LEARN, "Follow your usual buying strategy",
                "If you mostly pick one strategy in the bulk buy basket, the plan recommends it.", true,
                BUY_LEARNING, "§1");
        toggle(r, BUY_LEARNING_AUTO_TUNE, Group.BUY_LEARN, "Retune the model from your results",
                "Every night the model re-fits its own settings from the costs you actually agreed - where between "
                        + "the best price and the median the target should sit, how fast it phases in, how far your "
                        + "decisions may lean - within safe bounds, and tells you what changed. A setting you set "
                        + "yourself always wins. Off: learned settings are still worked out and shown, but not used.",
                true, BUY_LEARNING, "§3 step 8b and 9b");

        // ---- safety limits and reordering -----------------------------------------------
        toggle(r, BUY_MOVE_CAP, Group.BUY_GUARD, "Limit how far the target moves at once",
                "The target stays within this of what you pay today in one go. It never goes under the lowest "
                        + "real price on file.", true, null, "§3 step 10");
        number(r, BUY_MOVE_CAP_MAX_PCT, Group.BUY_GUARD, "Most the target moves at once", "", "25", "5", "100", "%",
                BUY_MOVE_CAP, "§3 step 10");
        toggle(r, BUY_FLAGS, Group.BUY_GUARD, "Warn when you pay over the market",
                "Flags when you pay more than the lowest shop price, more than the should-cost, or more than "
                        + "bulk lots go for.", true, null, "market check");
        number(r, BUY_FLAGS_BULK_TOLERANCE, Group.BUY_GUARD, "Tolerance above bulk-lot prices before a warning", "",
                "5", "0", "50", "%", BUY_FLAGS, "market check");
        toggle(r, BUY_REORDER, Group.BUY_GUARD, "Suggest when and how much to reorder",
                "From stock on hand, selling pace and lead time: the reorder point, the quantity and the date.",
                true, null, "reorder advice");
        number(r, BUY_REORDER_COVER_WEEKS, Group.BUY_GUARD, "Weeks of stock an order should cover", "", "8", "1",
                "52", "weeks", BUY_REORDER, "reorder advice");
        number(r, BUY_REORDER_SAFETY_SHARE, Group.BUY_GUARD, "Safety stock, as a share of lead-time demand", "", "50",
                "0", "200", "%", BUY_REORDER, "reorder advice");
        number(r, BUY_REORDER_OVERSTOCK_WEEKS, Group.BUY_GUARD, "Weeks of stock that count as overstocked", "", "26",
                "4", "104", "weeks", BUY_REORDER, "reorder advice");
        number(r, BUY_REORDER_SOON_DAYS, Group.BUY_GUARD, "Days ahead that count as 'order soon'", "", "14", "1", "60",
                "days", BUY_REORDER, "reorder advice");
        number(r, BUY_REORDER_DEFAULT_LEAD_DAYS, Group.BUY_GUARD, "Lead time assumed when none is on file", "", "14",
                "1", "120", "days", BUY_REORDER, "reorder advice");

        REGISTRY = Collections.unmodifiableList(r);
        Map<String, Parameter> byKey = new LinkedHashMap<>();
        for (Parameter p : r) {
            if (byKey.put(p.key(), p) != null) {
                throw new IllegalStateException("Duplicate pricing-model parameter " + p.key());
            }
        }
        for (Parameter p : r) {
            if (p.parent() != null) {
                Parameter parent = byKey.get(p.parent());
                if (parent == null || !parent.toggle()) {
                    throw new IllegalStateException(p.key() + " names a parent that is not a toggle: " + p.parent());
                }
                if (parent.side() != p.side()) {
                    throw new IllegalStateException(p.key() + " names a parent on the other side: " + p.parent());
                }
            }
            if (!p.toggle() && (p.defaultValue() == null || p.min() == null || p.max() == null
                    || p.defaultValue().compareTo(p.min()) < 0 || p.defaultValue().compareTo(p.max()) > 0)) {
                throw new IllegalStateException(p.key() + " has a default outside its range");
            }
            if (p.side() == Side.BUY && !p.key().startsWith("buy.")) {
                throw new IllegalStateException(p.key() + " is a buy parameter without the buy. prefix");
            }
        }
        BY_KEY = Collections.unmodifiableMap(byKey);

        // ---- presets ------------------------------------------------------------------------
        List<Preset> presets = new ArrayList<>();
        presets.add(new Preset(Side.SELL, Preset.BALANCED, "Recommended",
                "Every step on with the standard settings. Right for most businesses.", Map.of()));
        Map<String, Setting> careful = new LinkedHashMap<>();
        careful.put(MOVE_CAP_MAX_PCT, Setting.value(new BigDecimal("10")));
        careful.put(DEMAND_MAX_MOVE, Setting.value(new BigDecimal("2")));
        careful.put(LEARNING_MAX_MOVE, Setting.value(new BigDecimal("1")));
        careful.put(ANCHOR_EXTERNAL_ADJUST_CAP, Setting.value(new BigDecimal("5")));
        careful.put(TRUST_RAMP_LAUNCH, Setting.value(new BigDecimal("15")));
        careful.put(TRUST_RAMP_HALF_POINT, Setting.value(new BigDecimal("20")));
        presets.add(new Preset(Side.SELL, Preset.CAREFUL, "Careful",
                "Prices move in smaller steps and take longer to reach their targets. Good while you are getting "
                        + "used to the recommendations.", Collections.unmodifiableMap(careful)));
        Map<String, Setting> direct = new LinkedHashMap<>();
        direct.put(TRUST_RAMP, Setting.on(false));
        direct.put(LEARNING, Setting.on(false));
        presets.add(new Preset(Side.SELL, Preset.DIRECT, "Straight to target",
                "Shows the full target price at once and does not lean on your past decisions. Good for a price "
                        + "review where you want the model's own answer.", Collections.unmodifiableMap(direct)));

        presets.add(new Preset(Side.BUY, Preset.BALANCED, "Recommended",
                "Every step on with the standard settings. Right for most businesses.", Map.of()));
        Map<String, Setting> buyCareful = new LinkedHashMap<>();
        buyCareful.put(BUY_TARGET_GAP_SHARE, Setting.value(new BigDecimal("50")));
        buyCareful.put(BUY_MOVE_CAP_MAX_PCT, Setting.value(new BigDecimal("10")));
        buyCareful.put(BUY_LEARNING_MAX_MOVE, Setting.value(new BigDecimal("1")));
        buyCareful.put(BUY_PHASE_IN_LAUNCH, Setting.value(new BigDecimal("30")));
        buyCareful.put(BUY_PHASE_IN_HALF_POINT, Setting.value(new BigDecimal("10")));
        presets.add(new Preset(Side.BUY, Preset.CAREFUL, "Careful",
                "Targets sit closer to the market median and move in smaller steps. Good while you are getting "
                        + "used to the recommendations.", Collections.unmodifiableMap(buyCareful)));
        Map<String, Setting> buyDirect = new LinkedHashMap<>();
        buyDirect.put(BUY_PHASE_IN, Setting.on(false));
        buyDirect.put(BUY_LEARNING, Setting.on(false));
        presets.add(new Preset(Side.BUY, Preset.DIRECT, "Straight to target",
                "Shows the full target at once and does not lean on your past decisions. Good for a sourcing "
                        + "review where you want the model's own answer.", Collections.unmodifiableMap(buyDirect)));

        for (Preset p : presets) {
            for (String key : p.settings().keySet()) {
                Parameter param = byKey.get(key);
                if (param == null || param.side() != p.side()) {
                    throw new IllegalStateException("Preset " + p.side() + "/" + p.key() + " names " + key
                            + ", which is not a parameter on its side");
                }
            }
            if (Config.of(p.settings()).overrides().size() != p.settings().size()) {
                throw new IllegalStateException("Preset " + p.key() + " names a setting that is already the default");
            }
        }
        PRESETS = Collections.unmodifiableList(presets);
    }

    private PricingModel() {
    }

    private static void toggle(List<Parameter> r, String key, Group group, String label, String description,
            boolean defaultOn, String parent, String doc) {
        r.add(new Parameter(key, group, Type.TOGGLE, label, description, defaultOn, null, null, null, "", parent, doc));
    }

    private static void number(List<Parameter> r, String key, Group group, String label, String description,
            String defaultValue, String min, String max, String unit, String parent, String doc) {
        r.add(new Parameter(key, group, Type.NUMBER, label, description, true, new BigDecimal(defaultValue),
                new BigDecimal(min), new BigDecimal(max), unit, parent, doc));
    }

    /** Every parameter of both sides, in display order. */
    public static List<Parameter> registry() {
        return REGISTRY;
    }

    /** One side's parameters, in display order. */
    public static List<Parameter> registry(Side side) {
        List<Parameter> out = new ArrayList<>();
        for (Parameter p : REGISTRY) {
            if (p.side() == side) {
                out.add(p);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public static Optional<Parameter> parameter(String key) {
        return Optional.ofNullable(key == null ? null : BY_KEY.get(key.strip()));
    }

    /** The sell side's presets, in display order; the first is the defaults. */
    public static List<Preset> presets() {
        return presets(Side.SELL);
    }

    /** One side's presets, in display order; the first is the defaults. */
    public static List<Preset> presets(Side side) {
        List<Preset> out = new ArrayList<>();
        for (Preset p : PRESETS) {
            if (p.side() == side) {
                out.add(p);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** A sell-side preset by key. */
    public static Optional<Preset> preset(String key) {
        return preset(Side.SELL, key);
    }

    public static Optional<Preset> preset(Side side, String key) {
        return presets(side).stream().filter(p -> p.key().equals(key)).findFirst();
    }

    // ---- config -----------------------------------------------------------------------

    /**
     * A tenant's model: the registry's defaults with the tenant's overrides on top, for both
     * sides. Built through {@link #of}, which drops unknown keys and clamps numbers, so a
     * stored map from an older build is always readable.
     */
    public static final class Config {

        private static final Config DEFAULTS = new Config(Map.of());

        private final Map<String, Setting> overrides;

        private Config(Map<String, Setting> overrides) {
            this.overrides = Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
        }

        public static Config defaults() {
            return DEFAULTS;
        }

        /**
         * The model a tenant actually runs: the registry's defaults, then what the model learned
         * from the tenant's results, then what the tenant set by hand - a hand-set value always
         * wins. Learned settings for a side count only while that side's auto-tune toggle is on
         * (judged from the hand-set layer, so switching it off cannot be undone by a learned value).
         */
        public static Config layered(Map<String, Setting> learned, Map<String, Setting> overrides) {
            Config hand = of(overrides);
            if (learned == null || learned.isEmpty()) {
                return hand;
            }
            Map<String, Setting> merged = new LinkedHashMap<>();
            for (Map.Entry<String, Setting> e : of(learned).overrides().entrySet()) {
                Parameter p = BY_KEY.get(e.getKey());
                if (p == null) {
                    continue;
                }
                boolean autoTune = hand.on(p.side() == Side.BUY ? BUY_LEARNING_AUTO_TUNE : LEARNING_AUTO_TUNE);
                if (autoTune && !hand.overrides().containsKey(e.getKey())) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            merged.putAll(hand.overrides());
            return merged.isEmpty() ? DEFAULTS : new Config(merged);
        }

        /** Normalises a raw map: unknown keys dropped, numbers clamped, the wrong field for a type ignored. */
        public static Config of(Map<String, Setting> raw) {
            if (raw == null || raw.isEmpty()) {
                return DEFAULTS;
            }
            Map<String, Setting> clean = new LinkedHashMap<>();
            for (Map.Entry<String, Setting> e : raw.entrySet()) {
                Parameter p = e.getKey() == null ? null : BY_KEY.get(e.getKey().strip());
                Setting s = e.getValue();
                if (p == null || s == null) {
                    continue;
                }
                if (p.toggle()) {
                    if (s.on() != null && s.on() != p.defaultOn()) {
                        clean.put(p.key(), Setting.on(s.on()));
                    }
                } else if (s.value() != null) {
                    BigDecimal v = s.value();
                    if (v.compareTo(p.min()) < 0) {
                        v = p.min();
                    }
                    if (v.compareTo(p.max()) > 0) {
                        v = p.max();
                    }
                    if (v.compareTo(p.defaultValue()) != 0) {
                        clean.put(p.key(), Setting.value(v));
                    }
                }
            }
            return clean.isEmpty() ? DEFAULTS : new Config(clean);
        }

        /**
         * This config with one side's overrides replaced by {@code sideOverrides} (normalised
         * the same way as {@link #of}) and the other side's kept: what a save of one side's
         * settings screen does.
         */
        public Config withSide(Side side, Map<String, Setting> sideOverrides) {
            Map<String, Setting> merged = new LinkedHashMap<>();
            for (Map.Entry<String, Setting> e : overrides.entrySet()) {
                Parameter p = BY_KEY.get(e.getKey());
                if (p != null && p.side() != side) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            for (Map.Entry<String, Setting> e : of(sideOverrides).overrides().entrySet()) {
                Parameter p = BY_KEY.get(e.getKey());
                if (p != null && p.side() == side) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            return merged.isEmpty() ? DEFAULTS : new Config(merged);
        }

        /** Only what differs from the defaults, both sides: what is stored. */
        public Map<String, Setting> overrides() {
            return overrides;
        }

        /** Only what differs from the defaults on one side. */
        public Map<String, Setting> overrides(Side side) {
            Map<String, Setting> out = new LinkedHashMap<>();
            for (Map.Entry<String, Setting> e : overrides.entrySet()) {
                Parameter p = BY_KEY.get(e.getKey());
                if (p != null && p.side() == side) {
                    out.put(e.getKey(), e.getValue());
                }
            }
            return Collections.unmodifiableMap(out);
        }

        public boolean isDefault() {
            return overrides.isEmpty();
        }

        public boolean isDefault(Side side) {
            return overrides(side).isEmpty();
        }

        /**
         * Whether a step runs. A number is "on" when its parent toggle is; a toggle with a
         * parent is off whenever the parent is.
         */
        public boolean on(String key) {
            Parameter p = BY_KEY.get(key);
            if (p == null) {
                throw new IllegalArgumentException("Unknown pricing-model parameter " + key);
            }
            if (p.parent() != null && !on(p.parent())) {
                return false;
            }
            if (!p.toggle()) {
                return true;
            }
            Setting s = overrides.get(key);
            return s == null || s.on() == null ? p.defaultOn() : s.on();
        }

        public BigDecimal value(String key) {
            Parameter p = BY_KEY.get(key);
            if (p == null || p.toggle()) {
                throw new IllegalArgumentException("Not a numeric pricing-model parameter: " + key);
            }
            Setting s = overrides.get(key);
            return s == null || s.value() == null ? p.defaultValue() : s.value();
        }

        public double number(String key) {
            return value(key).doubleValue();
        }

        /** Every key of both sides with its effective setting. */
        public Map<String, Setting> effective() {
            Map<String, Setting> out = new LinkedHashMap<>();
            for (Parameter p : REGISTRY) {
                out.put(p.key(), p.toggle() ? Setting.on(on(p.key())) : Setting.value(value(p.key())));
            }
            return out;
        }

        /** Every key of one side with its effective setting, for the screen that shows that side. */
        public Map<String, Setting> effective(Side side) {
            Map<String, Setting> out = new LinkedHashMap<>();
            for (Parameter p : REGISTRY) {
                if (p.side() == side) {
                    out.put(p.key(), p.toggle() ? Setting.on(on(p.key())) : Setting.value(value(p.key())));
                }
            }
            return out;
        }

        /** How many sell toggles are on, out of how many. */
        public int[] toggleCount() {
            return toggleCount(Side.SELL);
        }

        /** How many of one side's toggles are on, out of how many, for a one-line summary. */
        public int[] toggleCount(Side side) {
            int on = 0;
            int total = 0;
            for (Parameter p : REGISTRY) {
                if (p.toggle() && p.side() == side) {
                    total++;
                    if (on(p.key())) {
                        on++;
                    }
                }
            }
            return new int[] {on, total};
        }

        /** The sell preset these overrides are exactly, or {@link Preset#CUSTOM}. */
        public String activePreset() {
            return activePreset(Side.SELL);
        }

        /** The preset one side's overrides are exactly, or {@link Preset#CUSTOM}. */
        public String activePreset(Side side) {
            Map<String, Setting> mine = overrides(side);
            for (Preset p : presets(side)) {
                if (sameSettings(Config.of(p.settings()).overrides(), mine)) {
                    return p.key();
                }
            }
            return Preset.CUSTOM;
        }

        private static boolean sameSettings(Map<String, Setting> a, Map<String, Setting> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (Map.Entry<String, Setting> e : a.entrySet()) {
                Setting other = b.get(e.getKey());
                if (other == null) {
                    return false;
                }
                Setting mine = e.getValue();
                boolean onSame = mine.on() == null ? other.on() == null : mine.on().equals(other.on());
                boolean valueSame = mine.value() == null ? other.value() == null
                        : other.value() != null && mine.value().compareTo(other.value()) == 0;
                if (!onSame || !valueSame) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return "PricingModel.Config" + overrides;
        }
    }
}
