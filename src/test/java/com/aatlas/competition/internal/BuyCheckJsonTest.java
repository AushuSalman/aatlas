package com.aatlas.competition.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A kept buy check reads back as it was shown - listings, reasons and the fetch time. */
class BuyCheckJsonTest {

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void roundTrips() throws Exception {
        CompetitionDtos.ListingView kept = new CompetitionDtos.ListingView("Southwire 12 AWG THHN", new BigDecimal("57.81"),
                "USD", "Arbor", "https://example.com/a", true, null, new BigDecimal("0.8"), null, null);
        CompetitionDtos.ListingView dropped = new CompetitionDtos.ListingView("Lot of 10 spools", new BigDecimal("40.00"),
                "USD", "eBay #123 - seller", "https://ebay.com/itm/123", false, "Sold as a lot or pack, not a single unit",
                new BigDecimal("0.5"), 10, new BigDecimal("400.00"));
        CompetitionDtos.Side retail = new CompetitionDtos.Side("12 awg thhn",
                new CompetitionDtos.Summary(2, 1, new BigDecimal("57.81"), new BigDecimal("57.81"), new BigDecimal("57.81")),
                List.of(new CompetitionDtos.ProviderRun("ebay", "eBay", "ok", null, List.of(kept, dropped))), "note");
        CompetitionDtos.Side bulk = new CompetitionDtos.Side("12 awg thhn bulk lot case",
                new CompetitionDtos.Summary(0, 0, null, null, null), List.of(), null);
        CompetitionDtos.BuyCheck check = new CompetitionDtos.BuyCheck("W-12", "12 AWG wire", "USD", retail, bulk,
                Instant.parse("2026-10-05T10:42:00Z"));

        CompetitionDtos.BuyCheck back = json.readValue(json.writeValueAsString(check), CompetitionDtos.BuyCheck.class);

        assertThat(back).isEqualTo(check);
    }

    @Test
    void ebayListingsAreStrippedAndMarkedExpired() throws Exception {
        CompetitionDtos.ListingView l = new CompetitionDtos.ListingView("t", new BigDecimal("10"), "USD", "eBay #1 - s",
                "https://ebay.com/itm/1", true, null, null, null, null);
        CompetitionDtos.Summary sum = new CompetitionDtos.Summary(2, 2, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN);
        CompetitionDtos.Side retail = new CompetitionDtos.Side("q", sum, List.of(
                new CompetitionDtos.ProviderRun("ebay", "eBay", "ok", null, List.of(l)),
                new CompetitionDtos.ProviderRun("serpapi", "Google Shopping", "ok", null, List.of(l))), null);
        CompetitionDtos.BuyCheck check = new CompetitionDtos.BuyCheck("W", "d", "USD", retail, retail,
                Instant.parse("2026-10-01T00:00:00Z"));
        com.fasterxml.jackson.databind.JsonNode tree = json.valueToTree(check);

        assertThat(BuyChecks.stripEbay(tree)).isTrue();
        assertThat(BuyChecks.stripEbay(tree)).as("already expired").isFalse();

        CompetitionDtos.BuyCheck back = json.treeToValue(tree, CompetitionDtos.BuyCheck.class);
        for (CompetitionDtos.Side side : List.of(back.retail(), back.bulk())) {
            assertThat(side.providers().get(0).listings()).isEmpty();
            assertThat(side.providers().get(0).status()).isEqualTo("expired");
            assertThat(side.providers().get(0).message()).isEqualTo(BuyChecks.EBAY_EXPIRED);
            assertThat(side.providers().get(1).listings()).hasSize(1);
            assertThat(side.summary()).usingRecursiveComparison()
                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class).isEqualTo(sum);
        }
    }
}
