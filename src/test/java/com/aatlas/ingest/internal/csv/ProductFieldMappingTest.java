package com.aatlas.ingest.internal.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The products template maps in the exact pass; UOM, declared last, never steals a cost column. */
class ProductFieldMappingTest {

    private static final List<String> TEMPLATE = List.of(
            "Item No", "Description", "Category", "Subcategory", "Commodity", "List Price", "Unit Cost", "On Hand",
            "Branch", "Supplier", "Supplier Cost", "Lead Time", "UOM");

    @Test
    @DisplayName("every column of the template is detected, in the right place")
    void detectsTheTemplate() {
        ColumnMapping mapping = ColumnMapping.detect(ImportKind.PRODUCTS, TEMPLATE);

        assertThat(mapping.missingRequired()).isEmpty();
        for (ProductField field : ProductField.values()) {
            assertThat(mapping.columnOf(field)).as(field.key()).contains(TEMPLATE.indexOf(field.header()));
        }
        assertThat(mapping.columns().values()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Unit Cost (USD) is the cost column, and Cost Price is not the list price")
    void costColumnsAreNotConfusedWithUomOrPrice() {
        ColumnMapping mapping = ColumnMapping.detect(ImportKind.PRODUCTS, List.of(
                "Item No", "Description", "Cost Price", "Unit Cost (USD)", "Unit of Measure"));

        // "costprice" is an exact unit-cost synonym and is claimed in pass 1; the parenthesised
        // header then has nothing specific left to match, and UOM only takes its own column.
        assertThat(mapping.columnOf(ProductField.UNIT_COST)).contains(2);
        assertThat(mapping.columnOf(ProductField.UOM)).contains(4);
        assertThat(mapping.columnOf(ProductField.LIST_PRICE)).isEmpty();
    }

    @Test
    @DisplayName("the RealDataIT products-only header, UOM before Commodity, still maps every field")
    void detectsTheProductsOnlyHeader() {
        ColumnMapping mapping = ColumnMapping.detect(ImportKind.PRODUCTS, List.of(
                "Item No", "Description", "Category", "Subcategory", "UOM", "Commodity", "List Price", "Unit Cost",
                "On Hand", "Branch", "Supplier", "Supplier Cost", "Lead Time"));

        assertThat(mapping.missingRequired()).isEmpty();
        assertThat(mapping.columnOf(ProductField.UOM)).contains(4);
        assertThat(mapping.columnOf(ProductField.COMMODITY)).contains(5);
        assertThat(mapping.columnOf(ProductField.LEAD_TIME)).contains(12);
    }
}
