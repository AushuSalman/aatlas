package com.aatlas.approvals;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** {@code GET /approvals/limits}: the signed-in seat's own ceiling and who signs off above it. */
@Schema(name = "ApprovalLimits")
public record ApprovalLimits(BigDecimal limit, String approver) {
}
