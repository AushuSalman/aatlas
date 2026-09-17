package com.aatlas.sell.internal.engine;

import static com.aatlas.sell.internal.engine.Round.round1;
import static com.aatlas.sell.internal.engine.Round.round2;
import static com.aatlas.sell.internal.engine.Wire.bd;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.Catalogue.ProductRef;
import com.aatlas.history.PurchaseHistory;
import com.aatlas.history.PurchaseHistory.PoMonth;
import com.aatlas.history.PurchaseHistory.SupplierShare;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.SalesHistory.CoPurchase;
import com.aatlas.history.SalesHistory.MonthPoint;
import com.aatlas.history.Stats;
import com.aatlas.history.Stats.Ols;
import com.aatlas.history.Suppliers;
import com.aatlas.history.Suppliers.SupplierRef;
import com.aatlas.history.Window;
import com.aatlas.sell.internal.catalog.CatalogGateway;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.CrossItemDto;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.ElasticityModelDto;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.ExperimentRowDto;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.ResponsePointDto;
import com.aatlas.sell.internal.dto.PricingDtos.CalcStepDto;
import com.aatlas.sell.internal.dto.PricingDtos.FactorWeightDto;
import com.aatlas.sell.internal.engine.PricingTypes.PricingModel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Port of {@code src/lib/platform/elasticity.ts}: {@code getElasticityModel}, both sides,
 * now real - sell over {@code history.SalesHistory.elasticity} (already resolved on the
 * pricing model), buy over an own-price fit of the supplier's monthly landed cost against
 * the quantity it moved.
 */
@Component
public class ElasticityEngine {

    private final CatalogGateway catalog;
    private final PricingEngine pricing;
    private final SalesHistory sales;
    private final PurchaseHistory purchases;
    private final Suppliers suppliers;
    private final AatlasClock clock;

    public ElasticityEngine(CatalogGateway catalog, PricingEngine pricing, SalesHistory sales,
            PurchaseHistory purchases, Suppliers suppliers, AatlasClock clock) {
        this.catalog = catalog;
        this.pricing = pricing;
        this.sales = sales;
        this.purchases = purchases;
        this.suppliers = suppliers;
        this.clock = clock;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public ElasticityModelDto getElasticityModel(String itemNumber, String side, String counterpartyId) {
        Optional<ProductRef> productOpt = catalog.findProduct(itemNumber);
        String description = productOpt.map(ProductRef::description).orElse(itemNumber);
        if (productOpt.isEmpty()) {
            return new ElasticityModelDto(itemNumber, description, side, "—", false, null, 0,
                    List.of(), "—", false, List.of(), List.of(), List.of(), List.of(), List.of());
        }
        if ("buy".equals(side)) {
            return buySide(itemNumber, productOpt.get(), description, counterpartyId);
        }
        return sellSide(itemNumber, productOpt.get(), description, counterpartyId);
    }

    // -- sell side ---------------------------------------------------------------------------

    private ElasticityModelDto sellSide(String itemNumber, ProductRef product, String description,
            String counterpartyId) {
        String counterparty = !blank(counterpartyId) ? counterpartyId
                : (product.defaultStoreCode() != null ? product.defaultStoreCode() : "");
        PricingModel m = pricing.getPricingModel(itemNumber, counterparty);

        BigDecimal coefficient = m.beta();
        boolean usedFallback = !SalesHistory.Elasticity.ITEM_STORE.equals(m.elasticityBasis());
        double r2 = m.betaR2() == null ? 0 : m.betaR2().doubleValue();
        int n = m.units90() != null ? (int) m.totalTransactions() : 0;
        int confidenceScore = SalesHistory.Elasticity.DEFAULT.equals(m.elasticityBasis()) ? 35
                : (int) Math.round(Stats.clamp(25 + 55 * r2 + 0.8 * Math.min(n, 24), 20, 95));
        List<BigDecimal> band = SalesHistory.Elasticity.DEFAULT.equals(m.elasticityBasis())
                ? List.of(new BigDecimal("-1.80"), new BigDecimal("-0.60"))
                : List.of(BigDecimal.valueOf(round2(coefficient.doubleValue() - 0.3)),
                        BigDecimal.valueOf(round2(coefficient.doubleValue() + 0.3)));

        Window w12 = Window.trailingMonths(clock.today(), 12);
        List<CoPurchase> co = sales.coPurchased(product.id(), 3, w12);
        List<CrossItemDto> cross = new ArrayList<>();
        List<MonthPoint> ownPrices = sales.monthly(product.id(), null, 24, clock.today());
        double[] priceSeries = toPriceArray(ownPrices);
        for (CoPurchase c : co) {
            List<MonthPoint> otherUnits = sales.monthly(c.productId(), null, 24, clock.today());
            double[] unitSeries = toUnitArray(otherUnits);
            Ols fit = Stats.olsLogLog(priceSeries, unitSeries);
            BigDecimal effectPct = (fit.available() && fit.n() >= 8 && fit.r2() >= 0.2)
                    ? BigDecimal.valueOf(round1(fit.coefficient())) : null;
            cross.add(new CrossItemDto(c.shortName(), "on " + Fmt.fixed(c.attachPct().doubleValue(), 0)
                    + "% of orders", effectPct, "complement"));
        }

        List<CalcStepDto> steps = List.of(
                new CalcStepDto("Method", usedFallback ? "Category prior, pooled" : "Log-log regression, item-store",
                        usedFallback
                                ? "Too little of this item's own price variation to trust an item-level estimate - "
                                        + "falls back to " + m.elasticityBasis() + "."
                                : "Fit to this pair's own price/volume co-movement over the trailing 24 months.",
                        "step"),
                new CalcStepDto("Granularity", m.elasticityBasis(),
                        Fmt.groupInt(m.totalTransactions()) + " transactions in the window", "step"),
                new CalcStepDto("R-squared", Fmt.fixed(r2, 2), null, "step"),
                new CalcStepDto("Own-price elasticity", Fmt.jsNum(coefficient.doubleValue()),
                        "A 1% price rise moves quantity " + Fmt.fixed(coefficient.doubleValue(), 2) + "% - "
                                + (Math.abs(coefficient.doubleValue()) > 1 ? "elastic" : "inelastic"),
                        "result"));

        List<ResponsePointDto> curve = new ArrayList<>();
        BigDecimal units90 = m.units90();
        for (int i = -3; i <= 3; i++) {
            double x = 1 + i * 0.05;
            double y = units90 != null ? units90.doubleValue() * Math.pow(x, coefficient.doubleValue()) : 0;
            curve.add(new ResponsePointDto(BigDecimal.valueOf(round2(x)), BigDecimal.valueOf(round1(y))));
        }

        return new ElasticityModelDto(itemNumber, description, "sell", counterparty, true, coefficient,
                confidenceScore, band, m.elasticityBasis(), usedFallback, curve, cross, List.of(),
                List.of(new FactorWeightDto("R-squared", bd(round1(r2 * 100)), null, null)), steps);
    }

    private static double[] toPriceArray(List<MonthPoint> points) {
        double[] out = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            var p = points.get(i).avgPrice();
            out[i] = p == null ? 0 : p.doubleValue();
        }
        return out;
    }

