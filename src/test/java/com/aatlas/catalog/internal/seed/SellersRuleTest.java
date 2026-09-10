package com.aatlas.catalog.internal.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SellersRule} ported bit-for-bit from the frontend's {@code hashString} and
 * {@code tenantsSellingItem} ({@code src/lib/mock/catalog.ts}).
 *
 * <p>The expected values below were computed by running the actual TypeScript
 * functions in Node against {@code seed/stores.json} (US network, seed order) and
 * {@code seed/products.json} for the item numbers exercised here - not re-derived in
 * Java - so a divergence between the two implementations fails this test rather than
 * only showing up as a golden-file mismatch two steps later.
 */
class SellersRuleTest {

    /** The nine US branch codes, in the exact order {@code stores.json} lists them. */
    private static final List<String> US_STORE_CODES = List.of(
            "100349", "100047", "100117", "100649", "100812", "100205", "100933", "100571", "100959");

    @Test
    @DisplayName("hashString matches the frontend's FNV-1a for known items")
    void hashStringMatchesFrontend() {
        assertThat(SellersRule.hashString("HRD118902")).isEqualTo(994342018);
        assertThat(SellersRule.hashString("HRD304148")).isEqualTo(30400771);
    }

    @Test
    @DisplayName("the demo item (copper tube) sells at every US branch")
    void demoItemSellsEverywhere() {
        List<String> sellers = SellersRule.sellers("HRD118902", "100959", true, US_STORE_CODES);
        assertThat(sellers).containsExactlyElementsOf(US_STORE_CODES);
    }

    @Test
    @DisplayName("a non-default item can be excluded from some branches, matching the frontend exactly")
    void nonDefaultItemMatchesFrontendExactly() {
        List<String> sellers = SellersRule.sellers("HRD304148", "100349", true, US_STORE_CODES);
        assertThat(sellers).containsExactly(
                "100349", "100117", "100649", "100812", "100205", "100933", "100571", "100959");
        // 100047 sits at index 1; the frontend's own run excludes exactly that branch.
        assertThat(sellers).doesNotContain("100047");
    }

    @Test
    @DisplayName("a catalogued-but-never-sold item sells nowhere")
    void unsellableItemHasNoSellers() {
        assertThat(SellersRule.sellers("HRD900001", "100349", false, US_STORE_CODES)).isEmpty();
    }

    @Test
    @DisplayName("the default branch always sells the item it defaults to, whatever the hash says")
    void defaultBranchAlwaysSells() {
        for (String item : List.of("HRD118902", "HRD304148", "HRD248813", "HRD772310")) {
            String defaultStore = US_STORE_CODES.get(SellersRule.hashString(item) % US_STORE_CODES.size());
            assertThat(SellersRule.sellers(item, defaultStore, true, US_STORE_CODES)).contains(defaultStore);
        }
    }

    @Test
    @DisplayName("every sellable product ends up at three or more branches, as the class contract promises")
    void everySellableProductHasAtLeastThreeSellers() {
        List<String> items = List.of("HRD118902", "HRD304148", "HRD772310", "HRD450871", "HRD290145",
                "HRD661204", "HRD983377", "HRD512066", "HRD874019", "HRD335590", "HRD107744", "HRD248813");
        for (String item : items) {
            List<String> sellers = SellersRule.sellers(item, US_STORE_CODES.get(0), true, US_STORE_CODES);
            assertThat(sellers).as("sellers of %s", item).hasSizeGreaterThanOrEqualTo(3);
        }
    }

    @Test
    @DisplayName("the rule is deterministic: same inputs, same output, every time")
    void isDeterministic() {
        List<String> first = SellersRule.sellers("HRD248813", "100571", true, US_STORE_CODES);
        List<String> second = SellersRule.sellers("HRD248813", "100571", true, US_STORE_CODES);
        assertThat(first).isEqualTo(second);
    }
}
