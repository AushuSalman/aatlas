package com.aatlas.sell;

import java.util.Optional;

/**
 * The sell module's line reader for other modules: the number the Sell screen would show
 * for this item at this branch, so a basket priced in bulk and a line priced alone can never
 * disagree.
 */
public interface SellLines {

    /** Empty when the item or the store does not exist. Present-but-not-priceable when there is nothing to price on. */
    Optional<SellLineView> read(String itemNumber, String storeCode);
}
