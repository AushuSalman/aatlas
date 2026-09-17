package com.aatlas.bulk;

/**
 * One item's supplier evaluation, landed into a region, over {@code buy.BuyIntelReader} -
 * see {@link BuyLine} and {@link SupplierEval} for exactly what it carries.
 */
public interface BuyLineReader {

    /** Never null; a non-priceable item comes back with {@code priceable=false}. */
    BuyLine read(String itemNumber, String regionKey, int qty);
}
