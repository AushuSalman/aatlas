package com.aatlas.catalog.internal.seed;

import java.util.ArrayList;
import java.util.List;

/**
 * Which branches have sold a given item - a port of {@code tenantsSellingItem} in the
 * frontend's {@code src/lib/mock/catalog.ts}, to the bit.
 *
 * <p>The prototype decides this deterministically from the item number so the "no sales
 * of this item" tag in the branch picker is stable across renders: the item's default
 * branch always sells it, and every other branch sells it unless bit-shifting the item's
 * FNV-1a hash by the branch's position in the list lands on a multiple of three. Every
 * sellable product ends up at three or more branches.
 *
 * <p>It is reproduced here rather than replaced with something tidier because the pricing
 * engine port in step 2 has golden-file tests against the prototype, and those only mean
 * something if the set of priceable (item, branch) pairs is the same on both sides.
 * Change this and the golden files change with it.
 */
public final class SellersRule {

    private SellersRule() {
    }

    /**
     * {@code hashString} from the frontend: FNV-1a over UTF-16 code units, 32-bit wrapping
     * multiply ({@code Math.imul}), then {@code Math.abs(h | 0)}.
     *
     * <p>Java {@code int} arithmetic matches JavaScript's 32-bit path exactly, including
     * the one edge the frontend has: {@code Math.abs} of {@code -2^31} stays negative in
     * Java, and JavaScript's {@code >>} converts its {@code 2^31} back to the same
     * negative before shifting.
     */
    public static int hashString(String value) {
        int h = 0x811c9dc5;
        for (int i = 0; i < value.length(); i++) {
            h ^= value.charAt(i);
            h *= 0x01000193;
        }
        return Math.abs(h);
    }

    /**
     * The branch codes that sell {@code itemNumber}, in the order the branches are listed.
     *
     * @param storeCodesInSeedOrder every branch, in the same order as the frontend's
     *     {@code TENANTS} - the index is part of the rule
     */
    public static List<String> sellers(
            String itemNumber, String defaultStoreCode, boolean sellable, List<String> storeCodesInSeedOrder) {
        if (!sellable) {
            return List.of();
        }
        int seed = hashString(itemNumber);
        List<String> sellers = new ArrayList<>();
        for (int i = 0; i < storeCodesInSeedOrder.size(); i++) {
            String code = storeCodesInSeedOrder.get(i);
            if (code.equals(defaultStoreCode) || (seed >> i) % 3 != 0) {
                sellers.add(code);
            }
        }
        return List.copyOf(sellers);
    }
}
