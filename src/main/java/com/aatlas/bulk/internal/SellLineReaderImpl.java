package com.aatlas.bulk.internal;

import com.aatlas.bulk.SellLine;
import com.aatlas.bulk.SellLineReader;
import com.aatlas.bulk.internal.BulkSeedCatalog.SeedProduct;
import com.aatlas.bulk.internal.PricingEngine.PricingModel;
import org.springframework.stereotype.Component;

/**
 * {@code TODO(merge): replace with the sell module's public SellIntel reader.} See
 * {@link SellLine} for exactly what this stand-in for {@code getSellIntel} covers.
 */
@Component
public class SellLineReaderImpl implements SellLineReader {

    private final PricingEngine pricing;
    private final BulkSeedCatalog catalog;

    public SellLineReaderImpl(PricingEngine pricing, BulkSeedCatalog catalog) {
        this.pricing = pricing;
        this.catalog = catalog;
    }

    @Override
    public SellLine read(String itemNumber, String storeId) {
        PricingModel m = pricing.getPricingModel(itemNumber, storeId);
        SeedProduct product = catalog.product(itemNumber).orElse(null);
        String name = product != null ? product.shortName() : itemNumber;
        String category = product != null ? product.category() : "Plumbing";
        String storeLabel = StoreLabels.of(catalog, storeId);

        if (!m.priceable()) {
            return new SellLine(itemNumber, storeId, false, name, itemNumber, storeLabel, category,
                    m.cost(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "none", 0, 0, "Low");
        }

        double currentMarginPct = marginPct(m.currentPrice(), m.cost());
        double expectedMarginPct = marginPct(m.optimalPrice(), m.cost());
        double elasticity = SellSeeds.elasticity(itemNumber, storeId);
        double monthlyUnits = SellSeeds.monthlyUnitsFor(itemNumber, storeId, m.cost());
        SellSeeds.Inventory inv = SellSeeds.inventoryFor(itemNumber, storeId, monthlyUnits);
        double inventoryValue = PricingEngine.round2(inv.units() * m.cost());
        double upliftPerUnit = PricingEngine.round2(m.optimalPrice() - m.currentPrice());
        double monthlyOpportunity = PricingEngine.round2(upliftPerUnit * monthlyUnits);
        String demandLevel = m.demand() != null ? m.demand().level() : "none";
        double demandPct = m.demand() != null ? m.demand().movePercent() : 0;

        double demandConf = m.demand() != null ? m.demand().confWeight() : 0.5;
        int confidence = (int) Math.round(Math.max(55, Math.min(97,
                58 + Math.min(18, m.competitorCount() * 2.2) + Math.min(14, m.totalTransactions() / 17.0)
                        + demandConf * 8)));
        String confidenceLabel = confidence >= 85 ? "High" : confidence >= 70 ? "Medium" : "Low";

        return new SellLine(itemNumber, storeId, true, name, itemNumber, storeLabel, category,
                m.cost(), m.currentPrice(), m.optimalPrice(), m.aggressivePrice(), m.marginFloor(),
                currentMarginPct, expectedMarginPct, elasticity, monthlyUnits, inv.units(), inventoryValue,
                inv.weeksOfCover(), monthlyOpportunity, demandLevel, demandPct, confidence, confidenceLabel);
    }

    private static double marginPct(double price, double cost) {
        return price == 0 ? 0 : PricingEngine.round2(((price - cost) / price) * 100);
    }
}
