package com.aatlas.demandmodel.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.demandmodel.DemandModels;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant's demand model: its status and per-pair report, a forecast for one pair at a
 * price, and training on demand. Thin by rule; who may train is the service's decision.
 */
@RestController
@Validated
@RequestMapping(path = "/api/v1/demand-model", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Demand model", description = "The machine-learned demand forecaster, one per tenant, trained on its own sales.")
class DemandModelController {

    private final DemandModels models;

    DemandModelController(DemandModels models) {
        this.models = models;
    }

    @Operation(summary = "The model's status and per-pair report",
            description = "Whether and when it trained, on how many weekly rows and item-branch pairs, and for each pair "
                    + "the held-out error against the naive baseline, whether the model beat it (usable), the probed "
                    + "price sensitivity, and the chain's own regression for comparison.")
    @GetMapping
    DemandModels.Status status() {
        return models.status();
    }

    @Operation(summary = "Train now",
            description = "Loads up to two years of weekly sales for the busiest pairs, fits the forest, scores it on the "
                    + "held-out weeks and stores it. Only seats whose persona may change the pricing model.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Trained, or visited and not trainable; see trained and note."),
        @ApiResponse(responseCode = "403", description = "This seat may not train the model (code not_allowed).")
    })
    @PostMapping("/train")
    DemandModels.Status train() {
        return models.train();
    }

    @Operation(summary = "Forecast one pair at a price",
            description = "Units next week for the item at the branch at that price, with the pair's probed price "
                    + "sensitivity and whether the model may be used for it.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The forecast."),
        @ApiResponse(responseCode = "404", description = "No trained model, or the pair is not in it.")
    })
    @GetMapping("/forecast")
    DemandModels.Forecast forecast(@RequestParam @NotBlank String item, @RequestParam @NotBlank String store,
            @RequestParam BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw ApiException.badRequest("validation_failed", "price must be greater than zero");
        }
        return models.forecast(item, store, price)
                .orElseThrow(() -> ApiException.notFound("demand model pair", item + "@" + store));
    }
}
