package com.aatlas.sell.internal.policy;

import com.aatlas.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class GuardrailsGatewayImpl implements GuardrailsGateway {

    private final JdbcTemplate jdbc;
    private final GuardrailValues platformDefaults;

    GuardrailsGatewayImpl(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.platformDefaults = readDefaults(json);
    }

    @Override
    public GuardrailValues current() {
        List<GuardrailValues> rows = jdbc.query(
                """
                select min_margin_pct, max_discount_pct, max_speed_premium_pct, max_market_deviation_pct
                from pricing_guardrails where tenant_id = ?
                """,
                (rs, rowNum) -> new GuardrailValues(
                        rs.getBigDecimal("min_margin_pct").doubleValue(),
                        rs.getBigDecimal("max_discount_pct").doubleValue(),
                        rs.getBigDecimal("max_speed_premium_pct").doubleValue(),
                        rs.getBigDecimal("max_market_deviation_pct").doubleValue()),
                TenantContext.requireTenantId());
        return rows.stream().findFirst().orElse(platformDefaults);
    }

    /** Same file {@code policy}'s {@code GuardrailDefaults} reads; a shared seed resource, not Java. */
    private static GuardrailValues readDefaults(ObjectMapper json) {
        try (InputStream in = new ClassPathResource("seed/guardrails.json").getInputStream()) {
            record Raw(BigDecimal minMarginPct, BigDecimal maxDiscountPct, BigDecimal maxSpeedPremiumPct,
                    BigDecimal maxMarketDeviationPct) {
            }
            Raw raw = json.readValue(in, Raw.class);
            return new GuardrailValues(raw.minMarginPct().doubleValue(), raw.maxDiscountPct().doubleValue(),
                    raw.maxSpeedPremiumPct().doubleValue(), raw.maxMarketDeviationPct().doubleValue());
        } catch (IOException ex) {
            throw new UncheckedIOException("seed/guardrails.json is missing or unreadable", ex);
        }
    }
}
