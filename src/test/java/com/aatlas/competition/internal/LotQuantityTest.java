package com.aatlas.competition.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.competition.internal.ListingFilter.Judged;
import com.aatlas.competition.internal.ShoppingProvider.Listing;
import java.math.BigDecimal;
import java.util.IdentityHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LotQuantityTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Lot of 50 1/2 in PVC ball valves|50",
        "1/2 in PVC Ball Valve 10-Pack|10",
        "PVC ball valve 1/2 inch (100 pcs)|100",
        "Case of 25 - NIBCO ball valve|25",
        "Ball valve 1/2 in, 25/case|25",
        "Box of 100 copper couplings|100",
        "Brass fitting 12 count|12",
    })
    void readsTheQuantity(String title, int qty) {
        assertThat(LotQuantity.parse(title)).isEqualTo(qty);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Charlotte 1/2 in. PVC Ball Valve",
        "1/2 in x 10 ft PVC pipe",
        "Pack of 1 ball valve",
        "2 in PVC coupling",
    })
    void aSizeOrASingleIsNotALot(String title) {
        assertThat(LotQuantity.parse(title)).isNull();
    }

    @Test
    void bulkListingsAreJudgedPerUnitAndTitlesWithNoQuantityGoOut() {
        List<Listing> lots = List.of(
                new Listing("ebay", "Lot of 50 1/2 in PVC ball valve", new BigDecimal("150.00"), "USD", "eBay: a", "u1"),
                new Listing("ebay", "1/2 in PVC ball valve", new BigDecimal("6.00"), "USD", "eBay: b", "u2"),
                new Listing("ebay", "1/2 in PVC ball valve 10-pack", new BigDecimal("40.00"), "USD", "eBay: c", "u3"));
        IdentityHashMap<Listing, Listing> toLot = new IdentityHashMap<>();
        List<Judged> judged = CompetitionService.judgeBulk("1/2 in PVC ball valve", "USD", lots, toLot);

        assertThat(judged).extracting(Judged::kept).containsExactly(true, false, true);
        assertThat(judged.get(0).listing().price()).isEqualByComparingTo("3.00");
        assertThat(judged.get(2).listing().price()).isEqualByComparingTo("4.00");
        assertThat(judged.get(1).reason()).startsWith("No pack or lot size");
        assertThat(toLot.get(judged.get(0).listing()).price()).isEqualByComparingTo("150.00");
    }
}
