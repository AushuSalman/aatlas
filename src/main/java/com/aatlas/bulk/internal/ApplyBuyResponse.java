package com.aatlas.bulk.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "ApplyBuyStrategyResponse")
record ApplyBuyResponse(UUID decisionId, String appliedStrategy, BulkBuyDtos.PlanView plan) {
}
