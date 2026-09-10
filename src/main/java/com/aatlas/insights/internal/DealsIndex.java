package com.aatlas.insights.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The seeded deal ledger ({@code seed/deals.json}, 181 rows), read directly rather than
 * reproduced.
 *
 * <p>{@code platform/data.ts}'s {@code DEALS} constant - the sell and buy history every
 * "recommendation adoption" figure in the prototype is built from - is itself a pure,
 * deterministic function of the catalogue and the pricing engine
 * ({@code buildSellDeals}/{@code buildBuyDeals}), so {@code deals.json} (one of wave 1's
 * shared seed files, generated straight from that TypeScript) is exactly what re-running
 * the formula here would produce. Reading it is simpler and safer than re-deriving
 * {@code buildSellDeals}'s own count/date/quantity formulas a second time for the one
 * figure this module needs from it: a branch's adoption rate in {@code getStoreIntel}, and
 * the sell side of Overview's KPI footer.
 *
 * <p>Not the same thing as "recent decisions" (a user's own recorded prices, which really
 * is the {@code decisions} module's cross-track data and is stood in for with an empty
 * list below) - this is the static historical ledger the demo ships with, present before
 * any user has recorded anything, the same way {@code commodities.json} or
 * {@code stores.json} are.
 */
@Component
class DealsIndex {

    private final List<DealRow> sellDeals;

    DealsIndex() {
        List<DealRow> all = read();
        this.sellDeals = all.stream().filter(d -> "sell".equals(d.side())).toList();
    }

    private static List<DealRow> read() {
        try (InputStream in = new ClassPathResource("seed/deals.json").getInputStream()) {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(in, new TypeReference<List<DealRow>>() {
            });
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read seed/deals.json", ex);
        }
    }

    /** {@code impact.deals} filtered to one branch's counterparty name, sell side only. */
    record Adoption(int total, int followed) {
        boolean any() {
            return total > 0;
        }
    }

    Adoption adoptionFor(String counterpartyName) {
        int total = 0;
        int followed = 0;
        for (DealRow d : sellDeals) {
            if (d.counterparty().equals(counterpartyName)) {
                total++;
                if (d.followed()) {
                    followed++;
                }
            }
        }
        return new Adoption(total, followed);
    }

    /** {@code summarise('sell', all)} - the network-wide sell side of the impact summary. */
    record SellImpact(int deals, int followedDeals, double gained, double lost) {
    }

    SellImpact sellImpact() {
        int deals = sellDeals.size();
        int followed = 0;
        double gained = 0;
        double lost = 0;
        for (DealRow d : sellDeals) {
            if (d.followed()) {
                followed++;
            }
            gained += d.gain();
            lost += d.lost();
        }
        return new SellImpact(deals, followed, Fmt.round2(gained), Fmt.round2(lost));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DealRow(
            String id, String date, String side, String itemNumber, String counterparty,
            boolean followed, double gain, double lost) {
    }
}
