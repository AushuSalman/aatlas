package com.aatlas.sell.internal.catalog;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.history.Catalogue;
import com.aatlas.history.Catalogue.CustomerRef;
import com.aatlas.history.Catalogue.ProductRef;
import com.aatlas.history.Catalogue.RegionRef;
import com.aatlas.history.Catalogue.StoreRef;
import com.aatlas.history.Reference;
import com.aatlas.sell.internal.catalog.CatalogRefs.CommodityTrend;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Delegates to {@code history}'s catalogue and reference readers for everything they cover.
 *
 * <p>{@link #topCustomersFor} is the one query {@code history} does not expose (a per-item,
 * per-store customer breakdown for {@code AtpEngine}'s allocation lines): plain tenant-scoped
 * JDBC over {@code sales_transactions}/{@code customers}, the same pattern {@code
 * GuardrailsGatewayImpl} already uses for a table another module owns.
 */
@Repository
class CatalogGatewayImpl implements CatalogGateway {

    private final Catalogue catalogue;
    private final Reference reference;
    private final JdbcTemplate jdbc;

    CatalogGatewayImpl(Catalogue catalogue, Reference reference, JdbcTemplate jdbc) {
        this.catalogue = catalogue;
        this.reference = reference;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProductRef> findProduct(String itemNumber) {
        return catalogue.product(itemNumber);
    }

    @Override
    public List<ProductRef> sellableProducts() {
        return catalogue.products().stream().filter(ProductRef::hasSales).toList();
    }

    @Override
    public List<ProductRef> allProducts() {
        return catalogue.products();
    }

    @Override
    public Optional<StoreRef> findStore(String storeCode) {
        return catalogue.store(storeCode);
    }

    @Override
    public List<StoreRef> allStores() {
        return catalogue.stores();
    }

    @Override
    public Optional<CustomerRef> findCustomer(String code) {
        return catalogue.customer(code);
    }

    @Override
    public List<CustomerRef> allCustomers() {
        return catalogue.customers();
    }

    @Override
    public Optional<RegionRef> regionOf(String regionKey) {
        return catalogue.region(regionKey);
    }

    @Override
    public List<RegionRef> allRegions() {
        return catalogue.regions();
    }

    @Override
    public String tenantCountry() {
        return catalogue.country();
    }

    @Override
    public CommodityTrend commodityTrend(String commodityKey) {
        Reference.Commodity c = reference.commodity(commodityKey);
        double pct90 = c.pct90() == null ? 0 : c.pct90().doubleValue();
        return new CommodityTrend(pct90, c.label(), c.asOf());
    }

    @Override
    public List<TopCustomer> topCustomersFor(UUID productId, UUID storeId, int limit, LocalDate from, LocalDate to) {
        if (storeId == null) {
            return List.of();
        }
        return jdbc.query(
                """
                select c.id, c.code, c.name, c.segment, c.tier, c.agreed_discount_pct, c.typical_qty, c.profile,
                       c.sla_days, sum(st.qty) as units90, count(distinct st.txn_date) as orders90
                from sales_transactions st
                join customers c on c.id = st.customer_id
                where st.tenant_id = ? and st.product_id = ? and st.store_id = ?
                  and st.txn_date between ? and ?
                group by c.id, c.code, c.name, c.segment, c.tier, c.agreed_discount_pct, c.typical_qty, c.profile,
                         c.sla_days
                order by units90 desc
                limit ?
                """,
                (rs, rowNum) -> new TopCustomer(
                        new CustomerRef(rs.getObject("id", UUID.class), rs.getString("code"),
                                rs.getString("name"), rs.getString("segment"), rs.getString("tier"),
                                rs.getBigDecimal("agreed_discount_pct"), rs.getInt("typical_qty"),
                                rs.getString("profile"), rs.getInt("sla_days")),
                        rs.getBigDecimal("units90"), rs.getLong("orders90")),
                TenantContext.requireTenantId(), productId, storeId, from, to, limit);
    }
}
