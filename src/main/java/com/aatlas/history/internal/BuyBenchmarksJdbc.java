package com.aatlas.history.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.history.BuyBenchmarks;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code buy_market_benchmarks}, read. */
@Repository
class BuyBenchmarksJdbc implements BuyBenchmarks {

    private final JdbcTemplate jdbc;

    BuyBenchmarksJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Bulk> bulk(UUID productId, LocalDate today) {
        List<Bulk> rows = jdbc.query("""
                select median_per_unit, low_per_unit, listings, sources, observed_at
                  from buy_market_benchmarks
                 where tenant_id = ? and product_id = ? and kind = 'bulk-lots' and observed_at >= ?
                """, (rs, i) -> new Bulk(rs.getBigDecimal("median_per_unit"), rs.getBigDecimal("low_per_unit"),
                        rs.getInt("listings"), rs.getString("sources"), rs.getObject("observed_at", LocalDate.class)),
                TenantContext.requireTenantId(), productId, today.minusDays(MAX_AGE_DAYS));
        return rows.stream().findFirst();
    }
}
