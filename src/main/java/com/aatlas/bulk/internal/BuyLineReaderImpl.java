package com.aatlas.bulk.internal;

import com.aatlas.bulk.BuyLine;
import com.aatlas.bulk.BuyLineReader;
import com.aatlas.bulk.CommercialTerms;
import com.aatlas.bulk.SupplierEval;
import com.aatlas.buy.BuyIntel;
import com.aatlas.buy.BuyIntelReader;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link BuyLineReader} over {@code buy.BuyIntelReader} - the real seam into the buy
 * module's own recommendation engine (effective cost, incumbent, target cost, every
 * supplier evaluation). {@code buy.SupplierEval}/{@code buy.CommercialTerms} carry the same
 * fields as bulk's own frozen wire shapes ({@link SupplierEval}, {@link CommercialTerms}),
 * except buy's are nullable where a quote, term or count is not on file; {@link #toBulk}
 * folds a missing figure to zero, matching bulk's own "0 means not quoted" convention
 * (see {@code BulkBuyEngine.quoted}), rather than re-deriving anything.
 */
@Component
public class BuyLineReaderImpl implements BuyLineReader {

    private final BuyIntelReader buyIntel;

    public BuyLineReaderImpl(BuyIntelReader buyIntel) {
        this.buyIntel = buyIntel;
    }

    @Override
    public BuyLine read(String itemNumber, String regionKey, int qty) {
        int effectiveQty = Math.max(1, qty);
        BuyIntel intel = buyIntel.getBuyIntel(itemNumber, regionKey, effectiveQty, null);
        String name = intel.name() != null ? intel.name() : itemNumber;
        String regionLabel = intel.regionLabel() != null ? intel.regionLabel() : BuyMath.regionLabel(regionKey);

        if (!intel.priceable()) {
            return new BuyLine(itemNumber, name, false, regionKey, regionLabel, intel.destinationId(),
                    intel.destinationLabel(), effectiveQty, 0, 0, 0, 0, 0, List.of(), null, null, null);
        }

        List<SupplierEval> suppliers = intel.suppliers().stream().map(BuyLineReaderImpl::toBulk).toList();
        SupplierEval incumbent = toBulk(intel.incumbent());
        SupplierEval recommendedSupplier = toBulk(intel.recommendedSupplier());
        SupplierEval cheapestQuoted = toBulk(intel.cheapestQuoted());

        return new BuyLine(itemNumber, name, true, regionKey, regionLabel, intel.destinationId(),
                intel.destinationLabel(), intel.qty(), nz(intel.currentCost()), nz(intel.targetCost()),
                nz(intel.savingPerUnit()), nz(intel.savingPct()), nz(intel.annualVolume()), suppliers, incumbent,
                recommendedSupplier, cheapestQuoted);
    }

    private static SupplierEval toBulk(com.aatlas.buy.SupplierEval s) {
        if (s == null) {
            return null;
        }
        List<SupplierEval.Adjustment> adjustments = s.adjustments().stream()
                .map(a -> new SupplierEval.Adjustment(a.label(), nz(a.amount())))
                .toList();
        return new SupplierEval(s.supplierId(), s.name(), s.country(), nz(s.quoted()), nz(s.landed()),
                nz(s.effective()), nz(s.freightAndDuty()), nz(s.leadDays()), nz(s.otifPct()), nz(s.defectPct()),
                nz(s.fulfilmentPct()), s.terms(), toBulk(s.commercial()), nz(s.penaltyRecoveryPerUnit()),
                nz(s.moq()), Boolean.TRUE.equals(s.meetsMoq()), nz(s.relationshipYears()), adjustments,
                s.isIncumbent(), s.recommended(), s.risk(), s.riskNote());
    }

    private static CommercialTerms toBulk(com.aatlas.buy.CommercialTerms t) {
        if (t == null) {
            return null;
        }
        return new CommercialTerms(nz(t.creditDays()), t.termsLabel(), nz(t.earlyPayDiscountPct()),
                nz(t.earlyPayDays()), nz(t.latePenaltyPctPerWeek()), nz(t.latePenaltyCapPct()),
                nz(t.warrantyMonths()), nz(t.quoteValidityDays()), t.incoterm(), nz(t.invoiceAccuracyPct()),
                nz(t.capacityUnitsMonth()));
    }

    /** A figure not on file folds to zero, matching bulk's own "0 means not quoted" convention. */
    private static double nz(BigDecimal v) {
        return v == null ? 0 : v.doubleValue();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
