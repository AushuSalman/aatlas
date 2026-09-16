package com.aatlas.rfq;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Which live quote to take, under the same weights the Buy screen scores suppliers with. The
 * frontend's {@code recommendQuote} result.
 */
@Schema(name = "RfqRecommendation")
public record RfqRecommendation(String supplierId, String name, String reason, Map<String, Integer> scores) {
}
