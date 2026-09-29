package com.aatlas.competition.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.competition.internal.ListingFilter.Judged;
import com.aatlas.competition.internal.ShoppingProvider.Listing;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListingFilterTest {

    private static final String Q = "1/2 in PVC ball valve";

    private static Listing l(String title, String price, String merchant) {
        return new Listing("serpapi", title, price == null ? null : new BigDecimal(price), "USD", merchant,
                "https://example.com/" + merchant);
    }

    @Test
    void keepsOnTopicListingsOnePerSellerAndSaysWhyTheRestWentOut() {
        List<Judged> judged = ListingFilter.judge(Q, "USD", List.of(
                l("Charlotte 1/2 in. PVC Ball Valve", "6.48", "The Home Depot"),
                l("NIBCO 1/2 in PVC Ball Valve Slip", "7.10", "Lowe's"),
                l("1/2 in PVC ball valve, sch 40", "5.95", "Zoro"),
                l("1/2 in PVC ball valve (again)", "6.10", "The Home Depot"),
                l("Adjustable pipe wrench 14 inch", "19.99", "Grainger"),
                l("1/2 in PVC ball valve - case of 50", "240.00", "Supplyhouse"),
                l("1/2 in PVC ball valve", null, "Ferguson")));

        assertThat(judged).extracting(j -> j.listing().merchant() + ":" + j.kept()).containsExactly(
                "The Home Depot:true", "Lowe's:true", "Zoro:true", "The Home Depot:false", "Grainger:false",
                "Supplyhouse:false", "Ferguson:false");
        assertThat(judged.get(3).reason()).contains("Another listing from The Home Depot");
        assertThat(judged.get(4).reason()).startsWith("Title matches too little");
        assertThat(judged.get(5).reason()).startsWith("Far from the other listings");
        assertThat(judged.get(6).reason()).isEqualTo("No price on the listing");
    }

    @Test
    void anotherCurrencyIsNotThisMarketsPrice() {
        Listing gbp = new Listing("ebay", "1/2 in PVC ball valve", new BigDecimal("5.00"), "GBP", "eBay: x", null);
        Judged j = ListingFilter.judge(Q, "USD", List.of(gbp)).get(0);
        assertThat(j.kept()).isFalse();
        assertThat(j.reason()).isEqualTo("Priced in GBP, not USD");
    }

    @Test
    void outlierRuleWaitsForThreeListings() {
        List<Judged> judged = ListingFilter.judge(Q, "USD", List.of(
                l("1/2 in PVC ball valve", "5.00", "A"), l("1/2 in PVC ball valve", "40.00", "B")));
        assertThat(judged).allMatch(Judged::kept);
    }

    @Test
    void wordsKeepSizesAndDropFiller() {
        assertThat(ListingFilter.words("1/2 in. PVC Ball Valve for the pipe")).containsExactly("1/2", "pvc", "ball",
                "valve", "pipe");
    }

    @Test
    void medianOfEvenAndOdd() {
        assertThat(ListingFilter.median(List.of(new BigDecimal("3"), new BigDecimal("1"), new BigDecimal("2"))))
                .isEqualByComparingTo("2");
        assertThat(ListingFilter.median(List.of(new BigDecimal("1"), new BigDecimal("2")))).isEqualByComparingTo("1.5");
    }
}
