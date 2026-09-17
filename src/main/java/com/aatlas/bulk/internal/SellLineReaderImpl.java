package com.aatlas.bulk.internal;

import com.aatlas.bulk.SellLine;
import com.aatlas.bulk.SellLineReader;
import com.aatlas.sell.SellLineView;
import com.aatlas.sell.SellLines;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** {@link SellLineReader} over {@code sell.SellLines}, the real seam into the sell engine. */
@Component
public class SellLineReaderImpl implements SellLineReader {

    private static final Map<String, String> NOT_PRICEABLE_SOURCES = Map.of();
    private static final List<String> NOT_PRICEABLE_LOCKED = List.of();

    private final SellLines sellLines;

    public SellLineReaderImpl(SellLines sellLines) {
        this.sellLines = sellLines;
    }

    @Override
    public SellLine read(String itemNumber, String storeId) {
        Optional<SellLineView> found = sellLines.read(itemNumber, storeId);
        if (found.isEmpty() || !found.get().priceable()) {
            String name = found.map(SellLineView::name).orElse(itemNumber);
            String description = found.map(SellLineView::description).orElse(itemNumber);
            String storeLabel = found.map(SellLineView::storeLabel).orElse(storeId);
            String category = found.map(SellLineView::category).orElse("Plumbing");
            return new SellLine(itemNumber, storeId, false, name, description, storeLabel, category,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "none", 0, 0, "Low",
                    NOT_PRICEABLE_SOURCES, NOT_PRICEABLE_LOCKED);
        }

        SellLineView v = found.get();
        return new SellLine(v.itemNumber(), v.storeId(), true, v.name(), v.description(), v.storeLabel(),
                v.category(), v.cost(), v.currentPrice(), v.recommended(), v.stretchPrice(), v.marginFloor(),
                v.currentMarginPct(), v.expectedMarginPct(), v.elasticity(), v.monthlyUnits(), v.inventoryUnits(),
                v.inventoryValue(), v.weeksOfCover(), v.monthlyOpportunity(), v.demandLevel(), v.demandPct(),
                v.confidence(), v.confidenceLabel(), v.sources(), v.locked());
    }
}
