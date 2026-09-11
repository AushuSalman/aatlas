package com.aatlas.sell.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.sell.DecisionRecorder;
import com.aatlas.sell.DecisionRecorder.RecordRequest;
import com.aatlas.sell.DecisionRecorder.Recorded;
import com.aatlas.sell.OpportunityScoreView;
import com.aatlas.sell.OpportunityScores;
import com.aatlas.sell.internal.catalog.CatalogGateway;
import com.aatlas.sell.internal.catalog.CatalogRefs.CustomerRef;
import com.aatlas.sell.internal.catalog.CatalogRefs.ProductRef;
import com.aatlas.sell.internal.dto.DealDtos.DealQuoteDto;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.ElasticityModelDto;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.ForecastModelDto;
import com.aatlas.sell.internal.dto.PricingDtos.SellDerivationDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.AtpAllocationDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.CustomerProfileDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.GuardrailCheckDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.HoldDecisionDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.SellWhatIfDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.SpeedPricingDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.SpeedTierDto;
import com.aatlas.sell.internal.dto.SellAnswerDtos.ApplyResponseDto;
import com.aatlas.sell.internal.dto.SellAnswerDtos.QuoteResponseDto;
import com.aatlas.sell.internal.dto.SellAnswerDtos.ScenarioResponseDto;
import com.aatlas.sell.internal.dto.SellAnswerDtos.SellRecommendationDto;
import com.aatlas.sell.internal.dto.SellAnswerDtos.StarterDto;
import com.aatlas.sell.internal.dto.SellDtos.ScenarioResultDto;
import com.aatlas.sell.internal.dto.SellDtos.SellIntelDto;
import com.aatlas.sell.internal.engine.AtpEngine;
import com.aatlas.sell.internal.engine.DealEngine;
import com.aatlas.sell.internal.engine.ElasticityEngine;
import com.aatlas.sell.internal.engine.ForecastEngine;
import com.aatlas.sell.internal.engine.PricingEngine;
import com.aatlas.sell.internal.engine.Sell2Engine;
import com.aatlas.sell.internal.engine.SellEngine;
import com.aatlas.sell.internal.policy.GuardrailValues;
import com.aatlas.sell.internal.policy.GuardrailsGateway;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the ported engines into the answers the Sell screen and its endpoints need.
 * Everything is computed on demand in the request thread - no snapshot, no cache - reading
 * catalogue rows already in Postgres via {@link CatalogGateway} and the tenant's guardrails
 * via {@link GuardrailsGateway}.
 */
@Service
public class SellService {

    /** The raw engine answer, the guardrail check, and the price actually shown - together, once. */
    private record Answer(SellIntelDto raw, GuardrailCheckDto check, SellIntelDto intel) {
    }

    private final CatalogGateway catalog;
    private final GuardrailsGateway guardrails;
    private final PricingEngine pricing;
    private final SellEngine sell;
    private final Sell2Engine sell2;
    private final OpportunityScores scores;
    private final ForecastEngine forecast;
    private final ElasticityEngine elasticity;
    private final AtpEngine atp;
    private final DealEngine deal;
    private final DecisionRecorder decisions;

    public SellService(CatalogGateway catalog, GuardrailsGateway guardrails, PricingEngine pricing, SellEngine sell,
            Sell2Engine sell2, OpportunityScores scores, ForecastEngine forecast, ElasticityEngine elasticity,
            AtpEngine atp, DealEngine deal, DecisionRecorder decisions) {
        this.catalog = catalog;
        this.guardrails = guardrails;
        this.pricing = pricing;
        this.sell = sell;
        this.sell2 = sell2;
        this.scores = scores;
        this.forecast = forecast;
        this.elasticity = elasticity;
        this.atp = atp;
        this.deal = deal;
        this.decisions = decisions;
    }

