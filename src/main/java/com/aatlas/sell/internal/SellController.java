package com.aatlas.sell.internal;

import com.aatlas.sell.DecisionRecorder.Recorded;
import com.aatlas.sell.OpportunityScoreView;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.ElasticityModelDto;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.ForecastModelDto;
import com.aatlas.sell.internal.dto.PricingDtos.SellDerivationDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.AtpAllocationDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.HoldDecisionDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.SpeedPricingDto;
import com.aatlas.sell.internal.dto.SellAnswerDtos.ApplyResponseDto;
import com.aatlas.sell.internal.dto.SellAnswerDtos.QuoteResponseDto;
import com.aatlas.sell.internal.dto.SellAnswerDtos.ScenarioResponseDto;
import com.aatlas.sell.internal.dto.SellAnswerDtos.SellRecommendationDto;
import com.aatlas.sell.internal.dto.SellAnswerDtos.StarterDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sell recommendation reads, quotes, scenarios, ATP, apply and outcomes.
 *
 * <p>Thin by rule: every answer is a {@link SellService} call shaped for the wire.
 * {@code item}/{@code store} are the frontend's {@code itemNumber}/{@code storeId} (an ERP
 * item number, a store code) - never a row's own uuid.
 */
@RestController
@RequestMapping(path = "/api/v1/sell", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@Tag(name = "Sell", description = "Price recommendation, decision tools, quotes, ATP and outcomes.")
class SellController {

    private final SellService service;

    SellController(SellService service) {
        this.service = service;
    }

    @Operation(summary = "The whole Sell answer for one (item, store)",
            description = "Guardrail-adjusted price, decision score, chain, timeline, forecast, now-vs-wait, "
                    + "liquidation signal, speed tiers and the opportunity chip - everything the Sell screen "
                    + "reads on load, in one response.")
    @GetMapping("/recommendation")
    SellRecommendationDto recommendation(
            @RequestParam @NotBlank String item, @RequestParam @NotBlank String store) {
        return service.recommendation(item, store);
    }

    @Operation(summary = "The \"why this price\" derivation",
            description = "Both tiers, the peer/competitor benchmarks, the observed-price bands, the calc steps "
                    + "and the factor weights.")
    @GetMapping("/recommendation/derivation")
    SellDerivationDto derivation(@RequestParam @NotBlank String item, @RequestParam @NotBlank String store) {
        return service.derivation(item, store);
    }

    @Operation(summary = "Opportunity score, tier and reasons for one (item, store)")
    @GetMapping("/score")
    OpportunityScoreView score(@RequestParam @NotBlank String item, @RequestParam @NotBlank String store) {
        return service.score(item, store);
    }

    @Operation(summary = "The demand forecast at a horizon",
            description = "Not in WAVE2-BRIEF's endpoint list, but platform/forecast.ts is this track's file to "
                    + "port and the golden file covers it; exposed here so it is reachable rather than dead code.")
    @GetMapping("/forecast")
    ForecastModelDto forecast(@RequestParam @NotBlank String item, @RequestParam @NotBlank String store,
            @RequestParam(defaultValue = "sensing") String horizon) {
        return service.forecastModel(item, store, horizon);
    }

    @Operation(summary = "Own-price (sell) or volume-price (buy) elasticity",
            description = "Same rationale as GET /sell/forecast: platform/elasticity.ts is this track's port; "
                    + "exposed for completeness even though WAVE2-BRIEF's endpoint list does not name it.")
    @GetMapping("/elasticity")
    ElasticityModelDto elasticity(@RequestParam @NotBlank String item,
            @RequestParam(defaultValue = "sell") String side, @RequestParam(required = false) String counterparty) {
        return service.elasticityModel(item, side, counterparty);
    }

    @Operation(summary = "Price-move or named-scenario deltas", description = "No write.")
    @PostMapping(path = "/scenario", consumes = MediaType.APPLICATION_JSON_VALUE)
    ScenarioResponseDto scenario(@Valid @RequestBody ScenarioRequest request) {
        return service.scenario(request.item(), request.store(), request.pct(), request.scenario());
    }

    @Operation(summary = "Hold vs sell breakdown over N days")
    @GetMapping("/hold-vs-sell")
    HoldDecisionDto holdVsSell(@RequestParam @NotBlank String item, @RequestParam @NotBlank String store,
            @RequestParam(defaultValue = "30") int days) {
        return service.holdVsSell(item, store, days);
    }

    @Operation(summary = "Urgent/standard/flexible prices under policy")
    @GetMapping("/speed-tiers")
    SpeedPricingDto speedTiers(@RequestParam @NotBlank String item, @RequestParam @NotBlank String store) {
        return service.speedTiers(item, store);
    }

    @Operation(summary = "Price a customer quote", description = "Breakdown + deal price. No write.")
    @PostMapping(path = "/quote", consumes = MediaType.APPLICATION_JSON_VALUE)
    QuoteResponseDto quote(@Valid @RequestBody QuoteRequest request) {
        return service.quote(request.item(), request.store(), request.customerId(), request.qtyOrDefault());
    }

    @Operation(summary = "Available-to-promise allocation")
    @GetMapping("/atp")
    AtpAllocationDto atp(@RequestParam @NotBlank String item, @RequestParam @NotBlank String store) {
        return service.atp(item, store);
    }

    @Operation(summary = "Top three opportunities for the empty state")
    @GetMapping("/starters")
    List<StarterDto> starters() {
        return service.starters();
    }

    @Operation(summary = "Apply the recommended (or an overridden) price",
            description = "Writes a decision and a deal. The decisions module is not in this worktree yet; see "
                    + "com.aatlas.sell.DecisionRecorder for the stand-in and the report for what it replaces.")
    @PostMapping(path = "/apply", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApplyResponseDto apply(@Valid @RequestBody ApplyRequest request) {
        return service.apply(request.item(), request.store(), request.price());
    }

    @Operation(summary = "Record a customer quote",
            description = "Prices the quote and records a decision + deal, same stand-in as POST /sell/apply.")
    @PostMapping(path = "/quotes", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApplyResponseDto quotes(@Valid @RequestBody QuoteRequest request) {
        return service.recordQuote(request.item(), request.store(), request.customerId(), request.qtyOrDefault());
    }

    @Operation(summary = "Last decisions on this (item, store) line",
            description = "Read through the same DecisionRecorder stand-in POST /sell/apply and POST /sell/quotes "
                    + "write through - see its doc comment.")
    @GetMapping("/outcomes")
    List<Recorded> outcomes(@RequestParam @NotBlank String item, @RequestParam @NotBlank String store) {
        return service.outcomes(item, store);
    }
}
