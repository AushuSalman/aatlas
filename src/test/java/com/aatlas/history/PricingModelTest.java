package com.aatlas.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The registry is well-formed and {@link PricingModel.Config} normalises a raw override map
 * the way the reader and the store both rely on: unknown keys dropped, numbers clamped,
 * defaults stripped, a child silenced by its parent.
 */
class PricingModelTest {

    @Test
    @DisplayName("every key in the registry is unique")
    void keysAreUnique() {
        Set<String> seen = new HashSet<>();
        for (PricingModel.Parameter p : PricingModel.registry()) {
            assertThat(seen.add(p.key())).as("duplicate key %s", p.key()).isTrue();
        }
        assertThat(seen).isNotEmpty();
    }

    @Test
    @DisplayName("every parent names an existing toggle")
    void parentsAreToggles() {
        for (PricingModel.Parameter p : PricingModel.registry()) {
            if (p.parent() != null) {
                PricingModel.Parameter parent = PricingModel.parameter(p.parent()).orElse(null);
                assertThat(parent).as("%s names parent %s", p.key(), p.parent()).isNotNull();
                assertThat(parent.toggle()).as("%s's parent %s is a toggle", p.key(), p.parent()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("every number's default sits inside [min, max]; toggles carry no range")
    void defaultsAreInRange() {
        for (PricingModel.Parameter p : PricingModel.registry()) {
            if (p.toggle()) {
                assertThat(p.defaultValue()).as("%s", p.key()).isNull();
                assertThat(p.min()).as("%s", p.key()).isNull();
                assertThat(p.max()).as("%s", p.key()).isNull();
            } else {
                assertThat(p.defaultValue()).as("%s", p.key()).isNotNull();
                assertThat(p.min()).as("%s", p.key()).isNotNull();
                assertThat(p.max()).as("%s", p.key()).isNotNull();
                assertThat(p.defaultValue()).as("%s default >= min", p.key()).isGreaterThanOrEqualTo(p.min());
                assertThat(p.defaultValue()).as("%s default <= max", p.key()).isLessThanOrEqualTo(p.max());
                assertThat(p.min()).as("%s min <= max", p.key()).isLessThanOrEqualTo(p.max());
            }
        }
    }

    @Test
    @DisplayName("Config.of drops a key the registry does not know")
    void ofDropsUnknownKeys() {
        Map<String, PricingModel.Setting> raw = new LinkedHashMap<>();
        raw.put("retired.parameter", PricingModel.Setting.on(false));
        raw.put("nonsense", PricingModel.Setting.value(new BigDecimal("3")));
        raw.put(PricingModel.ROUNDING, PricingModel.Setting.on(false));

        PricingModel.Config config = PricingModel.Config.of(raw);

        assertThat(config.overrides()).containsOnlyKeys(PricingModel.ROUNDING);
        assertThat(config.on(PricingModel.ROUNDING)).isFalse();
    }

    @Test
    @DisplayName("Config.of clamps a number outside its range to the nearer bound")
    void ofClampsToTheBound() {
        PricingModel.Parameter p = PricingModel.parameter(PricingModel.DEMAND_MAX_MOVE).orElseThrow();
        Map<String, PricingModel.Setting> raw = new LinkedHashMap<>();
        raw.put(PricingModel.DEMAND_MAX_MOVE, PricingModel.Setting.value(p.max().add(BigDecimal.TEN)));
        raw.put(PricingModel.LEARNING_MAX_MOVE, PricingModel.Setting.value(new BigDecimal("-7")));

        PricingModel.Config config = PricingModel.Config.of(raw);

        assertThat(config.value(PricingModel.DEMAND_MAX_MOVE)).isEqualByComparingTo(p.max());
        assertThat(config.value(PricingModel.LEARNING_MAX_MOVE))
                .isEqualByComparingTo(PricingModel.parameter(PricingModel.LEARNING_MAX_MOVE).orElseThrow().min());
    }

    @Test
    @DisplayName("Config.of stores only what differs from the defaults")
    void ofStripsDefaults() {
        Map<String, PricingModel.Setting> raw = new LinkedHashMap<>();
        raw.put(PricingModel.ELASTICITY, PricingModel.Setting.on(true));                              // default
        raw.put(PricingModel.DEMAND_MAX_MOVE, PricingModel.Setting.value(new BigDecimal("3.00")));   // default, other scale
        raw.put(PricingModel.ROUNDING, PricingModel.Setting.on(false));                              // changed
        raw.put(PricingModel.TRUST_RAMP_HALF_POINT, PricingModel.Setting.value(new BigDecimal("20"))); // changed
        raw.put(PricingModel.COMMODITY, new PricingModel.Setting(null, null));                       // "the default"
        raw.put(PricingModel.MOVE_CAP, null);                                                        // nothing

        PricingModel.Config config = PricingModel.Config.of(raw);

        assertThat(config.overrides()).containsOnlyKeys(PricingModel.ROUNDING, PricingModel.TRUST_RAMP_HALF_POINT);
        assertThat(config.overrides().get(PricingModel.ROUNDING)).isEqualTo(PricingModel.Setting.on(false));
        assertThat(config.overrides().get(PricingModel.TRUST_RAMP_HALF_POINT).value()).isEqualByComparingTo("20");
        assertThat(config.isDefault()).isFalse();
    }

    @Test
    @DisplayName("Config.of ignores the wrong field for a parameter's type")
    void ofIgnoresTheWrongField() {
        Map<String, PricingModel.Setting> raw = new HashMap<>();
        raw.put(PricingModel.ROUNDING, PricingModel.Setting.value(new BigDecimal("5")));   // toggle sent a value
        raw.put(PricingModel.DEMAND_MAX_MOVE, PricingModel.Setting.on(false));            // number sent a toggle

        assertThat(PricingModel.Config.of(raw).isDefault()).isTrue();
    }

    @Test
    @DisplayName("a child is off when its parent is off, whatever the child says")
    void childFollowsParent() {
        Map<String, PricingModel.Setting> raw = new HashMap<>();
        raw.put(PricingModel.COMPETITORS, PricingModel.Setting.on(false));
        raw.put(PricingModel.COMPETITORS_PLAUSIBILITY, PricingModel.Setting.on(true));
        raw.put(PricingModel.LEARNING, PricingModel.Setting.on(false));

        PricingModel.Config config = PricingModel.Config.of(raw);

        assertThat(config.on(PricingModel.COMPETITORS_PLAUSIBILITY)).isFalse();
        // A number under an off toggle is off too, though its value still reads.
        assertThat(config.on(PricingModel.LEARNING_MAX_MOVE)).isFalse();
        assertThat(config.value(PricingModel.LEARNING_MAX_MOVE)).isEqualByComparingTo("2");
        // And on again when the parent is on.
        assertThat(PricingModel.Config.defaults().on(PricingModel.COMPETITORS_PLAUSIBILITY)).isTrue();
        assertThat(PricingModel.Config.defaults().on(PricingModel.LEARNING_STRATEGY)).isTrue();
    }

    @Test
    @DisplayName("effective() answers every key, with the type's field set")
    void effectiveHasEveryKey() {
        Map<String, PricingModel.Setting> raw = new HashMap<>();
        raw.put(PricingModel.ROUNDING, PricingModel.Setting.on(false));
        raw.put(PricingModel.DEMAND_MAX_MOVE, PricingModel.Setting.value(new BigDecimal("5")));
        Map<String, PricingModel.Setting> effective = PricingModel.Config.of(raw).effective();

        assertThat(effective).hasSize(PricingModel.registry().size());
        for (PricingModel.Parameter p : PricingModel.registry()) {
            PricingModel.Setting s = effective.get(p.key());
            assertThat(s).as("%s", p.key()).isNotNull();
            if (p.toggle()) {
                assertThat(s.on()).as("%s", p.key()).isNotNull();
                assertThat(s.value()).as("%s", p.key()).isNull();
            } else {
                assertThat(s.value()).as("%s", p.key()).isNotNull();
                assertThat(s.on()).as("%s", p.key()).isNull();
            }
        }
        assertThat(effective.get(PricingModel.ROUNDING).on()).isFalse();
        assertThat(effective.get(PricingModel.DEMAND_MAX_MOVE).value()).isEqualByComparingTo("5");
        assertThat(effective.get(PricingModel.ELASTICITY).on()).isTrue();
    }

    @Test
    @DisplayName("the trained models are switches in the same registry: on by default, each under the step it feeds")
    void trainedModelToggles() {
        PricingModel.Parameter sell = PricingModel.registry(PricingModel.Side.SELL).stream()
                .filter(p -> p.key().equals(PricingModel.ELASTICITY_TRAINED_MODEL)).findFirst().orElseThrow();
        assertThat(sell.toggle()).isTrue();
        assertThat(sell.parent()).isEqualTo(PricingModel.ELASTICITY);
        PricingModel.Parameter buy = PricingModel.registry(PricingModel.Side.BUY).stream()
                .filter(p -> p.key().equals(PricingModel.BUY_RELIABILITY_TRAINED_MODEL)).findFirst().orElseThrow();
        assertThat(buy.toggle()).isTrue();
        assertThat(buy.parent()).isEqualTo(PricingModel.BUY_RELIABILITY);
        PricingModel.Config defaults = PricingModel.Config.defaults();
        assertThat(defaults.on(PricingModel.ELASTICITY_TRAINED_MODEL)).isTrue();
        assertThat(defaults.on(PricingModel.BUY_RELIABILITY_TRAINED_MODEL)).isTrue();
    }

    @Test
    @DisplayName("toggleCount() counts the toggles that are on, children of an off parent excluded")
    void toggleCountSums() {
        long toggles = PricingModel.registry(PricingModel.Side.SELL).stream().filter(PricingModel.Parameter::toggle).count();
        int[] all = PricingModel.Config.defaults().toggleCount();
        assertThat(all[1]).isEqualTo((int) toggles);
        assertThat(all[0]).isEqualTo((int) toggles); // every toggle defaults on

        Map<String, PricingModel.Setting> raw = new HashMap<>();
        raw.put(PricingModel.ROUNDING, PricingModel.Setting.on(false));
        // Switching competitors off silences competitors.plausibility and competitors.marketGap too:
        // with rounding, four fewer.
        raw.put(PricingModel.COMPETITORS, PricingModel.Setting.on(false));
        int[] some = PricingModel.Config.of(raw).toggleCount();
        assertThat(some[1]).isEqualTo((int) toggles);
        assertThat(some[0]).isEqualTo((int) toggles - 4);
    }

    @Test
    @DisplayName("the defaults are the defaults")
    void defaultsAreDefault() {
        assertThat(PricingModel.Config.defaults().isDefault()).isTrue();
        assertThat(PricingModel.Config.defaults().overrides()).isEmpty();
        assertThat(PricingModel.Config.of(null)).isSameAs(PricingModel.Config.defaults());
        assertThat(PricingModel.Config.of(Map.of())).isSameAs(PricingModel.Config.defaults());
    }

    @Test
    @DisplayName("presets: three, the first is the defaults, every key they name is a real parameter")
    void presets() {
        assertThat(PricingModel.presets()).extracting(PricingModel.Preset::key)
                .containsExactly(PricingModel.Preset.BALANCED, PricingModel.Preset.CAREFUL, PricingModel.Preset.DIRECT);
        assertThat(PricingModel.presets().get(0).settings()).isEmpty();
        for (PricingModel.Preset p : PricingModel.presets()) {
            for (String key : p.settings().keySet()) {
                assertThat(PricingModel.parameter(key)).as(p.key() + " names " + key).isPresent();
            }
            // Applying a preset and reading it back names that preset.
            assertThat(PricingModel.Config.of(p.settings()).activePreset()).isEqualTo(p.key());
        }
    }

    @Test
    @DisplayName("activePreset is custom for any other mix, and balanced for the defaults")
    void activePreset() {
        assertThat(PricingModel.Config.defaults().activePreset()).isEqualTo(PricingModel.Preset.BALANCED);
        Map<String, PricingModel.Setting> mix = new LinkedHashMap<>(PricingModel.preset(PricingModel.Preset.CAREFUL)
                .orElseThrow().settings());
        mix.put(PricingModel.ROUNDING, PricingModel.Setting.on(false));
        assertThat(PricingModel.Config.of(mix).activePreset()).isEqualTo(PricingModel.Preset.CUSTOM);
        assertThat(PricingModel.Config.of(Map.of(PricingModel.DEMAND, PricingModel.Setting.on(false))).activePreset())
                .isEqualTo(PricingModel.Preset.CUSTOM);
    }

    // ---- layered: defaults, then learned, then hand-set ---------------------------------

    private static PricingModel.Setting value(String v) {
        return PricingModel.Setting.value(new BigDecimal(v));
    }

    @Test
    @DisplayName("layered: a learned value applies on top of the defaults, on either side")
    void layeredAppliesLearned() {
        Map<String, PricingModel.Setting> learned = new LinkedHashMap<>();
        learned.put(PricingModel.TRUST_RAMP_LAUNCH, value("45"));
        learned.put(PricingModel.BUY_TARGET_GAP_SHARE, value("50"));

        PricingModel.Config config = PricingModel.Config.layered(learned, Map.of());

        assertThat(config.value(PricingModel.TRUST_RAMP_LAUNCH)).isEqualByComparingTo("45");
        assertThat(config.value(PricingModel.BUY_TARGET_GAP_SHARE)).isEqualByComparingTo("50");
        assertThat(config.overrides()).containsOnlyKeys(PricingModel.TRUST_RAMP_LAUNCH, PricingModel.BUY_TARGET_GAP_SHARE);
        // Nothing learned: the hand-set model itself.
        assertThat(PricingModel.Config.layered(Map.of(), Map.of())).isSameAs(PricingModel.Config.defaults());
        assertThat(PricingModel.Config.layered(null, Map.of(PricingModel.ROUNDING, PricingModel.Setting.on(false)))
                .overrides()).containsOnlyKeys(PricingModel.ROUNDING);
    }

    @Test
    @DisplayName("layered: a hand-set value wins over a learned one for the same key")
    void layeredHandSetWins() {
        Map<String, PricingModel.Setting> learned = Map.of(PricingModel.TRUST_RAMP_LAUNCH, value("45"));
        Map<String, PricingModel.Setting> hand = new LinkedHashMap<>();
        hand.put(PricingModel.TRUST_RAMP_LAUNCH, value("30"));
        hand.put(PricingModel.ROUNDING, PricingModel.Setting.on(false));

        PricingModel.Config config = PricingModel.Config.layered(learned, hand);

        assertThat(config.value(PricingModel.TRUST_RAMP_LAUNCH)).isEqualByComparingTo("30");
        assertThat(config.on(PricingModel.ROUNDING)).isFalse();
        assertThat(config.overrides()).containsOnlyKeys(PricingModel.TRUST_RAMP_LAUNCH, PricingModel.ROUNDING);
    }

    @Test
    @DisplayName("layered: with a side's auto-tune off, that side's learned values are dropped and the other side's stay")
    void layeredDropsLearnedWhenAutoTuneIsOff() {
        Map<String, PricingModel.Setting> learned = new LinkedHashMap<>();
        learned.put(PricingModel.TRUST_RAMP_LAUNCH, value("45"));
        learned.put(PricingModel.BUY_TARGET_GAP_SHARE, value("50"));

        PricingModel.Config sellOff = PricingModel.Config.layered(learned,
                Map.of(PricingModel.LEARNING_AUTO_TUNE, PricingModel.Setting.on(false)));
        assertThat(sellOff.value(PricingModel.TRUST_RAMP_LAUNCH)).isEqualByComparingTo("25");
        assertThat(sellOff.value(PricingModel.BUY_TARGET_GAP_SHARE)).isEqualByComparingTo("50");
        assertThat(sellOff.overrides()).containsOnlyKeys(PricingModel.LEARNING_AUTO_TUNE, PricingModel.BUY_TARGET_GAP_SHARE);

        PricingModel.Config buyOff = PricingModel.Config.layered(learned,
                Map.of(PricingModel.BUY_LEARNING_AUTO_TUNE, PricingModel.Setting.on(false)));
        assertThat(buyOff.value(PricingModel.TRUST_RAMP_LAUNCH)).isEqualByComparingTo("45");
        assertThat(buyOff.value(PricingModel.BUY_TARGET_GAP_SHARE)).isEqualByComparingTo("35");

        // The parent step off silences its auto-tune toggle too.
        PricingModel.Config learningOff = PricingModel.Config.layered(learned,
                Map.of(PricingModel.LEARNING, PricingModel.Setting.on(false)));
        assertThat(learningOff.value(PricingModel.TRUST_RAMP_LAUNCH)).isEqualByComparingTo("25");
        assertThat(learningOff.value(PricingModel.BUY_TARGET_GAP_SHARE)).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("layered: learned values are normalised like hand-set ones - unknown keys dropped, numbers clamped, defaults stripped")
    void layeredNormalisesLearned() {
        Map<String, PricingModel.Setting> learned = new LinkedHashMap<>();
        learned.put("retired.parameter", value("1"));
        learned.put(PricingModel.DEMAND_MAX_MOVE, value("99"));
        learned.put(PricingModel.TRUST_RAMP_LAUNCH, value("25")); // the default

        PricingModel.Config config = PricingModel.Config.layered(learned, Map.of());

        assertThat(config.value(PricingModel.DEMAND_MAX_MOVE))
                .isEqualByComparingTo(PricingModel.parameter(PricingModel.DEMAND_MAX_MOVE).orElseThrow().max());
        assertThat(config.overrides()).containsOnlyKeys(PricingModel.DEMAND_MAX_MOVE);
    }
}