    private void requireCatalogue() {
        if (catalog.allStores().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "no_catalogue",
                    "This workspace has no catalogue yet. Connect a data source first.");
        }
    }

    private String commodityKey(String itemNumber) {
        return catalog.findProduct(itemNumber).map(ProductRef::commodity).orElse("none");
    }

    private Answer answerFor(String itemNumber, String storeId) {
        SellIntelDto raw = sell.getSellIntel(itemNumber, storeId);
        if (!raw.priceable()) {
            return new Answer(raw, null, raw);
        }
        GuardrailCheckDto check = sell2.applyGuardrails(raw, guardrails.current());
        SellIntelDto intel = check.adjusted() ? SellEngine.withAdjustedPrice(raw, check.finalPrice()) : raw;
        return new Answer(raw, check, intel);
    }

    @Transactional(readOnly = true)
    public SellRecommendationDto recommendation(String itemNumber, String storeId) {
        requireCatalogue();
        Answer a = answerFor(itemNumber, storeId);
        OpportunityScoreView opportunity = scores.score(itemNumber, storeId);
        if (!a.raw().priceable()) {
            return new SellRecommendationDto(a.intel(), null, null, null, null, null, opportunity);
        }
        return new SellRecommendationDto(a.intel(), a.check(), sell2.sellDecisionScore(a.intel(), 70),
                sell2.liquidationSignal(a.intel()), sell2.speedPricing(a.intel(), guardrails.current()),
                sell2.holdVsSell(a.intel(), 30, commodityKey(itemNumber)), opportunity);
    }

    @Transactional(readOnly = true)
    public SellDerivationDto derivation(String itemNumber, String storeId) {
        requireCatalogue();
        return pricing.getSellDerivation(itemNumber, storeId);
    }

    @Transactional(readOnly = true)
    public OpportunityScoreView score(String itemNumber, String storeId) {
        requireCatalogue();
        return scores.score(itemNumber, storeId);
    }

    @Transactional(readOnly = true)
    public ForecastModelDto forecastModel(String itemNumber, String storeId, String horizon) {
        requireCatalogue();
        return forecast.getForecastModel(itemNumber, storeId, horizon);
    }

    @Transactional(readOnly = true)
    public ElasticityModelDto elasticityModel(String itemNumber, String side, String counterpartyId) {
        requireCatalogue();
        return elasticity.getElasticityModel(itemNumber, side, counterpartyId);
    }

    @Transactional(readOnly = true)
    public ScenarioResponseDto scenario(String itemNumber, String storeId, BigDecimal pct, String scenarioKey) {
        requireCatalogue();
        if (pct == null && (scenarioKey == null || scenarioKey.isBlank())) {
            throw ApiException.badRequest("validation_failed", "Give either pct or scenario.");
        }
        Answer a = answerFor(itemNumber, storeId);
        if (!a.raw().priceable()) {
            throw ApiException.notFound("priceable line", itemNumber + "@" + storeId);
        }
        ScenarioResultDto priceMove = pct != null ? SellEngine.runScenario(a.intel(), pct.doubleValue()) : null;
        SellWhatIfDto scenario = (scenarioKey != null && !scenarioKey.isBlank())
                ? sell2.sellWhatIf(a.intel(), scenarioKey, commodityKey(itemNumber)) : null;
        return new ScenarioResponseDto(priceMove, scenario);
    }

    @Transactional(readOnly = true)
    public HoldDecisionDto holdVsSell(String itemNumber, String storeId, int days) {
        requireCatalogue();
        Answer a = answerFor(itemNumber, storeId);
        if (!a.raw().priceable()) {
            throw ApiException.notFound("priceable line", itemNumber + "@" + storeId);
        }
        return sell2.holdVsSell(a.intel(), days, commodityKey(itemNumber));
    }

    @Transactional(readOnly = true)
    public SpeedPricingDto speedTiers(String itemNumber, String storeId) {
        requireCatalogue();
        Answer a = answerFor(itemNumber, storeId);
        if (!a.raw().priceable()) {
            throw ApiException.notFound("priceable line", itemNumber + "@" + storeId);
        }
        return sell2.speedPricing(a.intel(), guardrails.current());
    }

    @Transactional(readOnly = true)
    public AtpAllocationDto atp(String itemNumber, String storeId) {
        requireCatalogue();
        Answer a = answerFor(itemNumber, storeId);
        if (!a.raw().priceable()) {
            throw ApiException.notFound("priceable line", itemNumber + "@" + storeId);
        }
        return atp.allocate(a.intel());
    }

    @Transactional(readOnly = true)
    public List<StarterDto> starters() {
        requireCatalogue();
        List<StarterDto> rows = new ArrayList<>();
        for (ProductRef p : catalog.sellableProducts()) {
            for (var store : catalog.allStores()) {
                SellIntelDto s = sell.getSellIntel(p.itemNumber(), store.storeCode());
                if (!s.priceable() || s.upliftPct().doubleValue() < 4) {
                    continue;
                }
                rows.add(new StarterDto(p.itemNumber(), store.storeCode(), s.name(), s.storeLabel(), s.upliftPct()));
            }
        }
        return rows.stream()
                .sorted(Comparator.comparing(StarterDto::pct).reversed())
                .limit(3)
                .toList();
    }

    @Transactional(readOnly = true)
    public QuoteResponseDto quote(String itemNumber, String storeId, String customerId, int qty) {
        requireCatalogue();
        Answer a = answerFor(itemNumber, storeId);
        if (!a.raw().priceable()) {
            throw ApiException.notFound("priceable line", itemNumber + "@" + storeId);
        }
        return buildQuote(a, itemNumber, storeId, customerId, qty);
    }

    private QuoteResponseDto buildQuote(Answer a, String itemNumber, String storeId, String customerId, int qty) {
        SellDerivationDto rec = pricing.getSellDerivation(itemNumber, storeId);
        CustomerRef customer = (customerId == null || customerId.isBlank()) ? null
                : catalog.findCustomer(customerId).orElse(null);
        GuardrailValues g = guardrails.current();

        DealQuoteDto dealQuote = deal.quoteForDeal(a.intel().recommended(), rec.aggressive().price(),
                rec.marginFloor(), rec.recommendedTier(), customer, qty);

        CustomerProfileDto profile = sell2.customerProfile(customer);
        SpeedPricingDto sp = sell2.speedPricing(a.intel(), g);
        SpeedTierDto tier = sp.tiers().stream().filter(t -> t.key().equals(profile.fulfilment())).findFirst()
                .orElse(sp.tiers().get(1));

        BigDecimal requestedPct = dealQuote.volumeBreakPct().add(dealQuote.customerDiscountPct())
                .setScale(2, RoundingMode.HALF_UP);
        boolean capped = requestedPct.doubleValue() > g.maxDiscountPct();
        double discounted = capped
                ? round2(dealQuote.bookOptimal().doubleValue() * (1 - g.maxDiscountPct() / 100))
                : dealQuote.optimal().doubleValue();
        double dealPrice = round2(discounted + tier.premiumAbs().doubleValue());
        // Not part of any ported TS interface (sell-quote.tsx computes it inline, unrounded,
        // for fmtMoney(profit, 0) to round at display time); rounded here since this response
        // shape is this track's own design, not a wire contract pinned by a golden fixture.
        double profit = round2((dealPrice - a.intel().cost().doubleValue()) * qty);

        return new QuoteResponseDto(dealQuote, profile, tier, capped, requestedPct, BigDecimal.valueOf(dealPrice),
                BigDecimal.valueOf(profit));
    }

    private static double round2(double n) {
        return Math.round(n * 100.0) / 100.0;
    }

    // -- Writes (stand-in DecisionRecorder) --------------------------------------------------

    @Transactional
    public ApplyResponseDto apply(String itemNumber, String storeId, BigDecimal priceOverride) {
        requireCatalogue();
        Answer a = answerFor(itemNumber, storeId);
        if (!a.raw().priceable()) {
            throw ApiException.notFound("priceable line", itemNumber + "@" + storeId);
        }
        SellIntelDto intel = a.intel();
        BigDecimal applied = priceOverride != null ? priceOverride
                : (a.check() != null ? a.check().finalPrice() : intel.recommended());

        double impactMonthly = SellEngine.runScenario(intel, intel.upliftPct().doubleValue()).profitDelta()
                .doubleValue() / 12;
        var decisionScore = sell2.sellDecisionScore(intel, 70);

        RecordRequest req = new RecordRequest("sell", itemNumber, storeId, intel.storeLabel(), intel.recommended(),
                applied, intel.monthlyUnits(), intel.cost(), intel.currentPrice(),
                intel.name() + " at " + intel.storeLabel(), intel.currentPrice() + " -> " + applied
                        + " margin " + intel.expectedMarginPct() + "% score " + decisionScore.total() + "/100",
                BigDecimal.valueOf(Math.round(impactMonthly)), "/month", null);
        Recorded recorded = decisions.record(req);
        return new ApplyResponseDto(recorded);
    }

    @Transactional
    public ApplyResponseDto recordQuote(String itemNumber, String storeId, String customerId, int qty) {
        requireCatalogue();
        Answer a = answerFor(itemNumber, storeId);
        if (!a.raw().priceable()) {
            throw ApiException.notFound("priceable line", itemNumber + "@" + storeId);
        }
        SellIntelDto intel = a.intel();
        QuoteResponseDto q = buildQuote(a, itemNumber, storeId, customerId, qty);
        CustomerRef customer = (customerId == null || customerId.isBlank()) ? null
                : catalog.findCustomer(customerId).orElse(null);
        String customerName = customer != null ? customer.name() : "Walk-in";

        BigDecimal deal = q.dealPrice();
        RecordRequest req = new RecordRequest("sell", itemNumber, storeId, intel.storeLabel(), deal, deal, qty,
                intel.cost(), intel.currentPrice(), intel.name() + " quoted to " + customerName,
                qty + " units at " + deal + " - " + q.customerProfile().label() + ", "
                        + q.speedTier().label().toLowerCase(java.util.Locale.ROOT) + " fulfilment",
                BigDecimal.valueOf(Math.round(deal.subtract(intel.currentPrice()).doubleValue() * qty)),
                "this deal", customerName);
        Recorded recorded = decisions.record(req);
        return new ApplyResponseDto(recorded);
    }

    @Transactional(readOnly = true)
    public List<Recorded> outcomes(String itemNumber, String storeId) {
        return decisions.outcomesFor(itemNumber, storeId);
    }
}
