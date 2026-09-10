package com.aatlas.bulk.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "ApplySellStrategyResponse")
record ApplySellResponse(UUID decisionId, String appliedStrategy, BulkSellDtos.PlanView plan) {
}
