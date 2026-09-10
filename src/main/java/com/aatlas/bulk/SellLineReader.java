package com.aatlas.bulk;

/**
 * One (item, store) recommendation. {@code TODO(merge): replace with the sell module's
 * public reader} - see {@link SellLine} for exactly what this stand-in covers and what it
 * deliberately leaves out.
 */
public interface SellLineReader {

    /** Never null; a non-priceable pair comes back with {@code priceable=false}. */
    SellLine read(String itemNumber, String storeId);
}
