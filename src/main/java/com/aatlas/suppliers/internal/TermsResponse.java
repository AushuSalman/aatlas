package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** What a supplier's terms mean for this order: the numbers, the labels, and what to flag. */
@Schema(name = "SupplierTermsResponse")
record TermsResponse(CommercialTerms terms, Labels labels, List<String> watchOuts) {

    @Schema(name = "SupplierTermsLabels")
    record Labels(String credit, String earlyPay, String latePenalty) {
    }
}
