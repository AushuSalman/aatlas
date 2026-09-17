package com.aatlas.history.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.history.Window;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * The small conventions every JDBC class in this package shares: the tenant is always bound
 * explicitly, every money column is read at scale 4, and a window's derived dates are bound
 * as precomputed {@link LocalDate}s (never arithmetic on a bind).
 */
final class Sql {

    static final int SCALE = 4;

    private Sql() {
    }

    static UUID tenant() {
        return TenantContext.requireTenantId();
    }

    /** {@code :t} plus every date a window derives: from, to, r0, r1, p0, d30. */
    static MapSqlParameterSource params(Window w) {
        return new MapSqlParameterSource()
                .addValue("t", tenant())
                .addValue("from", w.from())
                .addValue("to", w.to())
                .addValue("r0", w.r0())
                .addValue("r1", w.r1())
                .addValue("p0", w.p0())
                .addValue("d30", w.d30());
    }

    static MapSqlParameterSource params() {
        return new MapSqlParameterSource().addValue("t", tenant());
    }

    static BigDecimal money(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    static BigDecimal moneyOrZero(ResultSet rs, String column) throws SQLException {
        BigDecimal value = money(rs, column);
        return value == null ? BigDecimal.ZERO.setScale(SCALE) : value;
    }

    static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static Boolean bool(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    static BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
