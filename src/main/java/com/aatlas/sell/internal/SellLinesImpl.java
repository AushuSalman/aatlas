package com.aatlas.sell.internal;

import com.aatlas.sell.SellLineView;
import com.aatlas.sell.SellLines;
import com.aatlas.sell.internal.catalog.CatalogGateway;
import com.aatlas.sell.internal.dto.SellDtos.SellIntelDto;
import com.aatlas.sell.internal.engine.SellEngine;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link SellLines} over {@link SellEngine#getSellIntel}: the real number, real sources, real locks. */
@Service
class SellLinesImpl implements SellLines {

    private final CatalogGateway catalog;
    private final SellEngine engine;

    SellLinesImpl(CatalogGateway catalog, SellEngine engine) {
        this.catalog = catalog;
        this.engine = engine;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SellLineView> read(String itemNumber, String storeCode) {
        if (catalog.findProduct(itemNumber).isEmpty() || catalog.findStore(storeCode).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toView(engine.getSellIntel(itemNumber, storeCode)));
    }

    static SellLineView toView(SellIntelDto i) {
        return new SellLineView(
                i.itemNumber(), i.storeId(), i.priceable(), i.name(), i.description(), i.storeLabel(), i.category(),
                d(i.cost()), d(i.currentPrice()), d(i.recommended()), d(i.stretchPrice()), d(i.marginFloor()),
                d(i.currentMarginPct()), d(i.expectedMarginPct()), d(i.elasticity()),
                i.monthlyUnits(), i.inventoryUnits() == null ? 0 : i.inventoryUnits(), d(i.inventoryValue()),
                d(i.weeksOfCover()), d(i.monthlyOpportunity()), demandLevel(i.demandLabel()), d(i.demandPct()),
                i.confidence(), i.confidenceLabel(),
                i.sources() == null ? java.util.Map.of() : i.sources(),
                i.locked() == null ? List.of() : i.locked());
    }

    private static double d(java.math.BigDecimal v) {
        return v == null ? 0 : v.doubleValue();
    }

    private static String demandLevel(String label) {
        if (label == null) {
            return "none";
        }
        return switch (label) {
            case "High demand" -> "high";
            case "Low demand" -> "low";
            case "Stable demand" -> "medium";
            default -> "none";
        };
    }
}