    private static double[] toUnitArray(List<MonthPoint> points) {
        double[] out = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            var u = points.get(i).units();
            out[i] = u == null ? 0 : u.doubleValue();
        }
        return out;
    }

    // -- buy side ----------------------------------------------------------------------------

    private ElasticityModelDto buySide(String itemNumber, ProductRef product, String description,
            String counterpartyId) {
        LocalDate today = clock.today();
        Window w12 = Window.trailingMonths(today, 12);
        List<SupplierShare> shares = purchases.item(itemNumber, w12).suppliers();

        String supplierKey = !blank(counterpartyId) ? counterpartyId
                : shares.stream().findFirst().map(SupplierShare::supplierKey).orElse(null);
        if (supplierKey == null) {
            return new ElasticityModelDto(itemNumber, description, "buy", "—", false, null, 0,
                    List.of(), "—", true, List.of(), List.of(), List.of(), List.of(), List.of());
        }
        Optional<SupplierRef> supplier = suppliers.supplier(supplierKey);
        String supplierName = supplier.map(SupplierRef::name).orElse(supplierKey);

        List<PoMonth> months = purchases.monthly(itemNumber, supplierKey, 24, today);
        double[] landed = new double[months.size()];
        double[] qty = new double[months.size()];
        for (int i = 0; i < months.size(); i++) {
            PoMonth pm = months.get(i);
            landed[i] = pm.avgLanded() == null ? 0 : pm.avgLanded().doubleValue();
            qty[i] = pm.units() == null ? 0 : pm.units().doubleValue();
        }
        Ols fit = Stats.olsLogLog(landed, qty);
        boolean usedFallback = !(fit.available() && fit.n() >= 8 && fit.r2() >= 0.25);
        BigDecimal coefficient = usedFallback ? new BigDecimal("-0.80") : BigDecimal.valueOf(round2(fit.coefficient()));
        List<BigDecimal> band = usedFallback ? List.of(new BigDecimal("-1.20"), new BigDecimal("-0.40"))
                : List.of(BigDecimal.valueOf(round2(fit.coefficient() - 0.3)),
                        BigDecimal.valueOf(round2(fit.coefficient() + 0.3)));
        int confidenceScore = usedFallback ? 35
                : (int) Math.round(Stats.clamp(25 + 55 * fit.r2() + 0.8 * Math.min(fit.n(), 24), 20, 95));

        List<CrossItemDto> cross = List.of();
        List<ExperimentRowDto> experiments = List.of();

        List<CalcStepDto> steps = List.of(
                new CalcStepDto("Method", usedFallback ? "Category default" : "Log-log regression on monthly POs",
                        usedFallback ? "Not enough of this supplier's own commitment history for this item."
                                : "Fit to this supplier's own monthly landed cost and quantity for this item.",
                        "step"),
                new CalcStepDto("Data volume observed", months.size() + " months", null, "step"),
                new CalcStepDto("Volume-price response", Fmt.jsNum(coefficient.doubleValue()),
                        "Quantity moves " + Fmt.fixed(coefficient.doubleValue(), 2) + "% for every 1% landed-cost "
                                + "change with " + supplierName, "result"));

        return new ElasticityModelDto(itemNumber, description, "buy", supplierName, true, coefficient,
                confidenceScore, band, usedFallback ? "Category prior" : "Supplier-item", usedFallback, List.of(),
                cross, experiments, List.of(), steps);
    }
}
