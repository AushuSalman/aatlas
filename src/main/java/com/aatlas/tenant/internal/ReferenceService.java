package com.aatlas.tenant.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Joins the static half of a currency (from the seed file) to its live rate (from
 * {@code fx_rate}). Falls back to the seed's rate for a pair the table does not have yet,
 * so a currency added to the picker before the nightly job knows it still renders.
 */
@Service
class ReferenceService {

    private static final String BASE = "USD";

    private final ReferenceData reference;
    private final FxRateRepository fxRates;

    ReferenceService(ReferenceData reference, FxRateRepository fxRates) {
        this.reference = reference;
        this.fxRates = fxRates;
    }

    @Transactional(readOnly = true)
    CurrenciesView currencies() {
        Map<String, FxRateEntity> latest = new HashMap<>();
        for (FxRateEntity rate : fxRates.findLatestFor(BASE)) {
            latest.put(rate.getKey().getQuote(), rate);
        }

        LocalDate asOf = reference.currenciesAsOf();
        List<CurrenciesView.CurrencyView> out = new ArrayList<>();
        for (ReferenceData.Currency currency : reference.currencies()) {
            FxRateEntity rate = latest.get(currency.code());
            BigDecimal perUsd = rate == null ? currency.perUsd() : rate.getRate();
            if (rate != null && rate.getKey().getAsOf().isAfter(asOf)) {
                asOf = rate.getKey().getAsOf();
            }
            out.add(new CurrenciesView.CurrencyView(currency.code(), currency.symbol(), currency.name(),
                    currency.locale(), plain(perUsd), currency.dp()));
        }
        return new CurrenciesView(asOf, List.copyOf(out));
    }

    /** {@code 0.78000000} reads as {@code 0.78}, {@code 25100.00000000} as {@code 25100}. */
    static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
