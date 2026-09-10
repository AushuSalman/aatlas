package com.aatlas.buy;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Whether to buy now or wait 30 days, and what that is worth. The frontend's {@code BuyNowVsWait}.
 *
 * <p>{@code waitOption} carries the JSON name {@code wait} - the frontend's own field name -
 * because a record component named {@code wait} collides with {@link Object#wait()}.
 */
@Schema(name = "BuyNowVsWait")
public record BuyNowVsWait(
        String recommendation, String reason, Now now, @JsonProperty("wait") Wait waitOption, String driver) {

    public record Now(double cost, double total) {
    }

    public record Wait(int days, double cost, double deltaPerUnit, double deltaTotal, String risk) {
    }
}
