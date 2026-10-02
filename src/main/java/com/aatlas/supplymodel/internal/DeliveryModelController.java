package com.aatlas.supplymodel.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.supplymodel.DeliveryModels;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant's delivery model: its status and per-supplier report, a forecast for one order,
 * and training on demand. Thin by rule; who may train is the service's decision.
 */
@RestController
@Validated
@RequestMapping(path = "/api/v1/delivery-model", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Delivery model", description = "The machine-learned lead-time and late-delivery forecaster, one per tenant, trained on its own purchase orders.")
class DeliveryModelController {

    private final DeliveryModels models;

    DeliveryModelController(DeliveryModels models) {
        this.models = models;
    }

    @Operation(summary = "The model's status and per-supplier report",
            description = "Whether and when it trained, on how many received orders, and for each supplier its record, "
                    + "the held-out error in days and Brier score against the supplier's own trailing record, whether "
                    + "the model beat it (usable), and its predicted lead time and chance of a late delivery.")
    @GetMapping
    DeliveryModels.Status status() {
        return models.status();
    }

    @Operation(summary = "Train now",
            description = "Loads the received purchase orders of the last three years, fits the slip and late forests, "
                    + "scores them on the latest fifth of the orders and stores them. Only seats whose persona may "
                    + "change the pricing model.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Trained, or visited and not trainable; see trained and note."),
        @ApiResponse(responseCode = "403", description = "This seat may not train the model (code not_allowed).")
    })
    @PostMapping("/train")
    DeliveryModels.Status train() {
        return models.train();
    }

    @Operation(summary = "Forecast one order",
            description = "Days the order is expected to take and the chance it is late, for a supplier (its id or "
                    + "name), item, branch and quantity. promisedDays is the lead time the supplier quotes; absent, what "
                    + "it promised for this item before, else across its orders.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The forecast."),
        @ApiResponse(responseCode = "404", description = "No trained model, or the supplier is not in it.")
    })
    @GetMapping("/forecast")
    DeliveryModels.Forecast forecast(@RequestParam @NotBlank String supplier, @RequestParam(required = false) String item,
            @RequestParam(required = false) String store, @RequestParam(defaultValue = "1") int qty,
            @RequestParam(required = false) Integer promisedDays) {
        if (qty < 0) {
            throw ApiException.badRequest("validation_failed", "qty must not be negative; 0 means a typical order");
        }
        return models.forecast(supplier, item, store, qty, promisedDays)
                .orElseThrow(() -> ApiException.notFound("delivery model supplier", supplier));
    }
}
