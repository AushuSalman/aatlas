package com.aatlas.ingest.internal.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The competitor-prices template maps in the exact pass. */
class CompetitorPriceFieldMappingTest {

    private static final List<String> TEMPLATE = List.of(
            "Item No", "Competitor", "Price", "Currency", "Region/Branch", "Observed Date", "Source URL");

    @Test
    @DisplayName("every column of the template is detected, in the right place")
    void detectsTheTemplate() {
        ColumnMapping mapping = ColumnMapping.detect(ImportKind.COMPETITOR_PRICES, TEMPLATE);

        assertThat(mapping.missingRequired()).isEmpty();
        for (CompetitorPriceField field : CompetitorPriceField.values()) {
            assertThat(mapping.columnOf(field)).as(field.key()).contains(TEMPLATE.indexOf(field.header()));
        }
        assertThat(mapping.columns().values()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a scraper export maps the required fields and leaves the rest to the user")
    void detectsAScraperExport() {
        ColumnMapping mapping = ColumnMapping.detect(ImportKind.COMPETITOR_PRICES, List.of(
                "sku", "seller", "shelf_price", "scraped_at", "listing_url", "market"));

        assertThat(mapping.columnOf(CompetitorPriceField.ITEM)).contains(0);
        assertThat(mapping.columnOf(CompetitorPriceField.COMPETITOR)).contains(1);
        assertThat(mapping.columnOf(CompetitorPriceField.PRICE)).contains(2);
        assertThat(mapping.columnOf(CompetitorPriceField.OBSERVED_AT)).contains(3);
        assertThat(mapping.columnOf(CompetitorPriceField.SOURCE_URL)).contains(4);
        assertThat(mapping.columnOf(CompetitorPriceField.REGION)).contains(5);
        assertThat(mapping.missingRequired()).isEmpty();
    }
}
