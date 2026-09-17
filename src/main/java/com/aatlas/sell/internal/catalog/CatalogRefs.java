package com.aatlas.sell.internal.catalog;

import java.time.LocalDate;

/**
 * The one read model the sell engines still keep for themselves: the commodity trend a
 * product's {@code commodity} key resolves to, badged {@code reference} wherever it reaches
 * a screen. Everything else the engines need (products, stores, customers, regions) comes
 * straight off {@link com.aatlas.history.Catalogue}'s own records through
 * {@link CatalogGateway} - no local duplicate of those shapes any more.
 */
public final class CatalogRefs {

    private CatalogRefs() {
    }

    /** {@code pct90} 0 and {@code label} "No commodity exposure" for an unknown/absent key. */
    public record CommodityTrend(double pct90, String label, LocalDate asOf) {
    }
}
