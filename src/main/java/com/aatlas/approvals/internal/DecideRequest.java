package com.aatlas.approvals.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/** Body for {@code POST /approvals/{id}/approve} and {@code /reject} - a note is always optional. */
@Schema(name = "ApprovalDecideRequest")
record DecideRequest(String note) {
}
