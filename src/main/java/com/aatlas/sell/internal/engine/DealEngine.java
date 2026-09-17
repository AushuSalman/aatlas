package com.aatlas.sell.internal.engine;

import static com.aatlas.sell.internal.engine.Round.round2;
import static com.aatlas.sell.internal.engine.Wire.bd;

import com.aatlas.history.Catalogue.CustomerRef;
import com.aatlas.sell.internal.dto.DealDtos.DealQuoteDto;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Port of {@code src/lib/platform/deal.ts}: {@code volumeBreakFor}, {@code quoteForDeal}.
 * Not in WAVE2-BRIEF's file list for this track, but {@code POST /sell/quote} needs it - a
 * customer and a quantity turning the item recommendation into a deal price - so it is
 * ported here rather than left out.
 */
@Component
public class DealEngine {

    private record Break(int min, double pct) {
    }

    private static final Break[] VOLUME_BREAKS = {
        new Break(0, 0), new Break(25, 2), new Break(100, 4.5), new Break(500, 7), new Break(2000, 9),
    };

    record VolumeBreak(double pct, int min, int nextAt, double nextPct, boolean hasNext) {
    }

    static VolumeBreak volumeBreakFor(int qty) {
        Break current = VOLUME_BREAKS[0];
        for (Break b : VOLUME_BREAKS) {
            if (qty >= b.min()) {
                current = b;
            }
        }
        Break next = null;
        for (Break b : VOLUME_BREAKS) {
            if (b.min() > qty) {
                next = b;
                break;
            }
        }
        return next == null
                ? new VolumeBreak(current.pct(), current.min(), 0, 0, false)
                : new VolumeBreak(current.pct(), current.min(), next.min(), next.pct(), true);
    }

    /**
     * {@code quoteForDeal}. {@code customer} may be null (walk-in / no account).
     *
     * <p>Takes the four inputs {@code quoteForDeal} actually reads from a {@code
     * SellRecommendation} rather than that whole shape, because the Sell screen quotes
     * against the GUARDRAIL-ADJUSTED optimal price - {@code {...rec.optimal, price:
     * intel.recommended}} in {@code sell-quote.tsx} - not the raw engine number, and patching
     * one field of an immutable record is more ceremony than it is worth for four numbers.
     */
    public DealQuoteDto quoteForDeal(BigDecimal optimalPriceAdjusted, BigDecimal aggressivePrice,
            BigDecimal marginFloor, String recommendedTierOfLine, CustomerRef customer, int qty) {
        VolumeBreak brk = volumeBreakFor(qty);
        double custPct = customer != null ? customer.agreedDiscountPct().doubleValue() : 0;
        double multiplier = (1 - brk.pct() / 100) * (1 - custPct / 100);

        double optimalPrice = optimalPriceAdjusted.doubleValue();
        double aggressivePriceD = aggressivePrice.doubleValue();
        double marginFloorD = marginFloor.doubleValue();

        double rawOptimal = optimalPrice * multiplier;
        double rawAggressive = aggressivePriceD * multiplier;
        double optimal = round2(Math.max(rawOptimal, marginFloorD));
        double aggressive = round2(Math.max(rawAggressive, marginFloorD));
        boolean clampedByFloor = rawOptimal < marginFloorD - 0.005;

        String tier = (customer != null && "A".equals(customer.tier())) ? "optimal" : recommendedTierOfLine;
        double recommended = tier.equals("aggressive") ? aggressive : optimal;

        double effectiveDiscountPct = clampedByFloor
                ? round2(((optimalPrice - optimal) / optimalPrice) * 100)
                : round2((1 - multiplier) * 100);

        return new DealQuoteDto(qty, bd(brk.pct()), brk.hasNext() ? brk.nextAt() : null,
                brk.hasNext() ? bd(brk.nextPct()) : null, bd(custPct), bd(optimalPrice), bd(aggressivePriceD),
                bd(optimal), bd(aggressive), bd(recommended), tier, clampedByFloor, bd(effectiveDiscountPct));
    }
}
