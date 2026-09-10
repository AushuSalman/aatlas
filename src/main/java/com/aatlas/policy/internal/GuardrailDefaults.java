package com.aatlas.policy.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The platform defaults, from {@code seed/guardrails.json}: what a tenant is on until
 * someone with the right seat saves, and what {@code POST /guardrails/reset} goes back to.
 *
 * <p>Read once at startup and checked against the ranges, so a bad edit to the seed file
 * fails the boot rather than the first tenant's reset.
 */
@Component
class GuardrailDefaults {

    private final GuardrailValues values;

    GuardrailDefaults(ObjectMapper json) {
        try (InputStream in = new ClassPathResource("seed/guardrails.json").getInputStream()) {
            this.values = json.readValue(in, GuardrailValues.class).normalised();
        } catch (IOException ex) {
            throw new UncheckedIOException("seed/guardrails.json is missing or unreadable", ex);
        }
        GuardrailLimits.check(values);
    }

    GuardrailValues values() {
        return values;
    }
}
