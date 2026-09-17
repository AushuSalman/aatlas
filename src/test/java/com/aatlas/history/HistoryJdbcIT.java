package com.aatlas.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The read layer against a real PostgreSQL: a tenant signed up over HTTP, a handful of
 * rows in each fact table, and every reducer asserted against the numbers worked out by
 * hand below. What matters here cannot be unit-tested: the FILTER clauses, percentile_cont,
 * DISTINCT ON ordering, the partition function and the bind-parameter typing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HistoryJdbcIT extends PostgresIntegrationTest {

    static final String COPPER = "HRD118902";
    static final String VALVE = "HRD772310";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AatlasClock clock;
    @Autowired SalesHistory sales;
    @Autowired PurchaseHistory purchases;
    @Autowired PriceList priceList;
    @Autowired PriceLadder ladder;
    @Autowired Inventory inventory;
    @Autowired CompetitorPrices competitors;
    @Autowired BulkModelReader bulkModels;

    UUID tenantId;
    LocalDate today;
    UUID copper;
    UUID valve;
    UUID dallas;
    UUID houston;

    @BeforeAll
    void fixture() throws Exception {
        tenantId = signUp();
        today = clock.today();
        TenantContext.runAs(TenantContext.Actor.system(tenantId), this::insertFixture);
    }

    /** Signs a tenant up the way a browser does and reads its id from the token's {@code tid} claim. */
    private UUID signUp() throws Exception {
        String body = """
                {"fullName": "History Tester", "email": "history-%s@kestrelsupply.com", "password": "Zephyr!42Bridge",
                 "company": "History Co %s", "country": "US", "role": "both"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
        MvcResult result = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String token = json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        return UUID.fromString(json.readTree(payload).get("tid").asText());
    }

    // ---- the fixture ----------------------------------------------------------------------

    private void insertFixture() {
        dallas = store("100959", "Dallas-Fort Worth", "south", new BigDecimal("103.2"));
        houston = store("300201", "Houston", "west", null);
        copper = product(COPPER, "1/2 IN COPPER TYPE L HARD TUBE 10FT", "Plumbing", "Pipe & tube", "copper");
        valve = product(VALVE, "3/4 IN BRASS BALL VALVE FULL PORT THREADED", "Plumbing", "Valves", "brass");
        supplier("sup-1", "Cascade Copper Mills", "USA");
        supplier("sup-2", "Larsen Brass & Bronze", "USA");

        // Copper at Dallas: five priced lines, one costless, one free-of-charge line that carries a cost.
        sale(copper, dallas, today.minusDays(12), "40", "36.80", "21.55", "A");
        sale(copper, dallas, today.minusDays(48), "60", "36.40", "21.40", "B");
        sale(copper, dallas, today.minusDays(114), "50", "35.10", "21.25", "A");
        sale(copper, dallas, today.minusDays(180), "30", "34.20", null, "C");
        sale(copper, dallas, today.minusDays(285), "20", "33.80", "21.10", "B");
        sale(copper, dallas, today.minusDays(7), "5", "0", "21.55", "A");
        // Copper at Houston: three lines, enough for a store median.
        sale(copper, houston, today.minusDays(22), "10", "37.00", null, "A");
        sale(copper, houston, today.minusDays(73), "12", "36.00", null, "D");
        sale(copper, houston, today.minusDays(153), "8", "35.50", null, "A");
        // The valve at Dallas.
        sale(valve, dallas, today.minusDays(30), "15", "20.50", "12.00", "A");
        sale(valve, dallas, today.minusDays(100), "10", "20.00", "11.80", "B");
        sale(valve, dallas, today.minusDays(200), "12", "19.50", "11.80", "C");

        purchase(1, copper, dallas, "sup-1", "Cascade Copper Mills", today.minusDays(22), 100, "21.20",
                today.minusDays(10), today.minusDays(12), true);
        purchase(2, copper, dallas, "sup-1", "Cascade Copper Mills", today.minusDays(62), 200, "21.00",
                today.minusDays(51), today.minusDays(48), false);
        purchase(3, copper, houston, "sup-2", "Larsen Brass & Bronze", today.minusDays(212), 50, "22.00",
                today.minusDays(198), today.minusDays(200), true);

        price(valve, null, "21.50", "12.00", today.minusDays(31), "import");
        price(valve, dallas, "22.00", null, today.minusDays(17), "manual");

        jdbc.update("INSERT INTO inventory_positions (tenant_id, product_id, store_id, on_hand, as_of) VALUES (?, ?, ?, ?, ?)",
                tenantId, copper, dallas, new BigDecimal("240"), today.minusDays(4));

        competitor(copper, "Northline Supply", "38.00", "south", today.minusDays(31));
        competitor(copper, "Brightwell Distribution", "36.00", "west", today.minusDays(27));
        competitor(copper, "Summit Pipe & Supply", "37.50", "south", today.minusDays(43));
        competitor(copper, "Meridian Plumbing Wholesale", "30.00", "south", today.minusDays(275));
    }

    private UUID store(String code, String msa, String region, BigDecimal rpp) {
        return jdbc.queryForObject("""
                INSERT INTO stores (tenant_id, store_code, legal_name, country, msa_name, region_key, rpp, source)
                VALUES (?, ?, ?, 'US', ?, ?, ?, 'import') RETURNING id
                """, UUID.class, tenantId, code, "Branch " + code, msa, region, rpp);
    }

    private UUID product(String item, String description, String category, String subcategory, String commodity) {
        return jdbc.queryForObject("""
                INSERT INTO products (tenant_id, item_number, description, short_name, category, subcategory, commodity,
                                      unit, has_sales, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'each', true, 'import') RETURNING id
                """, UUID.class, tenantId, item, description, description.substring(0, Math.min(40, description.length())),
                category, subcategory, commodity);
    }

    private void supplier(String key, String name, String country) {
        jdbc.update("""
                INSERT INTO suppliers (tenant_id, supplier_key, vendor_code, name, country, contact_name, email, currency,
                                       is_custom, source)
                VALUES (?, ?, '', ?, ?, '', '', 'USD', false, 'po_import')
                """, tenantId, key, name, country);
    }

    private void sale(UUID productId, UUID storeId, LocalDate date, String qty, String price, String cost, String customer) {
        jdbc.query("select app.ensure_month_partition('sales_transactions', ?)", (ResultSetExtractor<Void>) rs -> null, date);
        jdbc.update("""
                INSERT INTO sales_transactions (tenant_id, txn_date, product_id, store_id, item_number, qty, unit_price,
                                                unit_cost, customer_code, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'import')
                """, ps -> {
            ps.setObject(1, tenantId);
            ps.setObject(2, java.sql.Date.valueOf(date));
            ps.setObject(3, productId);
            ps.setObject(4, storeId);
            ps.setString(5, productId.equals(copper) ? COPPER : VALVE);
            ps.setBigDecimal(6, new BigDecimal(qty));
            ps.setBigDecimal(7, new BigDecimal(price));
            if (cost == null) {
                ps.setNull(8, Types.NUMERIC);
            } else {
                ps.setBigDecimal(8, new BigDecimal(cost));
            }
            ps.setString(9, customer);
        });
    }

    private void purchase(int seq, UUID productId, UUID storeId, String supplierKey, String supplierName, LocalDate ordered,
            int qty, String landed, LocalDate promised, LocalDate received, boolean onTime) {
        BigDecimal landedCost = new BigDecimal(landed);
        BigDecimal spend = landedCost.multiply(BigDecimal.valueOf(qty));
        jdbc.update("""
                INSERT INTO purchase_order (tenant_id, seq, po_number, order_date, supplier_id, supplier_name, country,
                    item_number, description, category, branch_id, branch_name, region_key, region_label, qty,
                    ex_works, freight, duty, landed, baseline, target, followed, spend, baseline_spend, saved, leaked,
                    status, promised_days, actual_days, days_late, on_time, promised_date, received_date,
                    source, product_id, store_id, cost_basis)
                VALUES (?, ?, ?, ?, ?, ?, 'USA', ?, '', 'Plumbing', ?, ?, 'south', 'South', ?,
                        ?, 0, 0, ?, ?, ?, true, ?, ?, 0, 0,
                        'received', ?, ?, ?, ?, ?, ?, 'import', ?, ?, 'file')
                """, tenantId, seq, "IMP-TEST-" + seq, ordered, supplierKey, supplierName, COPPER,
                storeId.equals(dallas) ? "100959" : "300201", storeId.equals(dallas) ? "Dallas" : "Houston", qty,
                landedCost, landedCost, landedCost, landedCost, spend, spend,
                (int) java.time.temporal.ChronoUnit.DAYS.between(ordered, promised),
                (int) java.time.temporal.ChronoUnit.DAYS.between(ordered, received),
                (int) Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(promised, received)), onTime, promised, received,
                productId, storeId);
    }

    private void price(UUID productId, UUID storeId, String list, String cost, LocalDate from, String source) {
        jdbc.update("""
                INSERT INTO product_prices (tenant_id, product_id, store_id, list_price, cost, currency, effective_from, source)
                VALUES (?, ?, ?, ?, ?, 'USD', ?, ?)
                """, tenantId, productId, storeId, list == null ? null : new BigDecimal(list),
                cost == null ? null : new BigDecimal(cost), from, source);
    }

    private void competitor(UUID productId, String name, String price, String region, LocalDate observed) {
        jdbc.update("""
                INSERT INTO competitor_prices (tenant_id, product_id, competitor, price, currency, region_key, observed_at)
                VALUES (?, ?, ?, ?, 'USD', ?, ?)
                """, tenantId, productId, name, new BigDecimal(price), region, observed);
    }

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        return TenantContext.runAs(TenantContext.Actor.system(tenantId), work);
    }

    // ---- sales ---------------------------------------------------------------------------

    @Test
    @DisplayName("itemStore reduces the pair: priced rows for prices, costed rows for margin, every row for units")
    void itemStoreStats() {
        SalesStats s = asTenant(() -> sales.itemStore(copper, dallas, Window.trailingMonths(today, 12)));

        assertThat(s.txns()).isEqualTo(6);
        assertThat(s.units()).isEqualByComparingTo("205");
        assertThat(s.revenue()).isEqualByComparingTo("7113.00");
        assertThat(s.cogs()).isEqualByComparingTo("3738.25");
        assertThat(s.costedUnits()).isEqualByComparingTo("175");
        assertThat(s.costedRevenue()).isEqualByComparingTo("6087.00");
        assertThat(s.customers()).isEqualTo(3);
        assertThat(s.avgPrice()).isEqualByComparingTo("35.565");
        assertThat(s.lastPrice()).isEqualByComparingTo("36.56");
        assertThat(s.lastPriceSource()).isEqualTo("sales-90d");
        assertThat(s.minPrice()).isEqualByComparingTo("33.80");
        assertThat(s.maxPrice()).isEqualByComparingTo("36.80");
        assertThat(s.avgCost()).isEqualByComparingTo("21.3614");
        assertThat(s.lastCost()).isEqualByComparingTo("21.4643");
        assertThat(s.lastCostSource()).isEqualTo("sales-90d");
        assertThat(s.grossMarginPct().doubleValue()).isCloseTo(38.586, within(0.001));
        assertThat(s.belowCostTxns()).isEqualTo(1);
        assertThat(s.zeroPriceTxns()).isEqualTo(1);
        assertThat(s.firstSale()).isEqualTo(today.minusDays(285));
        assertThat(s.lastSale()).isEqualTo(today.minusDays(7));
        assertThat(s.costCoveragePct()).isEqualByComparingTo("85.4");

        SalesStats none = asTenant(() -> sales.itemStore(valve, houston, Window.trailingMonths(today, 12)));
        assertThat(none.any()).isFalse();
        assertThat(asTenant(sales::hasHistory)).isTrue();
        assertThat(asTenant(sales::coverage).rows()).isEqualTo(12);
        assertThat(asTenant(sales::coverage).stores()).isEqualTo(2);
    }

    @Test
    @DisplayName("priceBand is percentile_cont over priced lines, with store medians and quartile buckets")
    void priceBand() {
        Optional<SalesHistory.PriceBand> band = asTenant(() -> sales.priceBand(copper, Window.trailingMonths(today, 12)));

        assertThat(band).isPresent();
        SalesHistory.PriceBand b = band.get();
        assertThat(b.n()).isEqualTo(8);
        assertThat(b.min()).isEqualByComparingTo("33.80");
        assertThat(b.q1()).isEqualByComparingTo("34.875");
        assertThat(b.median()).isEqualByComparingTo("35.75");
        assertThat(b.q3()).isEqualByComparingTo("36.50");
        assertThat(b.max()).isEqualByComparingTo("37.00");
        assertThat(b.storeMedians()).hasSize(2);
        assertThat(b.storeMedians().get(0).storeCode()).isEqualTo("100959");
        assertThat(b.storeMedians().get(0).median()).isEqualByComparingTo("35.10");
        assertThat(b.storeMedians().get(0).n()).isEqualTo(5);
        assertThat(b.storeMedians().get(1).median()).isEqualByComparingTo("36.00");
        assertThat(b.buckets()).extracting(SalesHistory.Bucket::n).containsExactly(2L, 2L, 2L, 2L);

        Optional<SalesHistory.PeerBand> peer = asTenant(() -> sales.peerBand(copper, dallas, Window.trailingMonths(today, 12)));
        assertThat(peer).isPresent();
        assertThat(peer.get().q2()).isEqualByComparingTo("36.00");
        assertThat(peer.get().stores()).isEqualTo(1);

        // Fewer than three priced lines: no band.
        assertThat(asTenant(() -> sales.priceBandAtStore(valve, houston, Window.trailingMonths(today, 12)))).isEmpty();
    }

    @Test
    @DisplayName("velocity compares the trailing 90 days with the 90 before")
    void velocity() {
        SalesHistory.Velocity v = asTenant(() -> sales.velocity(copper, dallas, today));

        assertThat(v.recentTxns()).isEqualTo(3);
        assertThat(v.priorTxns()).isEqualTo(1);
        assertThat(v.recentPerWeek()).isEqualByComparingTo("8.1667");
        assertThat(v.priorPerWeek()).isEqualByComparingTo("3.8889");
        assertThat(v.trendPct()).isEqualByComparingTo("110");
        assertThat(v.historyDays()).isEqualTo(285);
        assertThat(v.volumePercentile()).isNull();
    }

    @Test
    @DisplayName("monthly fills the gaps with zero rows and prices only the priced units")
    void monthly() {
        List<SalesHistory.MonthPoint> points = asTenant(() -> sales.monthly(copper, dallas, 6, today));

        assertThat(points).hasSize(6);
        assertThat(points.get(0).month()).isEqualTo(today.withDayOfMonth(1).minusMonths(5));
        assertThat(points.get(5).month()).isEqualTo(today.withDayOfMonth(1));
        SalesHistory.MonthPoint august = points.stream()
                .filter(p -> p.month().equals(today.minusDays(12).withDayOfMonth(1))).findFirst().orElseThrow();
        assertThat(august.units()).isEqualByComparingTo("45");
        assertThat(august.revenue()).isEqualByComparingTo("1472.00");
        assertThat(august.txns()).isEqualTo(2);
        assertThat(august.avgPrice()).isEqualByComparingTo("36.80");
        assertThat(august.avgCost()).isEqualByComparingTo("21.55");
        long empty = points.stream().filter(p -> p.units().signum() == 0).count();
        assertThat(empty).isEqualTo(3);
        assertThat(points.stream().filter(p -> p.units().signum() == 0)).allMatch(p -> p.avgPrice() == null);
    }

    // ---- purchases -----------------------------------------------------------------------

    @Test
    @DisplayName("item purchases are shared by supplier and the incumbent is the largest share")
    void purchases() {
        PurchaseHistory.ItemPurchases item = asTenant(() -> purchases.item(COPPER, Window.trailingMonths(today, 12)));

        assertThat(item.all().pos()).isEqualTo(3);
        assertThat(item.all().received()).isEqualTo(3);
        assertThat(item.all().units()).isEqualByComparingTo("350");
        assertThat(item.all().spend()).isEqualByComparingTo("7420.00");
        assertThat(item.all().avgLanded()).isEqualByComparingTo("21.2");
        assertThat(item.all().lastLanded()).isEqualByComparingTo("21.20");
        assertThat(item.all().otifMeasurable()).isEqualTo(3);
        assertThat(item.all().otifPct()).isEqualByComparingTo("66.7");
        assertThat(item.all().inFullPct()).isEqualByComparingTo("100.0");
        assertThat(item.all().avgLeadDays()).isEqualByComparingTo("12.0");
        assertThat(item.suppliers()).hasSize(2);
        assertThat(item.suppliers().get(0).supplierKey()).isEqualTo("sup-1");
        assertThat(item.suppliers().get(0).name()).isEqualTo("Cascade Copper Mills");
        assertThat(item.suppliers().get(0).sharePct()).isEqualByComparingTo("85.2");
        assertThat(item.suppliers().get(0).stats().avgLanded()).isEqualByComparingTo("21.0667");

        Optional<PurchaseHistory.SupplierShare> incumbent = asTenant(() -> purchases.incumbent(COPPER, today));
        assertThat(incumbent).isPresent();
        assertThat(incumbent.get().supplierKey()).isEqualTo("sup-1");

        assertThat(asTenant(() -> purchases.incumbent(VALVE, today))).isEmpty();
        assertThat(asTenant(purchases::coverage).rows()).isEqualTo(3);
        assertThat(asTenant(() -> purchases.itemStats(Window.trailingMonths(today, 12)))).containsOnlyKeys(COPPER);
    }

    // ---- price list, ladder, stock, competitors ------------------------------------------

    @Test
    @DisplayName("the current price is the latest effective row per component, store-specific winning")
    void priceListCurrent() {
        Optional<PriceList.CurrentPrice> atDallas = asTenant(() -> priceList.current(valve, dallas, today));
        assertThat(atDallas).isPresent();
        assertThat(atDallas.get().listPrice()).isEqualByComparingTo("22.00");
        assertThat(atDallas.get().listPriceSource()).isEqualTo("manual");
        assertThat(atDallas.get().listPriceStoreSpecific()).isTrue();
        assertThat(atDallas.get().cost()).isEqualByComparingTo("12.00");
        assertThat(atDallas.get().costSource()).isEqualTo("import");
        assertThat(atDallas.get().costStoreSpecific()).isFalse();

        Optional<PriceList.CurrentPrice> tenantWide = asTenant(() -> priceList.current(valve, null, today));
        assertThat(tenantWide).isPresent();
        assertThat(tenantWide.get().listPrice()).isEqualByComparingTo("21.50");

        assertThat(asTenant(() -> priceList.current(copper, dallas, today))).isEmpty();
        Map<UUID, PriceList.CurrentPrice> forDallas = asTenant(() -> priceList.currentForStore(dallas, today));
        assertThat(forDallas).containsOnlyKeys(valve);
        assertThat(forDallas.get(valve).listPrice()).isEqualByComparingTo("22.00");
        assertThat(asTenant(() -> priceList.coverage(today)).itemsWithCost()).isEqualTo(1);
    }

    @Test
    @DisplayName("the ladder labels every figure: sales-90d for the price, purchases-90d for the cost, competitor for the anchor")
    void ladder() {
        Optional<Resolved> price = asTenant(() -> ladder.currentPrice(copper, dallas, today));
        assertThat(price).isPresent();
        assertThat(price.get().value()).isEqualByComparingTo("36.56");
        assertThat(price.get().source()).isEqualTo(Resolved.SALES_90D);

        Optional<Resolved> cost = asTenant(() -> ladder.cost(copper, dallas, today));
        assertThat(cost).isPresent();
        assertThat(cost.get().value()).isEqualByComparingTo("21.0667");
        assertThat(cost.get().source()).isEqualTo(Resolved.PURCHASES_90D);
        assertThat(cost.get().asOf()).isEqualTo(today.minusDays(22));

        Optional<Anchor> anchor = asTenant(() -> ladder.anchor(copper, dallas, today));
        assertThat(anchor).isPresent();
        assertThat(anchor.get().source()).isEqualTo(Anchor.COMPETITOR);
        assertThat(anchor.get().value()).isEqualByComparingTo("37.75");
        assertThat(anchor.get().observations()).isEqualTo(2);

        // The valve: a store-specific list price, a tenant-wide cost, no purchases.
        Optional<Resolved> valvePrice = asTenant(() -> ladder.currentPrice(valve, dallas, today));
        assertThat(valvePrice).isPresent();
        assertThat(valvePrice.get().value()).isEqualByComparingTo("22.00");
        assertThat(valvePrice.get().source()).isEqualTo(Resolved.PRICE_LIST);
        Optional<Resolved> valveCost = asTenant(() -> ladder.cost(valve, dallas, today));
        assertThat(valveCost).isPresent();
        assertThat(valveCost.get().value()).isEqualByComparingTo("12.00");
        assertThat(valveCost.get().source()).isEqualTo(Resolved.PRICE_LIST);

        // Bulk agrees with single-pair, rung for rung.
        Map<UUID, Resolved> prices = asTenant(() -> ladder.currentPrices(dallas, today));
        assertThat(prices.get(copper).value()).isEqualByComparingTo("36.56");
        assertThat(prices.get(copper).source()).isEqualTo(Resolved.SALES_90D);
        assertThat(prices.get(valve).value()).isEqualByComparingTo("22.00");
        Map<UUID, Resolved> costs = asTenant(() -> ladder.costs(dallas, today));
        assertThat(costs.get(copper).value()).isEqualByComparingTo("21.0667");
        assertThat(costs.get(copper).source()).isEqualTo(Resolved.PURCHASES_90D);
        assertThat(costs.get(valve).value()).isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("stock on hand and competitor medians read back as written")
    void inventoryAndCompetitors() {
        Optional<Inventory.OnHand> onHand = asTenant(() -> inventory.onHand(copper, dallas));
        assertThat(onHand).isPresent();
        assertThat(onHand.get().units()).isEqualByComparingTo("240");
        assertThat(onHand.get().asOf()).isEqualTo(today.minusDays(4));
        assertThat(onHand.get().stale(today)).isFalse();
        assertThat(asTenant(() -> inventory.onHand(valve, dallas))).isEmpty();
        assertThat(asTenant(inventory::latestAsOf)).contains(today.minusDays(4));

        // Region-matched first: the two south observations; the 275-day-old one is out of range.
        Optional<Anchor> south = asTenant(() -> competitors.anchor(copper, "south", dallas, today));
        assertThat(south).isPresent();
        assertThat(south.get().value()).isEqualByComparingTo("37.75");
        assertThat(south.get().observations()).isEqualTo(2);
        Optional<Anchor> anywhere = asTenant(() -> competitors.anchor(copper, null, null, today));
        assertThat(anywhere).isPresent();
        assertThat(anywhere.get().value()).isEqualByComparingTo("37.50");
        assertThat(anywhere.get().observations()).isEqualTo(3);
        List<CompetitorPrices.Observation> observed = asTenant(() -> competitors.forItem(copper, "south", null, today));
        assertThat(observed).hasSize(3);
        assertThat(observed.stream().filter(CompetitorPrices.Observation::matched)).hasSize(2);
        assertThat(asTenant(() -> competitors.medians(today)).get(copper).value()).isEqualByComparingTo("37.50");
        assertThat(asTenant(() -> competitors.coverage(today)).observations()).isEqualTo(3);
    }

    // ---- bulk model ------------------------------------------------------------------------

    @Test
    @DisplayName("the bulk model answers every pair at a store from a fixed handful of queries")
    void bulkModel() {
        BulkModelReader.BulkModel model = asTenant(() -> bulkModels.bulkModel(dallas, today));

        assertThat(model.today()).isEqualTo(today);
        assertThat(model.hasInventory()).isTrue();
        assertThat(model.inventoryAsOf()).isEqualTo(today.minusDays(4));
        assertThat(model.coverage().rows()).isEqualTo(12);
        assertThat(model.pairs()).hasSize(2);

        BulkModelReader.PairModel copperPair = model.pairs().stream()
                .filter(p -> p.itemNumber().equals(COPPER)).findFirst().orElseThrow();
        assertThat(copperPair.storeCode()).isEqualTo("100959");
        assertThat(copperPair.storeLabel()).isEqualTo("Dallas #100959");
        assertThat(copperPair.regionKey()).isEqualTo("south");
        assertThat(copperPair.rpp()).isEqualByComparingTo("103.2");
        assertThat(copperPair.w12().units()).isEqualByComparingTo("205");
        assertThat(copperPair.w12prior().any()).isFalse();
        assertThat(copperPair.units90()).isEqualByComparingTo("105");
        assertThat(copperPair.unitsPrior90()).isEqualByComparingTo("50");
        assertThat(copperPair.avgPrice30()).isEqualByComparingTo("36.80");
        assertThat(copperPair.volumePercentile()).isNotNull();
        assertThat(copperPair.band().median()).isEqualByComparingTo("35.75");
        assertThat(copperPair.peer().q2()).isEqualByComparingTo("36.00");
        assertThat(copperPair.currentPrice().value()).isEqualByComparingTo("36.56");
        assertThat(copperPair.currentPrice().source()).isEqualTo(Resolved.SALES_90D);
        assertThat(copperPair.cost().value()).isEqualByComparingTo("21.0667");
        assertThat(copperPair.cost().source()).isEqualTo(Resolved.PURCHASES_90D);
        assertThat(copperPair.competitor().value()).isEqualByComparingTo("37.50");
        assertThat(copperPair.competitor().observations()).isEqualTo(3);
        assertThat(copperPair.onHand().units()).isEqualByComparingTo("240");
        assertThat(copperPair.purchases12m().pos()).isEqualTo(3);

        BulkModelReader.PairModel valvePair = model.pairs().stream()
                .filter(p -> p.itemNumber().equals(VALVE)).findFirst().orElseThrow();
        assertThat(valvePair.currentPrice().value()).isEqualByComparingTo("22.00");
        assertThat(valvePair.currentPrice().source()).isEqualTo(Resolved.PRICE_LIST);
        assertThat(valvePair.cost().value()).isEqualByComparingTo("12.00");
        assertThat(valvePair.onHand()).isNull();
        assertThat(valvePair.purchases12m()).isNull();

        // Tenant-wide: the valve's tenant-wide row puts it at Houston too, with no sales there.
        BulkModelReader.BulkModel all = asTenant(() -> bulkModels.bulkModel(null, today));
        assertThat(all.pairs()).hasSize(4);
        BulkModelReader.PairModel valveAtHouston = all.pairs().stream()
                .filter(p -> p.itemNumber().equals(VALVE) && houston.equals(p.storeId())).findFirst().orElseThrow();
        assertThat(valveAtHouston.w12().any()).isFalse();
        assertThat(valveAtHouston.currentPrice().value()).isEqualByComparingTo("21.50");
        assertThat(valveAtHouston.currentPrice().source()).isEqualTo(Resolved.PRICE_LIST);
    }
}
