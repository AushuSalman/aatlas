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
    @DisplayName("toggleCount() counts the toggles that are on, children of an off parent excluded")
    void toggleCountSums() {
        long toggles = PricingModel.registry().stream().filter(PricingModel.Parameter::toggle).count();
        int[] all = PricingModel.Config.defaults().toggleCount();
        assertThat(all[1]).isEqualTo((int) toggles);
        assertThat(all[0]).isEqualTo((int) toggles); // every toggle defaults on

        Map<String, PricingModel.Setting> raw = new HashMap<>();
        raw.put(PricingModel.ROUNDING, PricingModel.Setting.on(false));
        // Switching competitors off silences competitors.plausibility too: two fewer.
        raw.put(PricingModel.COMPETITORS, PricingModel.Setting.on(false));
        int[] some = PricingModel.Config.of(raw).toggleCount();
        assertThat(some[1]).isEqualTo((int) toggles);
        assertThat(some[0]).isEqualTo((int) toggles - 3);
    }

    @Test
    @DisplayName("the defaults are the defaults")
    void defaultsAreDefault() {
        assertThat(PricingModel.Config.defaults().isDefault()).isTrue();
        assertThat(PricingModel.Config.defaults().overrides()).isEmpty();
        assertThat(PricingModel.Config.of(null)).isSameAs(PricingModel.Config.defaults());
        assertThat(PricingModel.Config.of(Map.of())).isSameAs(PricingModel.Config.defaults());
    }
}
