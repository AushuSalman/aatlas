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
 * <p>Labels and descriptions are written for the customer, not the engineer: they are what
 * the settings screen, the Sell derivation and the "How we price" page show. The section of
 * the pricing walkthrough each comes from is kept in {@link Parameter#doc} for the reader who
 * wants the maths.
 *
 * <p>The two guardrails every price is bounded by - the minimum-margin floor and the market
 * ceiling - are the tenant's guardrails ({@link Reference.Guardrails}) and are never part of
 * this list: nothing here can switch them off.
 */
public final class PricingModel {

    public enum Type { TOGGLE, NUMBER }

    /** The settings screen's sections, in order. */
    public enum Group {
        MEASURE("measure", "What we learn from your sales", "How each item sells, before a price is worked out."),
        COMPOSE("compose", "Where the price starts", "The starting point, and how the higher-margin price is found."),
        ADJUST("adjust", "Small adjustments",
                "Bounded nudges for demand, commodity costs and the local market, and moving to new prices gradually."),
        LEARN("learn", "Learning from your decisions", "What your own pricing decisions are allowed to change."),
        GUARD("guard", "Safety limits", "Checks every price passes before you see it.");

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
     * @param doc the section of the pricing walkthrough this comes from, for the reader who wants the maths
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

    /**
     * A one-click set of overrides for people who do not want to tune knobs.
     *
     * @param settings the overrides on top of the defaults; empty for the recommended preset
     */
    public record Preset(String key, String label, String blurb, Map<String, Setting> settings) {

        public static final String BALANCED = "balanced";
        public static final String CAREFUL = "careful";
        public static final String DIRECT = "direct";
        public static final String CUSTOM = "custom";
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
    private static final List<Preset> PRESETS;

    static {
        List<Parameter> r = new ArrayList<>();

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
                "A price this far from your own is more likely a different product than a real market price.", "30",
                "5", "100", "%", ANCHOR_INTERNAL, "§3 step 8b");
        toggle(r, COMPETITORS, Group.COMPOSE, "Use competitor prices",
                "Prices you imported, entered or fetched live count toward the starting point and the ceiling. "
                        + "Off: priced from your own history and the benchmarks only.", true, null, "§3 step 7");
        toggle(r, COMPETITORS_PLAUSIBILITY, Group.COMPOSE, "Ignore competitor prices that look wrong",
                "A competitor price under half or over twice your minimum-margin price is left out.", true,
                COMPETITORS, "§3 step 7");
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
                "How far toward the target an item with no decisions yet is priced.", "25", "0", "100", "%",
                TRUST_RAMP, "§3 step 8d");
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

        // ---- safety limits --------------------------------------------------------------
        toggle(r, CORRIDOR_HISTORY, Group.GUARD, "Stay within the prices this item has sold at",
                "The target floor is the lower end of what the item has actually sold for, and its upper end "
                        + "counts toward the ceiling. Trusted less with only a few sales.", true, null, "§3 step 6");
        number(r, CORRIDOR_MIN_OBSERVATIONS, Group.GUARD, "Sales needed to trust this", "", "8", "1", "50", "sales",
                CORRIDOR_HISTORY, "§3 step 6");
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

        // ---- presets ------------------------------------------------------------------------
        List<Preset> presets = new ArrayList<>();
        presets.add(new Preset(Preset.BALANCED, "Recommended",
                "Every step on with the standard settings. Right for most businesses.", Map.of()));
        Map<String, Setting> careful = new LinkedHashMap<>();
        careful.put(MOVE_CAP_MAX_PCT, Setting.value(new BigDecimal("10")));
        careful.put(DEMAND_MAX_MOVE, Setting.value(new BigDecimal("2")));
        careful.put(LEARNING_MAX_MOVE, Setting.value(new BigDecimal("1")));
        careful.put(ANCHOR_EXTERNAL_ADJUST_CAP, Setting.value(new BigDecimal("5")));
        careful.put(TRUST_RAMP_LAUNCH, Setting.value(new BigDecimal("15")));
        careful.put(TRUST_RAMP_HALF_POINT, Setting.value(new BigDecimal("20")));
        presets.add(new Preset(Preset.CAREFUL, "Careful",
                "Prices move in smaller steps and take longer to reach their targets. Good while you are getting "
                        + "used to the recommendations.", Collections.unmodifiableMap(careful)));
        Map<String, Setting> direct = new LinkedHashMap<>();
        direct.put(TRUST_RAMP, Setting.on(false));
        direct.put(LEARNING, Setting.on(false));
        presets.add(new Preset(Preset.DIRECT, "Straight to target",
                "Shows the full target price at once and does not lean on your past decisions. Good for a price "
                        + "review where you want the model's own answer.", Collections.unmodifiableMap(direct)));
        for (Preset p : presets) {
            if (!Config.of(p.settings()).overrides().equals(normalisedOverrides(p.settings()))) {
                throw new IllegalStateException("Preset " + p.key() + " names a parameter it cannot set");
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

    /** A preset's settings as {@link Config#of} would keep them: every key it names must survive. */
    private static Map<String, Setting> normalisedOverrides(Map<String, Setting> raw) {
        Map<String, Setting> out = new LinkedHashMap<>();
        for (Map.Entry<String, Setting> e : raw.entrySet()) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** Every parameter, in display order. */
    public static List<Parameter> registry() {
        return REGISTRY;
    }

    public static Optional<Parameter> parameter(String key) {
        return Optional.ofNullable(key == null ? null : BY_KEY.get(key.strip()));
    }

    /** The one-click presets, in display order; the first is the defaults. */
    public static List<Preset> presets() {
        return PRESETS;
    }

    public static Optional<Preset> preset(String key) {
        return PRESETS.stream().filter(p -> p.key().equals(key)).findFirst();
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

        /** The preset these overrides are exactly, or {@link Preset#CUSTOM}. */
        public String activePreset() {
            for (Preset p : PRESETS) {
                if (sameSettings(Config.of(p.settings()).overrides(), overrides)) {
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
