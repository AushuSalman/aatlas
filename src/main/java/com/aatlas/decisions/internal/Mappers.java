package com.aatlas.decisions.internal;

import com.aatlas.decisions.DealRecord;
import com.aatlas.decisions.Decision;

/** Entity &lt;-&gt; public-API record conversions, shared by the recorder and the history/deals reads. */
final class Mappers {

    private Mappers() {
    }

    static Decision toDecision(DecisionEntity e) {
        return new Decision(e.getId(), e.getCreatedAt(), e.kindPublic(), e.getTitle(), e.getItemNumber(),
                e.getScope(), e.getRecommended(), e.getApplied(), e.getExpectedImpact(), e.getImpactLabel(),
                e.getDetail(), e.getCount(), e.statusPublic());
    }

    static DealRecord toDealRecord(DealEntity e) {
        return new DealRecord(e.getDealKey(), e.getDealDate(), e.getSide(), e.getItemNumber(), e.getDescription(),
                e.getCounterparty(), e.getQty(), e.getCost(), e.getBaselinePrice(), e.getSuggestedPrice(),
                e.getActualPrice(), e.isFollowed(), e.getGain(), e.getLost(),
                e.isRecorded() ? Boolean.TRUE : null, e.getCustomer(), e.getRecordedAt(), e.getBelowFloor(),
                e.getDestinationId(), e.getDecisionId());
    }
}
