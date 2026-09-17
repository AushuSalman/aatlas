package com.aatlas.bulk.internal;

import com.aatlas.bulk.BuyLine;
import com.aatlas.bulk.BuyLineReader;
import com.aatlas.bulk.CommercialTerms;
import com.aatlas.bulk.SupplierEval;
import com.aatlas.buy.BuyIntel;
import com.aatlas.buy.BuyIntelReader;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link BuyLineReader} over {@code buy.BuyIntelReader} - the real seam into the buy
 * module's own recommendation engine (effective cost, incumbent, target cost, every
 * supplier evaluation). {@code buy.SupplierEval}/{@code buy.CommercialTerms} carry exactly
 * the same fields as bulk's own frozen wire shapes ({@link SupplierEval},
 * {@link CommercialTerms}), so {@link #toBulk} is a straight field-for-field copy, not a
 * re-derivation.
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
                intel.destinationLabel(), intel.qty(), intel.currentCost(), intel.targetCost(),
                intel.savingPerUnit(), intel.savingPct(), intel.annualVolume(), suppliers, incumbent,
                recommendedSupplier, cheapestQuoted);
    }

    private static SupplierEval toBulk(com.aatlas.buy.SupplierEval s) {
        if (s == null) {
            return null;
        }
        List<SupplierEval.Adjustment> adjustments = s.adjustments().stream()
                .map(a -> new SupplierEval.Adjustment(a.label(), a.amount()))
                .toList();
        return new SupplierEval(s.supplierId(), s.name(), s.country(), s.quoted(), s.landed(), s.effective(),
                s.freightAndDuty(), s.leadDays(), s.otifPct(), s.defectPct(), s.fulfilmentPct(), s.terms(),
                toBulk(s.commercial()), s.penaltyRecoveryPerUnit(), s.moq(), s.meetsMoq(), s.relationshipYears(),
                adjustments, s.isIncumbent(), s.recommended(), s.risk(), s.riskNote());
    }

    private static CommercialTerms toBulk(com.aatlas.buy.CommercialTerms t) {
        if (t == null) {
            return null;
        }
        return new CommercialTerms(t.creditDays(), t.termsLabel(), t.earlyPayDiscountPct(), t.earlyPayDays(),
                t.latePenaltyPctPerWeek(), t.latePenaltyCapPct(), t.warrantyMonths(), t.quoteValidityDays(),
                t.incoterm(), t.invoiceAccuracyPct(), t.capacityUnitsMonth());
    }
}
