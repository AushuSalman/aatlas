# Golden fixtures for the intelligence engines

Recorded outputs of the TypeScript engines in `aatlas/src/lib`, so the Java port can be
proven equal to the cent. Every row is `{fn, input, output}`: `fn` is the exported
function, `input` its arguments keyed by parameter name (in declaration order), `output`
its return value exactly as returned - numbers unrounded, strings verbatim.

Generated 2026-09-10T13:18:06Z by
`scratchpad/golden/gen_golden.cjs` under Node v22.12.0 (ICU 76.1),
from `src/lib` transpiled to CommonJS with TypeScript `transpileModule` (ES2020, CommonJS)
by `scratchpad/build/transpile.js`. Regenerate with:

    cd <scratchpad> && node build/transpile.js && node golden/gen_golden.cjs

## Fixture clock

Everything is measured against `FIXTURE_NOW = 2026-09-01T09:20:00.000Z`
(`src/lib/mock/pricing.ts`). Derived: `TODAY = 2026-09-01`, `LEDGER_START = 2024-08-01`,
`LEDGER_MONTHS = 26`, `RATES_AS_OF = 2026-09-01`. No output depends on the wall clock.

## Files

| File | Source | Functions | Rows | Size | Layout |
|---|---|---|---:|---:|---|
| pricing-model.json | src/lib/mock/pricing.ts | getPricingModel | 84 | 124 KB | pre-existing; plain PricingModel objects, not {fn,input,output} |
| sell-intel.json | src/lib/intel/sell.ts | getSellIntel | 84 | 363 KB | indent 1 |
| sell-decisions.json | src/lib/intel/sell2.ts | applyGuardrails, sellDecisionScore, liquidationSignal, speedPricing, holdVsSell, sellWhatIf, allocateInventory | 840 | 820 KB | indent 1 |
| opportunity-score.json | src/lib/intel/score.ts | opportunityScore | 84 | 60 KB | indent 1 |
| buy-recommendation.json | src/lib/platform/api.ts | buildBuyRecommendation | 135 | 809 KB | indent 1 |
| buy-intel.json | src/lib/intel/buy.ts | getBuyIntel | 180 | 3.35 MB | indent 1 |
| procurement-plan.json | src/lib/intel/buy2.ts | procurementPlan, supplierRisk | 26 | 598 KB | indent 1 |
| bulk.json | src/lib/intel/bulk.ts | bulkSellPlan, bulkBuyPlan | 2 | 144 KB | indent 1 |
| geo.json | src/lib/intel/geo.ts | allRegions, allStores | 2 | 73 KB | indent 1 |
| demographics.json | src/lib/intel/demographics.ts | getDemographics | 7 | 46 KB | indent 1 |
| overview.json | src/lib/intel/overview.ts | getOverview | 1 | 81 KB | indent 1 |
| history.json | src/lib/intel/history.ts, src/lib/platform/api.ts | getHistory, buildImpact | 2 | 148 KB | indent 1 |
| procurement-analytics.json | src/lib/platform/procurement.ts | resolveRange, computeBuyAnalytics, PURCHASE_ORDERS.slice | 5 | 967 KB | indent 1 |
| forecast-elasticity.json | src/lib/platform/forecast.ts, src/lib/platform/elasticity.ts | getForecastModel, getElasticityModel | 26 | 64 KB | indent 1 |
| rand.json | src/lib/platform/data.ts (rand, randRange, randInt, pick), src/lib/mock/catalog.ts (hashString, seeded, seededRange, tenantsSellingItem) | fnv1a-constants, hashString, tenantsSellingItem, rand, randRange, randInt, pick, seeded, seededRange | 1045 | 119 KB | indent 1 |

Notes per file:

- **sell-decisions.json**: Ten calls per priceable pair. `intel` is the getSellIntel row of sell-intel.json for the same pair. `g` is DEFAULT_GUARDRAILS passed explicitly; conversionPct 70 is the default second argument of sellDecisionScore.
- **buy-recommendation.json**: All 15 products (the three PIM-only ones come back priceable:false) x 9 destinations.
- **buy-intel.json**: Three arguments only: destinationId is left undefined, so the engine lands the buy at primaryStoreForRegion(regionKey).
- **procurement-plan.json**: `intel` is getBuyIntel(HRD118902, south, 2400) (a row of buy-intel.json). supplierRisk takes a RiskInput; the four fields recorded are the ones it reads, taken from intel.suppliers[i].
- **bulk.json**: Baskets are what the pages open with: sell = every item priceable at 100959 (7); buy = every sellable item whose getBuyIntel(item, south, 1) is priceable with savingPct > 0 (7); qtyMultiplier 1 is the page's default horizon.
- **demographics.json**: The first row is the Insights page default filter; each later row changes one field of it.
- **overview.json**: Empty browser state: decisions is [] and adoption comes from seeded deals only.
- **history.json**: Seeded deals only: recorded is [] and decisions is [].
- **procurement-analytics.json**: PURCHASE_ORDERS has 794 rows (newest first); TODAY=2026-09-01, LEDGER_START=2024-08-01, LEDGER_MONTHS=26. The first 50 rows are the 50 most recent orders.
- **forecast-elasticity.json**: Flagship pair plus HRD304148@100349 (cheap fitting) and HRD983377@100933 (equipment): every horizon; elasticity on both sides with the store, null and the incumbent supplier as counterparty. One PIM-only item per function for the priceable:false branch.
- **rand.json**: Every salt literal grep-able from src/lib (template salts instantiated) x four representative keys, plus hashString on edge-case strings and every item number, the no-salt form, and curated randRange/randInt/pick calls with the ranges and lists the engines actually use.

