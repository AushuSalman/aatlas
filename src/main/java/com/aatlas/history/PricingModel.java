package com.aatlas.history;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The pricing model's parameters: every step the recommendation chain can take, what each
 * defaults to, and a tenant's overrides on top.
 *
 * <p>The registry is the single list. A parameter is either a <b>toggle</b> (a step that can
 * be switched off) or a <b>number</b> (a knob a step reads), and a number may belong to a
 * toggle ({@link Parameter#parent}) so that switching the step off silences its knobs too.
 * Adding a step or a knob is one entry here plus the line of {@link PricingMath} that reads
 * it: the settings screen renders whatever this list says, the store keeps only overrides
 * keyed by {@link Parameter#key}, and an override for a key that no longer exists is
 * dropped on read rather than failing.
 *
 * <p>The two guardrails every price is bounded by - the minimum-margin floor and the market
 * ceiling - are the tenant's guardrails ({@link Reference.Guardrails}) and are never part of
 * this list: nothing here can switch them off.
 */
public final class PricingModel {

    public enum Type { TOGGLE, NUMBER }

    /** The settings screen's sections, in order. */
    public enum Group {
        MEASURE("measure", "Measure", "What the history says about this item before a price is composed."),
        COMPOSE("compose", "Anchor and compose", "Where the two prices start from and how the profit tier is found."),
        ADJUST("adjust", "Adjust", "Bounded nudges for demand, commodity, the local market and the phase-in."),
        LEARN("learn", "Learn from your decisions", "What your own decision history is allowed to change."),
        GUARD("guard", "Final checks", "What happens to every price before it leaves the model.");

        private final String key;
        private final String label;
        private final String blurb;

        Group(String key, String label, String blurb) {
            this.key = key;
            this.label = label;
            this.blurb = blurb;
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
    }

    /**
     * One entry of the registry.
     *
     * @param parent the toggle this number belongs to, or null for a top-level parameter
     * @param unit what the number is in ("%", "orders", "days", "× cost", or "" for a bare ratio)
     * @param doc the section of the pricing walkthrough this comes from, for the settings screen
     */
    public record Parameter(String key, Group group, Type type, String label, String description,
            boolean defaultOn, BigDecimal defaultValue, BigDecimal min, BigDecimal max, String unit,
            String parent, String doc) {

        public boolean toggle() {
            return type == Type.TOGGLE;
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

    // ---- keys -------------------------------------------------------------------------

    public static final String ELASTICITY = "elasticity";
    public static final String ELASTICITY_PRIOR = "elasticity.prior";
    public static final String ELASTICITY_PRIOR_WEIGHT = "elasticity.priorWeight";
    public static final String SEGMENT = "segment";
    public static final String SEGMENT_REGULAR_MIN_ORDERS = "segment.regularMinOrders";

    public static final String ANCHOR_INTERNAL = "anchor.internal";
    public static final String ANCHOR_EXTERNAL_ADJUST_CAP = "anchor.externalAdjustCap";
    public static final String ANCHOR_EXTERNAL_DIVERGENCE = "anchor.externalDivergence";
    public static final String COMPETITORS = "competitors";
    public static final String COMPETITORS_PLAUSIBILITY = "competitors.plausibility";
    public static final String BLEND_OWN_PRICE = "blend.ownPrice";
    public static final String AGGRESSIVE_PROFIT_MAX = "aggressive.profitMax";
    public static final String AGGRESSIVE_RISK_AVERSION = "aggressive.riskAversion";
    public static final String TIER_GAP = "tierGap";
    public static final String TIER_GAP_BASE = "tierGap.base";
    public static final String TIER_GAP_CONTESTED = "tierGap.contested";

    public static final String DEMAND = "demand";
    public static final String DEMAND_MAX_MOVE = "demand.maxMove";
    public static final String DEMAND_MAX_AGE_DAYS = "demand.maxAgeDays";
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

    public static final String CORRIDOR_HISTORY = "corridor.history";
    public static final String CORRIDOR_MIN_OBSERVATIONS = "corridor.minObservations";
    public static final String CEILING_PLAUSIBILITY = "ceiling.plausibility";
    public static final String CEILING_COST_MULTIPLE = "ceiling.costMultiple";
    public static final String MOVE_CAP = "moveCap";
    public static final String MOVE_CAP_MAX_PCT = "moveCap.maxPct";
    public static final String ROUNDING = "rounding";

    private static final List<Parameter> REGISTRY;
    private static final Map<String, Parameter> BY_KEY;

    static {
        List<Parameter> r = new ArrayList<>();

        // ---- measure -------------------------------------------------------------------
        toggle(r, ELASTICITY, Group.MEASURE, "Measured price sensitivity",
                "Fit how fast this item's sales fall when its price rises, from your own invoice lines "
                        + "(this branch, then the item, then its category), and blend it with the prior below. "
                        + "Off: the prior alone.", true, null, "§3 step 2 and 4");
        number(r, ELASTICITY_PRIOR, Group.MEASURE, "Prior elasticity",
                "The sensitivity assumed before any measurement: −1 means a 1% price rise costs 1% of units.",
                "-1.2", "-3", "-0.1", "", ELASTICITY, "§3 step 4");
        number(r, ELASTICITY_PRIOR_WEIGHT, Group.MEASURE, "Weight of the prior",
                "How many measured months it takes to outweigh the prior. Higher means the measurement needs "
                        + "more history before it is trusted.", "8", "0", "50", "obs", ELASTICITY, "§3 step 4");
        toggle(r, SEGMENT, Group.MEASURE, "Regular or occasional headline",
                "An item this branch sells often leads with the optimal (win) price; one it sells rarely leads "
                        + "with the aggressive (profit) price. Off: always lead with the optimal price.",
                true, null, "§3 step 3");
        number(r, SEGMENT_REGULAR_MIN_ORDERS, Group.MEASURE, "Orders that make an item regular",
                "Invoice lines at this branch in the last twelve months.", "5", "1", "50", "orders", SEGMENT,
                "§3 step 3");

        // ---- compose -------------------------------------------------------------------
        toggle(r, ANCHOR_INTERNAL, Group.COMPOSE, "Anchor on your own branches",
                "The win price starts from the median of what your other branches actually charge, when that "
                        + "median is credible. A competitor median only nudges it, by half the gap and never more "
                        + "than the cap. Off: the ladder - competitor median, else peer median, else cost over the "
                        + "benchmark margin, else the last price.", true, null, "§3 step 8b");
        number(r, ANCHOR_EXTERNAL_ADJUST_CAP, Group.COMPOSE, "Most a competitor price can move the anchor",
                "Even a competitor 30% below you moves the baseline by this much at most.", "10", "0", "30", "%",
                ANCHOR_INTERNAL, "§3 step 8b");
        number(r, ANCHOR_EXTERNAL_DIVERGENCE, Group.COMPOSE, "Ignore a competitor price further away than",
                "A gap this wide against your own realised prices is more often a bad product match than a real "
                        + "market.", "30", "5", "100", "%", ANCHOR_INTERNAL, "§3 step 8b");
        toggle(r, COMPETITORS, Group.COMPOSE, "Use competitor prices",
                "Observed competitor prices - imported, entered or fetched live - take part in the anchor and the "
                        + "ceiling. Off: priced from your own history and the benchmarks only.", true, null,
                "§3 step 7");
        toggle(r, COMPETITORS_PLAUSIBILITY, Group.COMPOSE, "Drop implausible competitor prices",
                "A competitor median under half or over twice your minimum-margin price is ignored.", true,
                COMPETITORS, "§3 step 7");
        toggle(r, BLEND_OWN_PRICE, Group.COMPOSE, "Blend the anchor with your own last price",
                "On the ladder, the anchor is weighted against the price this branch last sold at: competitor "
                        + "60/40, peer 50/50, benchmark 35/65. Off: the anchor alone.", true, null, "single-item step 2");
        toggle(r, AGGRESSIVE_PROFIT_MAX, Group.COMPOSE, "Profit-max aggressive tier",
                "The aggressive price is the point on the item's demand curve that earns the most per order, "
                        + "pulled back toward safety when the sensitivity is uncertain. Off: a 4-15% step above the "
                        + "optimal price.", true, null, "§3 step 8a");
        number(r, AGGRESSIVE_RISK_AVERSION, Group.COMPOSE, "Risk aversion",
                "How much a shaky sensitivity pulls the profit price back from the theoretical peak. 0 takes the "
                        + "peak; 1 subtracts one standard deviation of profit.", "1", "0", "3", "", AGGRESSIVE_PROFIT_MAX,
                "§3 step 8a");
        toggle(r, TIER_GAP, Group.COMPOSE, "Keep a gap between the two tiers",
                "The aggressive price sits at least this far above the optimal price: a base gap plus more for a "
                        + "contested item (many competitors, price-sensitive). Fitted inside the guardrails, never by "
                        + "widening them.", true, null, "§3 step 8e and 10");
        number(r, TIER_GAP_BASE, Group.COMPOSE, "Base gap", "", "3", "0", "15", "%", TIER_GAP, "§3 step 8e");
        number(r, TIER_GAP_CONTESTED, Group.COMPOSE, "Extra gap for a fully contested item", "", "15", "0", "40",
                "%", TIER_GAP, "§3 step 8e");

        // ---- adjust --------------------------------------------------------------------
        toggle(r, DEMAND, Group.ADJUST, "Demand nudge",
                "An item selling faster than its own normal pace moves up, slower moves down, scaled by how much "
                        + "evidence there is. Held when the newest sale is older than the age limit.", true, null,
                "§3 step 9");
        number(r, DEMAND_MAX_MOVE, Group.ADJUST, "Most demand can move a price", "", "3", "0", "10", "%", DEMAND,
                "§3 step 9");
        number(r, DEMAND_MAX_AGE_DAYS, Group.ADJUST, "Hold when the last sale is older than", "", "45", "7", "365",
                "days", DEMAND, "§3 step 9");
        toggle(r, COMMODITY, Group.ADJUST, "Commodity pass-through",
                "A share of the item's commodity index move over ninety days (copper, plastics pipe, steel) "
                        + "reaches the price.", true, null, "single-item step 4");
        number(r, COMMODITY_PASS_THROUGH, Group.ADJUST, "Share of the index move passed through", "", "25", "0",
                "100", "%", COMMODITY, "single-item step 4");
        toggle(r, LOCAL_MARKET, Group.ADJUST, "Local market",
                "Regional price parity of the branch's metro, read as how contested the local market is: a dearer, "
                        + "denser metro lowers the win price; a cheaper one supports a higher price.", true, null,
                "§3 step 8c");
        number(r, LOCAL_MARKET_WEIGHT, Group.ADJUST, "Weight on the regional index",
                "100 passes the whole index gap through; 55 passes a little over half.", "55", "0", "100", "%",
                LOCAL_MARKET, "§3 step 8c");
        toggle(r, TRUST_RAMP, Group.ADJUST, "Phase prices in",
                "Both prices start near today's price and move toward their targets as this item builds a track "
                        + "record at this branch - the decisions you have already applied. Off: the targets are "
                        + "quoted at once.", true, null, "§3 step 8d");
        number(r, TRUST_RAMP_HALF_POINT, Group.ADJUST, "Decisions to reach halfway",
                "With this many prior decisions for the item at the branch the price is halfway to its target.",
                "12", "1", "60", "decisions", TRUST_RAMP, "§3 step 8d");
        number(r, TRUST_RAMP_LAUNCH, Group.ADJUST, "Starting point with no track record",
                "How far toward the target a brand-new item at a branch is quoted; the ramp never falls below it.",
                "25", "0", "100", "%", TRUST_RAMP, "§3 step 8d");
        number(r, TRUST_RAMP_WOBBLE, Group.ADJUST, "Wobble",
                "A small, reproducible variation around the ramp so prices do not march up in lockstep. 0 turns "
                        + "it off.", "15", "0", "30", "%", TRUST_RAMP, "§3 step 8d");

        // ---- learn ---------------------------------------------------------------------
        toggle(r, LEARNING, Group.LEARN, "Learn from your decisions",
                "When you keep applying prices above or below what was suggested - for this item at this branch, "
                        + "else the item anywhere, else across the business - the next suggestion leans that way, "
                        + "within the band below. The lean is re-derived from the raw decisions each time, so it "
                        + "cannot ratchet.", true, null, "§3 step 9b");
        number(r, LEARNING_MAX_MOVE, Group.LEARN, "Most your decisions can move a price", "", "2", "0", "10", "%",
                LEARNING, "§3 step 9b");
        number(r, LEARNING_MIN_DECISIONS, Group.LEARN, "Decisions needed before it counts", "", "3", "1", "20",
                "decisions", LEARNING, "§3 step 9b");
        number(r, LEARNING_WINDOW_DAYS, Group.LEARN, "Look back", "", "180", "30", "730", "days", LEARNING,
                "§3 step 9b");
        number(r, LEARNING_HALF_LIFE_DAYS, Group.LEARN, "A decision's weight halves every", "", "60", "7", "365",
                "days", LEARNING, "§3 step 9b");
        number(r, LEARNING_DEADBAND, Group.LEARN, "Ignore a lean smaller than",
                "A split verdict is not a verdict: the price holds while your deviation stays inside this.", "1",
                "0", "10", "%", LEARNING, "§3 step 9b");
        toggle(r, LEARNING_STRATEGY, Group.LEARN, "Follow your usual strategy",
                "If the bulk basket strategy you pick most is max profit, lead with the aggressive tier; if it is "
                        + "fast movement or balanced, lead with the optimal tier.", true, LEARNING, "§1");

        // ---- guard ---------------------------------------------------------------------
        toggle(r, CORRIDOR_HISTORY, Group.GUARD, "Corridor from the prices this item really carried",
                "The target floor is the lower quartile of the prices this item sold at (never under the "
                        + "minimum-margin floor), and its upper quartile joins the ceiling candidates. Shrunk toward "
                        + "the hard floor with fewer observations than the minimum.", true, null, "§3 step 6");
        number(r, CORRIDOR_MIN_OBSERVATIONS, Group.GUARD, "Observations to trust the corridor", "", "8", "1", "50",
                "obs", CORRIDOR_HISTORY, "§3 step 6");
        toggle(r, CEILING_PLAUSIBILITY, Group.GUARD, "Plausibility cap on the ceiling",
                "No ceiling above a multiple of cost, whatever the peers or competitors say.", true, null,
                "§3 step 6b");
        number(r, CEILING_COST_MULTIPLE, Group.GUARD, "Ceiling at most", "", "4", "1.5", "10", "× cost",
                CEILING_PLAUSIBILITY, "§3 step 6b");
        toggle(r, MOVE_CAP, Group.GUARD, "Limit the move per run",
                "Neither price moves further than this from today's price in one go. The minimum-margin floor "
                        + "and the market ceiling outrank it: an item that must fall further to get under the "
                        + "ceiling is allowed to.", true, null, "§3 step 10");
        number(r, MOVE_CAP_MAX_PCT, Group.GUARD, "Most a price moves per run", "", "25", "5", "100", "%",
                MOVE_CAP, "§3 step 10");
        toggle(r, ROUNDING, Group.GUARD, "Round to retail price points",
                "0.01 under $10, 0.05 under $100, 0.50 under $1,000, else 1.00 - rounded back inside the band.",
                true, null, "single-item step 8");

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
            }
            if (!p.toggle() && (p.defaultValue() == null || p.min() == null || p.max() == null
                    || p.defaultValue().compareTo(p.min()) < 0 || p.defaultValue().compareTo(p.max()) > 0)) {
                throw new IllegalStateException(p.key() + " has a default outside its range");
            }
        }
        BY_KEY = Collections.unmodifiableMap(byKey);
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

    /** Every parameter, in display order. */
    public static List<Parameter> registry() {
        return REGISTRY;
    }

    public static Optional<Parameter> parameter(String key) {
        return Optional.ofNullable(key == null ? null : BY_KEY.get(key.strip()));
    }

    // ---- config -----------------------------------------------------------------------

    /**
     * A tenant's model: the registry's defaults with the tenant's overrides on top. Built
     * through {@link #of}, which drops unknown keys and clamps numbers, so a stored map from
     * an older build is always readable.
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

        /** Only what differs from the defaults: what is stored. */
        public Map<String, Setting> overrides() {
            return overrides;
        }

        public boolean isDefault() {
            return overrides.isEmpty();
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

        /** Every key with its effective setting, for a screen that shows the whole model. */
        public Map<String, Setting> effective() {
            Map<String, Setting> out = new LinkedHashMap<>();
            for (Parameter p : REGISTRY) {
                out.put(p.key(), p.toggle() ? Setting.on(on(p.key())) : Setting.value(value(p.key())));
            }
            return out;
        }

        /** How many toggles are on, out of how many, for a one-line summary. */
        public int[] toggleCount() {
            int on = 0;
            int total = 0;
            for (Parameter p : REGISTRY) {
                if (p.toggle()) {
                    total++;
                    if (on(p.key())) {
                        on++;
                    }
                }
            }
            return new int[] {on, total};
        }

        @Override
        public String toString() {
            return "PricingModel.Config" + overrides;
        }
    }
}
