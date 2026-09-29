/**
 * Market data: the reference figures every engine reads, kept current from free public feeds.
 *
 * <p>Application module. Two feeds, both refreshed daily (and once at startup) under a
 * ShedLock so only one pod calls out:
 * <ul>
 *   <li><b>Commodity moves</b> from FRED (St. Louis Fed) producer price indices - one series per
 *       {@code commodities.commodity_key}, configured under {@code aatlas.market-data.fred.series}.
 *       The 3-month change of the index becomes {@code pct90}; the source names the series. Needs a
 *       free FRED key; without one the shipped seed figures stay.</li>
 *   <li><b>Exchange rates</b> from Frankfurter (European Central Bank reference rates, no key),
 *       written into {@code fx_rate} as a new day per pair, which is what the currency reader
 *       takes. A currency the ECB does not publish keeps its seeded rate.</li>
 * </ul>
 * Writes go straight to the two reference tables, which have no tenant; every engine reads them
 * through {@code history.Reference} and the currency reference endpoint as before.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "marketdata",
        allowedDependencies = {"common"})
package com.aatlas.marketdata;
