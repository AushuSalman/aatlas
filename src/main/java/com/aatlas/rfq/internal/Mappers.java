package com.aatlas.rfq.internal;

import com.aatlas.rfq.Rfq;
import com.aatlas.rfq.RfqInvite;
import com.aatlas.rfq.RfqQuote;
import com.aatlas.rfq.RfqStatus;
import java.util.List;

final class Mappers {

    private Mappers() {
    }

    static RfqStatus toStatus(RfqEntity.Status status) {
        return RfqStatus.fromWire(status.name());
    }

    static RfqInvite toInvite(RfqInviteEntity e) {
        return new RfqInvite(e.getSupplierId(), e.getName(), e.getCountry(), e.getRoute(),
                e.getExpectedLanded().doubleValue(), e.getLeadDays(), e.getOnTimePct().doubleValue(),
                e.getRiskLevel(), e.getSentAt(), e.getStatus().name());
    }

    static RfqQuote toQuote(RfqQuoteEntity e) {
        return new RfqQuote(e.getId(), e.getSupplierId(), e.getName(), e.isDeclined(),
                e.getQuotedLanded() == null ? null : e.getQuotedLanded().doubleValue(), e.getCurrency(),
                e.getLeadDays(), e.getValidUntil(), e.getPaymentTerms(), e.getNote(),
                e.getVsExpectedPct() == null ? null : e.getVsExpectedPct().doubleValue(), e.getReceivedAt(),
                e.getEnteredBy(), e.isSimulated());
    }

    static Rfq toRfq(RfqEntity e, List<RfqInviteEntity> invites, List<RfqQuoteEntity> quotes) {
        return new Rfq(e.getId(), e.getRef(), e.getCreatedAt(), e.getItemNumber(), e.getItemName(),
                e.getRegionKey(), e.getRegionLabel(), e.getDestinationId(), e.getDestinationLabel(), e.getQty(),
                e.getRequiredDays(), e.getUrgency(), e.getPriority(), e.getIncoterm(), e.getPaymentTerms(),
                e.getNotes(), e.getMessage(), invites.stream().map(Mappers::toInvite).toList(),
                quotes.stream().map(Mappers::toQuote).toList(), toStatus(e.getStatus()), e.getClosesAt(),
                e.getAwardedSupplierId(), e.getAwardedAt(), e.getDecisionId(), e.getCreatedBy());
    }
}
