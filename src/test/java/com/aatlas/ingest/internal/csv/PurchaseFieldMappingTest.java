package com.aatlas.ingest.internal.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The purchases template must map entirely in the exact pass, and real exports in the second. */
class PurchaseFieldMappingTest {

    private static final List<String> TEMPLATE = List.of(
            "PO Number", "Order Date", "Supplier", "Supplier Country", "Item No", "Item Description", "Qty Ordered",
            "Unit Cost", "Freight", "Duty", "Landed Cost", "Currency", "Ship To", "Promised Date", "Received Date",
            "Qty Received");

    @Test
    @DisplayName("every column of the template is detected, in the right place")
    void detectsTheTemplate() {
        ColumnMapping mapping = ColumnMapping.detect(ImportKind.PURCHASES, TEMPLATE);

        assertThat(mapping.missingRequired()).isEmpty();
        for (PurchaseField field : PurchaseField.values()) {
            assertThat(mapping.columnOf(field)).as(field.key()).contains(TEMPLATE.indexOf(field.header()));
        }
        assertThat(mapping.columns().values()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a Dynamics-style export maps every required field")
    void detectsAnErpExport() {
        ColumnMapping mapping = ColumnMapping.detect(ImportKind.PURCHASES, List.of(
                "Document No.", "Buy-from Vendor No.", "Order Date", "No.", "Quantity", "Direct Unit Cost",
                "Expected Receipt Date", "Location Code"));

        assertThat(mapping.columnOf(PurchaseField.PO_NUMBER)).contains(0);
        assertThat(mapping.columnOf(PurchaseField.SUPPLIER)).contains(1);
        assertThat(mapping.columnOf(PurchaseField.ORDER_DATE)).contains(2);
        assertThat(mapping.columnOf(PurchaseField.QTY)).contains(4);
        // "directunitcost" contains "unitcost", a specific synonym: pass 2 claims it.
        assertThat(mapping.columnOf(PurchaseField.UNIT_COST)).contains(5);
        assertThat(mapping.columnOf(PurchaseField.PROMISED_DATE)).contains(6);
        assertThat(mapping.columnOf(PurchaseField.SHIP_TO)).contains(7);
        // "No." alone is too generic to be the item column; the user picks it.
        assertThat(mapping.missingRequired()).containsExactly(PurchaseField.ITEM);
    }

    @Test
    @DisplayName("a generic token never claims a column on its own")
    void genericTokensDoNotMatch() {
        // "Unit Cost (USD)" must go to unit cost, never to currency through "currency"-like
        // tokens, and "Cost Center" must not become the cost column through "cost".
        ColumnMapping mapping = ColumnMapping.detect(ImportKind.PURCHASES, List.of(
                "Item No", "Order Date", "Supplier", "Qty Ordered", "Cost Center", "Unit Cost (USD)"));

        assertThat(mapping.columnOf(PurchaseField.UNIT_COST)).contains(5);
        assertThat(mapping.columns().values()).doesNotContain(4);
    }
}
