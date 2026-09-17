package com.aatlas.ingest.internal.csv;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * One accepted product-master row.
 *
 * <p>Text fields are stripped and empty when blank; numeric fields are null when blank or
 * skipped by validation. {@code commodity} is already lower-cased and aliased ({@code cpvc}
 * is {@code pvc}), or {@code none} when the file named one the platform does not track.
 */
public record ProductRow(
        int line,
        String item,
        String description,
        String category,
        String subcategory,
        String commodity,
        BigDecimal listPrice,
        BigDecimal unitCost,
        BigDecimal onHand,
        String branch,
        String supplier,
        BigDecimal supplierCost,
        Integer leadTime,
        String uom) implements AcceptedRow {

    @Override
    public Map<String, Object> preview() {
        Map<String, Object> out = new HashMap<>();
        out.put("item", item);
        out.put("description", description);
        out.put("category", category);
        out.put("subcategory", subcategory);
        out.put("commodity", commodity);
        out.put("listPrice", listPrice);
        out.put("unitCost", unitCost);
        out.put("onHand", onHand);
        out.put("branch", branch);
        out.put("supplier", supplier);
        out.put("supplierCost", supplierCost);
        out.put("leadTime", leadTime);
        out.put("uom", uom);
        return out;
    }

    @Override
    public ProductRow forSample(int days, BigDecimal fx) {
        return new ProductRow(line, item, description, category, subcategory, commodity,
                AcceptedRow.convert(listPrice, fx), AcceptedRow.convert(unitCost, fx), onHand, branch, supplier,
                AcceptedRow.convert(supplierCost, fx), leadTime, uom);
    }
}
