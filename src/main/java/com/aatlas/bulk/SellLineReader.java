package com.aatlas.bulk;

/**
 * One (item, store) recommendation, over {@code sell.SellLines} - see {@link SellLine}
 * for exactly what it carries.
 */
public interface SellLineReader {

    /** Never null; a non-priceable pair comes back with {@code priceable=false}. */
    SellLine read(String itemNumber, String storeId);
}
