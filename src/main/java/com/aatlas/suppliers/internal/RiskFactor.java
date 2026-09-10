package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/** One line of the risk breakdown, rendered verbatim: a label, a value, and whether it is good news. */
@Schema(name = "RiskFactor")
record RiskFactor(String label, String value, boolean good) {
}
