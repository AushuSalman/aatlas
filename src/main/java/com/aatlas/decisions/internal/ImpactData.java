package com.aatlas.decisions.internal;

import com.aatlas.decisions.DealRecord;
import java.util.List;

/** Ports {@code platform/api.ts}'s {@code ImpactData}. */
public record ImpactData(ImpactSummary sell, ImpactSummary buy, List<DealRecord> deals, List<DealRecord> recorded) {
}