## Conventions

- **Derived arguments.** An input value shaped `{fn, ...args}` (e.g. `intel: {fn: "getSellIntel", itemNumber, storeId}`)
  means "the return value of that function with those arguments"; it is reproduced in full in the file for that function.
- **Enumeration.** The 84 priceable (item, store) pairs are `SELLABLE_PRODUCTS x TENANTS` (catalogue order, product-major)
  filtered on `getPricingModel(item, store).priceable`; they are the same 84 rows, in the same order, as pricing-model.json.
  Regions are enumerated in `MARKET_REGIONS` order: west, north, south, east. Products are the catalogue's 15 in order; the last three
  (HRD900001-3) have no sales history.
- **Numbers** are JSON doubles exactly as JavaScript produced them (shortest round-trip form). Compare structurally; object key order is
  not significant. Every engine rounds with `Math.round(n * 10^k) / 10^k`; JS `Math.round` rounds half toward +infinity
  (`Math.round(-2.5) = -2`), which Java's `Math.round(double)` matches and `RoundingMode.HALF_UP` does not for negatives.
- **Strings** were produced with the trading currency USD and locale en-US: `fmtMoney` gives `$1,234.56` (`Number.toLocaleString('en-US')`,
  two decimals), `fmtCompact` gives `$1.2k` / `$3.4M`, integer counts use `toLocaleString('en-US')` grouping. Negative signs inside prose are
  U+2212 MINUS SIGN, not ASCII hyphen; ranges use U+2013 EN DASH; some labels use the star U+2605 and arrows U+2191/U+2193. Compare as UTF-8.
- **Dates** are ISO `yyyy-mm-dd` strings computed in UTC from the fixture clock.

## JSON fidelity

JSON cannot carry NaN, Infinity, -0 or undefined. The generator scanned every row; what it found:

- history.json: 181 x undefined (key dropped) - e.g. [0].output.rows[0].customer, [0].output.rows[1].customer, [0].output.rows[2].customer
- opportunity-score.json: 1 x -0 (written as 0) - e.g. [72].output.signals.priceGapPct

A -0 is written as 0; a Java port producing -0.0 should be compared numerically (`==`), not with `Double.equals`.

## Browser state, and how it was neutralised

The engines run in a browser and several read `window.localStorage`. Generation ran with a fake `window` whose storage was
empty and instrumented; every read returned `null`, so each reader took its documented default, and no write happened
(the generator aborts on one). Keys consulted, with the reader and the default that applied:

| localStorage key | Reads | Reader | Default used | Reaches |
|---|---:|---|---|---|
| aatlas.decisions.v1 | 2 | decisions.readDecisions() | [] (no decisions) | getHistory().decisions, getOverview().decisions |
| aatlas.locale.v1 | 2 | locale.activeCountry() and money.displayCurrency() (both cached after the first read) | country 'US', currency 'USD' | TENANTS (US branch list), MARKET_REGIONS, STORE_MAP_XY, geo CLIMATE - all module-level; every fmtMoney/fmtCompact string in chain notes, reasons, tradeoffs, negotiation messages |
| aatlas.recorded.v1 | 145 | recorded.readRecorded() | [] (no recorded deals) | buildImpact().recorded and .deals -> getHistory, getOverview, getStoreIntel adoption |
| aatlas.suppliers.v1 | 1 | suppliers.readCustomSuppliers() | [] (seeded 8-supplier panel only) | api.activeSuppliers() -> buildBuyRecommendation quotes -> getBuyIntel, procurementPlan, bulkBuyPlan, geo, demographics, overview; buy2.supplierProfile ratings |

Functions whose output would change with browser state, all fixed to the defaults above: `buildBuyRecommendation`, `getBuyIntel`,
`procurementPlan`/`scoreSuppliers` (custom suppliers), `buildImpact`, `getHistory`, `getOverview`, `getStoreIntel`/`allStores`/`allRegions`
(recorded deals, decisions), `speedPricing`/`applyGuardrails` (guardrails, passed explicitly), and every function that formats money or
depends on `TENANTS`/`MARKET_REGIONS` (locale and currency). The Java port should treat these as the seeded/default state.

## The hash every number comes from

`rand(key, salt)` in `src/lib/platform/data.ts` and `seeded(key, salt)` in `src/lib/mock/catalog.ts` are the same function:

    h = 0x811c9dc5                       // FNV-1a 32-bit offset basis
    for each UTF-16 code unit c of (key + "::" + salt):
        h ^= c
        h = Math.imul(h, 0x01000193)     // 32-bit wrapping multiply, FNV prime
    hashString = Math.abs(h | 0)         // signed int32, then absolute value
    rand       = (hashString % 100000) / 100000
    randRange  = min + rand * (max - min)
    randInt    = floor(randRange(key, salt, min, max + 0.999))
    pick       = list[floor(rand * list.length) % list.length]

Port pitfalls, each pinned by rows in rand.json: the multiply wraps at 32 bits (`Math.imul`); `h | 0` reinterprets as a signed int
before `Math.abs`, and `Math.abs(Integer.MIN_VALUE)` is negative in Java (JS returns 2147483648) - guard it; iteration is over UTF-16
code units, so an astral character (`🇺🇸`) is four units and `Düsseldorf` is ten; the modulus is applied to a non-negative int and the
division is in double precision. `randInt(key, salt, min, max)` uses `max + 0.999`, not `max + 1`. The default salt is the empty string,
which still contributes the `::` separator. `tenantsSellingItem` (which decides the priceable pairs) uses `(hashString(item) >> i) % 3`
on the 31-bit absolute value with a signed shift.
