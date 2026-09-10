package com.aatlas.bulk;

/**
 * One item's supplier evaluation, landed into a region. {@code TODO(merge): replace with
 * the buy module's public reader} - see {@link BuyLine} and {@link SupplierEval} for
 * exactly what this stand-in covers and what it deliberately leaves out.
 */
public interface BuyLineReader {

    /** Never null; a non-priceable item comes back with {@code priceable=false}. */
    BuyLine read(String itemNumber, String regionKey, int qty);
}
