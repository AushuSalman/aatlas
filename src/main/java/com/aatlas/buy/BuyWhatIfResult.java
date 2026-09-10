package com.aatlas.buy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The answer to one what-if scenario on a procurement plan. The frontend's {@code WhatIfResult}. */
@Schema(name = "BuyWhatIfResult")
public record BuyWhatIfResult(String title, List<Row> rows, String note) {

    public record Row(String label, String value, String tone) {
    }
}
