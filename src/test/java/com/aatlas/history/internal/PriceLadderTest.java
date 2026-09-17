package com.aatlas.history.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aatlas.history.Catalogue;
import com.aatlas.history.CompetitorPrices;
import com.aatlas.history.PriceList;
import com.aatlas.history.PurchaseHistory;
import com.aatlas.history.Reference;
import com.aatlas.history.Resolved;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.SalesStats;
import com.aatlas.history.Suppliers;
import com.aatlas.history.Window;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the rung order and the labels of the truth ladder with fakes underneath: price-list,
 * sales-90d, sales-12m, sales-item-12m, empty for the price; purchases-90d, price-list,
 * sales-cost, supplier-list, empty for the cost.
 */
class PriceLadderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();
    private static final String ITEM = "HRD118902";

    private PriceList priceList;
    private SalesHistory sales;
    private PurchaseHistory purchases;
    private Suppliers suppliers;
    private Catalogue catalogue;
    private Reference reference;
    private CompetitorPrices competitors;
    private PriceLadderImpl ladder;

    @BeforeEach
    void wire() {
        priceList = mock(PriceList.class);
        sales = mock(SalesHistory.class);
        purchases = mock(PurchaseHistory.class);
        suppliers = mock(Suppliers.class);
        catalogue = mock(Catalogue.class);
        reference = mock(Reference.class);
        competitors = mock(CompetitorPrices.class);
        ladder = new PriceLadderImpl(priceList, sales, purchases, suppliers, catalogue, reference, competitors, null);

        when(priceList.current(any(), any(), any())).thenReturn(Optional.empty());
        when(sales.itemStore(any(), any(), any())).thenReturn(SalesStats.empty());
        when(sales.item(any(), any())).thenReturn(SalesStats.empty());
        when(purchases.itemAtStore(any(), any(), any())).thenReturn(PurchaseHistory.PoStats.empty());
        when(purchases.item(any(), any()))
                .thenReturn(new PurchaseHistory.ItemPurchases(ITEM, PurchaseHistory.PoStats.empty(), List.of()));
        when(suppliers.panelFor(any())).thenReturn(List.of());
        when(catalogue.productById(PRODUCT)).thenReturn(Optional.of(new Catalogue.ProductRef(PRODUCT, ITEM,
                "1/2 IN COPPER TYPE L HARD TUBE 10FT", "1/2 IN COPPER TUBE", "Plumbing", "Pipe & tube", "copper",
                "each", true, null, "import")));
        when(reference.origin(any())).thenReturn(new Reference.Origin("USA", "domestic", "domestic", "domestic truck",
                BigDecimal.ZERO, 0, BigDecimal.ZERO, "Domestic - no duty", false));
    }

    // ---- helpers -------------------------------------------------------------------------

    private static SalesStats stats(BigDecimal lastPrice, String priceSource, BigDecimal lastCost, String costSource) {
        return new SalesStats(5, BigDecimal.TEN, BigDecimal.valueOf(300), null, BigDecimal.ZERO, BigDecimal.ZERO, 2,
                lastPrice, lastPrice, priceSource, lastPrice, lastPrice, lastCost, lastCost, costSource, null, 0, 0,
                TODAY.minusDays(200), TODAY.minusDays(3));
    }

    private static PriceList.CurrentPrice listRow(BigDecimal list, BigDecimal cost) {
        return new PriceList.CurrentPrice(list, list == null ? null : "manual", TODAY.minusDays(10), true,
                cost, cost == null ? null : "import", TODAY.minusDays(20), false, null);
    }

    private static PurchaseHistory.PoStats bought(BigDecimal avgLanded) {
        return new PurchaseHistory.PoStats(3, 3, BigDecimal.valueOf(300), avgLanded.multiply(BigDecimal.valueOf(300)),
                avgLanded, avgLanded, avgLanded, avgLanded, TODAY.minusDays(60), TODAY.minusDays(12), null, null, 0,
                null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    // ---- current price -------------------------------------------------------------------

    @Test
    @DisplayName("price-list wins over every sales rung")
    void priceListFirst() {
        when(priceList.current(PRODUCT, STORE, TODAY)).thenReturn(Optional.of(listRow(new BigDecimal("22.00"), null)));
        when(sales.itemStore(eq(PRODUCT), eq(STORE), any()))
                .thenReturn(stats(new BigDecimal("36.56"), "sales-90d", null, null));

        Optional<Resolved> price = ladder.currentPrice(PRODUCT, STORE, TODAY);

        assertThat(price).isPresent();
        assertThat(price.get().source()).isEqualTo(Resolved.PRICE_LIST);
        assertThat(price.get().value()).isEqualByComparingTo("22.00");
        assertThat(price.get().asOf()).isEqualTo(TODAY.minusDays(10));
    }

    @Test
    @DisplayName("then the store's trailing-90-day price, then its twelve-month average")
    void salesRungsInOrder() {
        when(sales.itemStore(eq(PRODUCT), eq(STORE), any()))
                .thenReturn(stats(new BigDecimal("36.56"), "sales-90d", null, null));
        Optional<Resolved> recent = ladder.currentPrice(PRODUCT, STORE, TODAY);
        assertThat(recent).isPresent();
        assertThat(recent.get().source()).isEqualTo(Resolved.SALES_90D);
        assertThat(recent.get().value()).isEqualByComparingTo("36.56");

        when(sales.itemStore(eq(PRODUCT), eq(STORE), any()))
                .thenReturn(stats(new BigDecimal("35.83"), "sales-12m", null, null));
        Optional<Resolved> yearly = ladder.currentPrice(PRODUCT, STORE, TODAY);
        assertThat(yearly).isPresent();
        assertThat(yearly.get().source()).isEqualTo(Resolved.SALES_12M);
    }

    @Test
    @DisplayName("a store with no sales of the item falls back to the item across every branch")
    void itemLevelFallbackOnlyWithAStore() {
        when(sales.item(eq(PRODUCT), any())).thenReturn(stats(new BigDecimal("35.50"), "sales-12m", null, null));

        Optional<Resolved> atStore = ladder.currentPrice(PRODUCT, STORE, TODAY);
        assertThat(atStore).isPresent();
        assertThat(atStore.get().source()).isEqualTo(Resolved.SALES_ITEM_12M);
        assertThat(atStore.get().value()).isEqualByComparingTo("35.50");

        // Tenant-wide the item level is already the second rung, labelled as such.
        Optional<Resolved> tenantWide = ladder.currentPrice(PRODUCT, null, TODAY);
        assertThat(tenantWide).isPresent();
        assertThat(tenantWide.get().source()).isEqualTo(Resolved.SALES_12M);
    }

    @Test
    @DisplayName("nothing on file is empty, not zero")
    void emptyPrice() {
        assertThat(ladder.currentPrice(PRODUCT, STORE, TODAY)).isEmpty();
        assertThat(ladder.currentPrice(PRODUCT, null, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("a zero list price is absent")
    void zeroListPriceIsAbsent() {
        when(priceList.current(PRODUCT, STORE, TODAY)).thenReturn(Optional.of(listRow(BigDecimal.ZERO, null)));
        assertThat(ladder.currentPrice(PRODUCT, STORE, TODAY)).isEmpty();
    }

    // ---- cost ----------------------------------------------------------------------------

    @Test
    @DisplayName("purchases in the last 90 days come first, the branch before any branch")
    void purchasesFirst() {
        when(purchases.itemAtStore(eq(ITEM), eq(STORE), any())).thenReturn(bought(new BigDecimal("21.07")));
        when(purchases.item(eq(ITEM), any())).thenReturn(new PurchaseHistory.ItemPurchases(ITEM,
                bought(new BigDecimal("21.50")), List.of()));
        when(priceList.current(PRODUCT, STORE, TODAY)).thenReturn(Optional.of(listRow(null, new BigDecimal("19.10"))));

        Optional<Resolved> cost = ladder.cost(PRODUCT, STORE, TODAY);

        assertThat(cost).isPresent();
        assertThat(cost.get().source()).isEqualTo(Resolved.PURCHASES_90D);
        assertThat(cost.get().value()).isEqualByComparingTo("21.07");
        assertThat(cost.get().asOf()).isEqualTo(TODAY.minusDays(12));

        when(purchases.itemAtStore(eq(ITEM), eq(STORE), any())).thenReturn(PurchaseHistory.PoStats.empty());
        Optional<Resolved> anyBranch = ladder.cost(PRODUCT, STORE, TODAY);
        assertThat(anyBranch).isPresent();
        assertThat(anyBranch.get().value()).isEqualByComparingTo("21.50");
    }

    @Test
    @DisplayName("then the price list's cost, then the cost on the sales lines, then the supplier list")
    void costRungsInOrder() {
        when(priceList.current(PRODUCT, STORE, TODAY)).thenReturn(Optional.of(listRow(null, new BigDecimal("19.10"))));
        when(sales.itemStore(eq(PRODUCT), eq(STORE), any()))
                .thenReturn(stats(null, null, new BigDecimal("21.40"), "sales-90d"));
        Suppliers.SupplierRef cascade = new Suppliers.SupplierRef(UUID.randomUUID(), "sup-2", "Cascade Copper Mills",
                "USA", "V-1", null, null, null, null, null, null, null, null, false, "sample");
        when(suppliers.panelFor(PRODUCT)).thenReturn(List.of(
                new Suppliers.SupplierLink(cascade, new BigDecimal("17.90"), "import", TODAY.minusDays(30), null, null)));

        Optional<Resolved> listed = ladder.cost(PRODUCT, STORE, TODAY);
        assertThat(listed).isPresent();
        assertThat(listed.get().source()).isEqualTo(Resolved.PRICE_LIST);
        assertThat(listed.get().value()).isEqualByComparingTo("19.10");

        when(priceList.current(PRODUCT, STORE, TODAY)).thenReturn(Optional.empty());
        Optional<Resolved> sold = ladder.cost(PRODUCT, STORE, TODAY);
        assertThat(sold).isPresent();
        assertThat(sold.get().source()).isEqualTo(Resolved.SALES_COST);
        assertThat(sold.get().value()).isEqualByComparingTo("21.40");

        when(sales.itemStore(eq(PRODUCT), eq(STORE), any())).thenReturn(SalesStats.empty());
        Optional<Resolved> quoted = ladder.cost(PRODUCT, STORE, TODAY);
        assertThat(quoted).isPresent();
        assertThat(quoted.get().source()).isEqualTo(Resolved.SUPPLIER_LIST);
        assertThat(quoted.get().value()).isEqualByComparingTo("17.90");
        assertThat(quoted.get().asOf()).isEqualTo(TODAY.minusDays(30));

        when(suppliers.panelFor(PRODUCT)).thenReturn(List.of());
        assertThat(ladder.cost(PRODUCT, STORE, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("the supplier-list rung lands the lowest quote through its origin's freight and duty")
    void supplierListAddsTheLane() {
        Suppliers.SupplierRef china = new Suppliers.SupplierRef(UUID.randomUUID(), "sup-3", "Anhui Precision Fittings",
                "China", "V-2", null, null, null, null, null, null, null, null, false, "sample");
        Suppliers.SupplierRef usa = new Suppliers.SupplierRef(UUID.randomUUID(), "sup-2", "Cascade Copper Mills",
                "USA", "V-1", null, null, null, null, null, null, null, null, false, "sample");
        when(reference.origin("China")).thenReturn(new Reference.Origin("China", "west", "ocean", "LA/LB",
                new BigDecimal("7.8"), 34, new BigDecimal("12.5"), "MFN plus 301", false));
        when(suppliers.panelFor(PRODUCT)).thenReturn(List.of(
                new Suppliers.SupplierLink(china, new BigDecimal("15.00"), "import", TODAY.minusDays(5), null, null),
                new Suppliers.SupplierLink(usa, new BigDecimal("17.90"), "purchases", TODAY.minusDays(30), null, null)));

        Optional<Resolved> cost = ladder.cost(PRODUCT, STORE, TODAY);

        // 15.00 × (1 + 0.078 + 0.125) = 18.045 > 17.90, so the domestic quote wins despite the higher ex-works.
        assertThat(cost).isPresent();
        assertThat(cost.get().value()).isEqualByComparingTo("17.90");
        assertThat(cost.get().source()).isEqualTo(Resolved.SUPPLIER_LIST);
    }

    @Test
    @DisplayName("the sales-cost rung falls back from the branch to the item")
    void salesCostFallsBackToItem() {
        when(sales.item(eq(PRODUCT), any())).thenReturn(stats(null, null, new BigDecimal("21.10"), "sales-12m"));

        Optional<Resolved> cost = ladder.cost(PRODUCT, STORE, TODAY);
        assertThat(cost).isPresent();
        assertThat(cost.get().source()).isEqualTo(Resolved.SALES_COST);
        assertThat(cost.get().value()).isEqualByComparingTo("21.10");
    }

    @Test
    @DisplayName("windows handed down are the trailing 90 days for purchases and twelve months for sales")
    void windowsAreTrailing() {
        when(purchases.itemAtStore(eq(ITEM), eq(STORE), eq(Window.trailingDays(TODAY, 90))))
                .thenReturn(bought(new BigDecimal("21.07")));
        assertThat(ladder.cost(PRODUCT, STORE, TODAY)).isPresent();

        when(sales.itemStore(eq(PRODUCT), eq(STORE), eq(Window.trailingMonths(TODAY, 12))))
                .thenReturn(stats(new BigDecimal("36.56"), "sales-90d", null, null));
        assertThat(ladder.currentPrice(PRODUCT, STORE, TODAY)).isPresent();
    }
}
