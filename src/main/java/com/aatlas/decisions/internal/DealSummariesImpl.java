package com.aatlas.decisions.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.decisions.DealSummaries;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DealSummariesImpl implements DealSummaries {

    private final JdbcTemplate jdbc;

    DealSummariesImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Adoption adoption(String side, LocalDate from, LocalDate to, String storeCodeOrNull) {
        UUID tenantId = TenantContext.requireTenantId();
        String sql = """
                select count(*) as total, count(*) filter (where followed) as followed
                  from deal
                 where tenant_id = ? and side = ? and deal_date between ? and ?
                """;
        if (storeCodeOrNull == null) {
            return jdbc.queryForObject(sql, (rs, i) -> new Adoption(rs.getInt("total"), rs.getInt("followed")),
                    tenantId, side, from, to);
        }
        return jdbc.queryForObject(sql + " and destination_id = ?",
                (rs, i) -> new Adoption(rs.getInt("total"), rs.getInt("followed")),
                tenantId, side, from, to, storeCodeOrNull);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalDate> latestDeal() {
        UUID tenantId = TenantContext.requireTenantId();
        LocalDate latest = jdbc.query("select max(deal_date) from deal where tenant_id = ?",
                rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null, tenantId);
        return Optional.ofNullable(latest);
    }
}
