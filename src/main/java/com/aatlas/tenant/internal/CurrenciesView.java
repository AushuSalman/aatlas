package com.aatlas.tenant.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The currency picker's data: the shape of {@code seed/currencies.json} and of the
 * frontend's {@code CURRENCIES}, with {@code perUsd} taken from {@code fx_rate}.
 *
 * @param asOf the newest rate date in the set
 */
@Schema(name = "Currencies")
record CurrenciesView(LocalDate asOf, List<CurrencyView> currencies) {

    @Schema(name = "Currency")
    record CurrencyView(String code, String symbol, String name, String locale, BigDecimal perUsd, int dp) {
    }
}
