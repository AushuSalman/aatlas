package com.aatlas.sell.internal.buy;

import com.aatlas.common.tenant.TenantContext;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class BuySupplierGatewayImpl implements BuySupplierGateway {

    private final JdbcTemplate jdbc;

    BuySupplierGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SupplierRef> seededPanel() {
        return jdbc.query(
                """
                select supplier_key, name, otif_pct, price_index
                from suppliers where tenant_id = ? and is_custom = false
                order by supplier_key
                """,
                (rs, rowNum) -> new SupplierRef(
                        rs.getString("supplier_key"), rs.getString("name"),
                        rs.getDouble("otif_pct"), rs.getDouble("price_index")),
                TenantContext.requireTenantId());
    }
}
