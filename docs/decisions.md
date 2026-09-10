# Decisions made while scaffolding

Short record of the choices that were not obvious, so the next person does not have to rediscover the reasoning — or worse, quietly undo it.

---

## Spring Boot 3.5.16, not 4.1

Boot 4.1 is GA. It was not chosen because springdoc, Spring Modulith and ShedLock all have their Boot 4 lines at milestone quality today, and the OpenAPI generator is load-bearing here — it is what stops the frontend's view models and the backend's DTOs from drifting. 3.5.16 is the newest release where every dependency in this stack is stable *together*.

Nothing in the code is designed around 3.x specifics. When the ecosystem settles, the upgrade is a version bump plus the Spring Framework 7 migration.

---

## Modular monolith, module boundaries enforced at build time

Straight from the blueprint, and worth restating: fifteen microservices would spend the first six months on plumbing. But "modular monolith" is only a real thing if something checks — otherwise the sixteen packages are a filing convention that erodes the first time someone reaches into another module's internals under deadline.

`ModularityTests` is that check. `common` and `config` are declared shared modules; everything else may only touch another module's package-root types.

---

## The Modulith events store is JDBC, not JPA

`spring-modulith-starter-jpa` maps the outbox as a JPA entity, which `ddl-auto: validate` then polices — so the schema of a framework-owned table becomes something this project has to keep in lockstep by hand.

The JDBC variant ships its own `schema-postgresql.sql`, copied verbatim into `V3__event_publication.sql`. Flyway owns the schema, one owner, and a framework upgrade that changes the table is a reviewed migration rather than a surprise at startup.

Same reasoning for `V2__spring_batch.sql`: taken verbatim from `spring-batch-core`, with `spring.batch.jdbc.initialize-schema: never`.

---

## Servlet filters are registered explicitly, never `@Component`

**This one bit during setup, and the failure mode is opaque.**

Spring Modulith's observability module proxies beans that live in application modules so it can trace calls between them. CGLIB cannot override `GenericFilterBean.init()` because it is `final`, so the proxy's `init` runs against uninitialised state, the filter fails to start, and Tomcat comes down with:

```
One or more Filters failed to start. Full details will be found in the appropriate container log file
```

— which names neither the filter nor the cause. The only hint is a `WARN` from `CglibAopProxy` several hundred lines earlier.

`TenantContextFilter` is therefore constructed in `WebConfig` and registered through a `FilterRegistrationBean`. **Any filter added later must do the same.** Explicit ordering is a real benefit too: the tenant filter must run after Spring Security has validated the JWT, and saying so in one place beats an `@Order` plus an assumption.

---

## `uuid_generate_v7()` rather than `gen_random_uuid()`

Random v4 keys scatter inserts across the whole B-tree. For a 24-month import that means random I/O and a bloated index; for the ledger, history and audit log — all read newest-first — it means the rows a query wants are spread over the whole heap.

v7 keys sort by creation time, so inserts stay at the right-hand edge of the index and recent rows cluster. PostgreSQL 18 has this built in, but the function is defined here so the schema also applies to 16 and 17.

---

## Row-level security is wired but off

`aatlas.rls.enabled=false` until the migrations that create the policies exist. The `TenantAwareDataSource` decorator, `app.current_tenant()` and `app.enable_tenant_rls()` are all in place, so switching it on is a config change plus one line per table.

The wiring lands before the policies deliberately: retrofitting the connection-level tenant binding after sixty tables exist is a much larger change than having it ready.

Note that `scripts/create-database.sql` creates a **non-superuser** role. RLS is bypassed by superusers and by the table owner, so developing as one would make the policies silently useless locally and hide a bug that only shows up in production.

---

## Two test phases

`mvn test` runs unit and architecture tests: fast, no Docker, safe to run on every save. `mvn verify` adds `*IT` tests against a real PostgreSQL via Testcontainers.

Real PostgreSQL, not H2 — the schema uses extensions, `jsonb`, partitioning and row-level security, so an H2 test would only prove the migrations apply to something this application never runs on.

The integration tests skip rather than fail where Docker is absent (`@Testcontainers(disabledWithoutDocker = true)`), so a developer without Docker Desktop can still run everything else. CI has Docker and runs the lot.

---

## `archRule.failOnEmptyShould=false`, for now

Most architecture rules currently match no classes, because the modules are empty. Failing on that would mean deleting the rules until the code they guard exists — backwards: the rule should be in place before the first line it governs is written.

**Flip this to `true` once the modules have code**, so a rule that silently stops matching anything (a renamed package, say) is reported instead of quietly passing.

---

## Wave 1: `identity` (rest of auth), `tenant`, `policy`, guardrails

### `tenant_settings` is the source of truth for country and currency

`tenants.country` / `tenants.trading_currency` predate `tenant_settings` (`V4` vs `V5`) and the session, `/me` and every report already read them from `tenants`. Rather than migrate every reader, `V5` adds `tenant_settings` as the row `TenantService` actually writes to, and the same transaction copies the result onto `tenants` (`TenantEntity.mirror`). Two columns can only disagree inside a transaction that then rolls back. If a future module needs the country or currency, it should read `TenantDirectory.get(tenantId)` (backed by `tenants`) rather than reaching into `tenant_settings` directly — one reason less for the copy to matter.

### Currency is no longer pinned to the tenant's country

The frontend supports ten trading currencies (`src/lib/platform/money.ts`), but `V4`'s `tenants_currency_ck` only allowed `USD`/`GBP` — the pair the two supported countries imply. `V5` relaxes that constraint to the full ten and backfills `tenant_settings` from the existing `tenants` rows in the same migration, so `PUT /tenant/settings` can set any of them independently of country (a US company can trade in EUR).

### `role_policy` replaces per-seat constants

`SeatRole` used to carry title, side, level, bulk, guardrails and approval limit as enum constructor arguments — fine until a tenant needs its own buyer limit. `V5` seeds `role_policy` from `seed/personas.json` (`tenant_id null` = platform default) and `SeatRole` shrank back down to the closed set of eight wire values; everything else is `PolicyReader.personaFor(tenantId, role)`, read fresh per request so a tenant override needs no deploy. `identity` depends on `policy` for this (not the other way around): the JWT carries the role's wire value, not the enum, so there is no cycle to break.

### `Persona.isHeadOrDirector()` needed `@JsonIgnore`

Jackson treats any no-arg `isXxx()` method as a bean property, records included — not just the canonical component accessors. Without `@JsonIgnore` this derived helper leaked into `/me` and `/roles` as an extra `headOrDirector` key the frontend's `Persona` type does not have. Caught by hand while curling the running app, not by a test; worth remembering for the next boolean helper added to a record that also serialises.

### `Map.copyOf` does not preserve insertion order

`ReferenceData` built its `countries` and `currencies` lookups with a `LinkedHashMap` and then wrapped them in `Map.copyOf(...)`. `Map.copyOf` (like `Map.of`) salts its hashing per JVM run for flood resistance, so the returned map's iteration order has no relation to the input's — `GET /reference/currencies` came back in a different, wrong order every restart instead of the seed's `USD, GBP, EUR, ...`. Fixed with `Collections.unmodifiableMap`, which does not copy or reorder. `ReferenceIT.currenciesIsPublicAndOrdered` pins the order so this cannot silently come back.

### The JWT decoder now validates against `AatlasClock`, not the system clock

`TokenService` mints `exp`/`iat`/`nbf` from `AatlasClock`, which is exactly right — it is what lets the demo tenant's tokens make sense under a frozen "now". But `NimbusJwtDecoder`'s default timestamp validator checks the *system* clock. The two clocks only ever agreed by coincidence (`local` and `prod` do not fix the clock, so `AatlasClock` tracks the system clock there anyway); the `test` profile freezes "now" at 1 September 2026, so **every** token minted in a test decoded as already-expired the moment anything validated it — signup worked (it never decodes its own token), but any IT that authenticated against a second endpoint failed with `Jwt expired`. `JwtConfig.jwtDecoder` now builds its validator with `JwtTimestampValidator.setClock(aatlasClock.clock())`, so minting and verification ask the same clock the same question. No behaviour change in `local`/`prod`.

### Two scaffold review fixes, both in `JwtConfig`

`/actuator/health` was already reachable unauthenticated — `SecurityConfig.PUBLIC` lists it ahead of the blanket `/actuator/** → ROLE_platform_admin` rule, and Spring Security matches in order, so that one was already correct. The other was not: `JwtConfig.rsaKey` generated a throwaway key pair whenever `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY` were unset, logging a warning but starting anyway — fine for a laptop, wrong for `prod`, where every pod would mint its own key and nothing would agree on token validity across pods. It now throws `IllegalStateException` and refuses to start under `spring.profiles.active=prod` specifically; `local`/`dev`/`test` keep the ephemeral-key fallback.

### `@Email` runs before the service gets to `.strip()`

`SignupRequest`, `LoginRequest` and `ForgotPasswordRequest`'s Javadoc promised padding and case would be normalised — true once a request reaches `SignupService`/`SessionService`, which call `.strip()` — but Jakarta's `@Email` validates the raw bound value first, and its pattern does not tolerate a leading or trailing space. A client that pastes a padded address got "not a valid email address" instead of a normal sign-in. Fixed with a record compact constructor (`email = email.strip()`) on all three, which runs before the accessor bean validation reads from, so the constraint sees the same string the service would have produced anyway. `SignupIT.duplicateEmailIsRejected`, which posts a padded, upper-cased duplicate on purpose, is what caught this.

## Wave 1: `catalog`, `ingest`

## Catalog and ingest: gate reads on the catalogue, not on the data-source row

Group F's tenant reads (`/products`, `/stores`, `/regions`, `/customers`) answer `404 no_catalogue` for a tenant with nothing to show. That gate is `stores.existsByTenantId(tenantId)` — presence of catalogue rows — not "does a `data_sources` row exist". The two usually agree, but `DELETE /data-sources/{id}` deliberately does not delete the catalogue it brought (disconnecting is a statement about future syncs, not a request to forget history already learned), so a tenant that connects the sample dataset and then disconnects it keeps reading its catalogue. That is intentional: the rows are still true, and re-seeding them on a second connect would only produce the same rows again since the seeder is idempotent by tenant. If a future screen needs "does this tenant have an *active* source" as a distinct question from "does it have data", that is a second, cheaper check (`data_sources` existence) layered on top — it should not replace this one.

---

## The sample provisioner is the only writer of the catalogue tables

`stores`, `products`, `product_stores`, `customers` have exactly one writer today: `SampleCatalogueSeeder`, reached only through `ingest`'s `POST /data-sources`. There is deliberately no `POST /products` or `POST /stores` yet (those are P2 in the blueprint, and belong once ERP sync and CSV import exist to race against). Until then, the seeder is called with `Propagation.MANDATORY` — it refuses to run outside a caller's transaction — because a catalogue row and the `data_sources` row that announces it must commit together or not at all; either one appearing without the other would either open a workspace with nothing in it or hide a real catalogue behind a closed gate.

---

## `SellersRule` is a bit-for-bit port, not a rewrite

Which branches have sales history for an item decides every `product_stores` row the sample dataset gets, and the frontend already has an answer for this (`tenantsSellingItem` in `src/lib/mock/catalog.ts`): FNV-1a hash the item number, keep the item's default branch always, keep every other branch unless `(hash >> branchIndex) % 3 == 0`. `catalog.internal.seed.SellersRule` reproduces it exactly — including `Math.imul`'s 32-bit wrap, done in Java with ordinary `int` multiplication, which matches without a helper because Java ints are already 32-bit and wrap the same way. It would have been easy to write something cleaner; it was not, because the demo story (which branches "have no sales of this item") and later golden-file tests only mean something if the set of priceable (item, branch) pairs is identical to the prototype's, not merely plausible. `SellersRuleTest` checks the port against values computed by actually running the frontend's TypeScript in Node — not re-derived in Java — so the two implementations are compared, not just each read against its own assumptions.

---

## Regions on the wire mirror `CountryInfo.regions`, not the derived `MarketRegion`

The frontend has two shapes for the same four regions: `CountryInfo.regions[]` (`key, label, short, subdivisions`, from `locale.ts`) and `MarketRegion[]` (`key, label, name, states`, in `intel/catalog.ts`), which is *derived* from the first by swapping `short` into `label` and `label` into `name`. `GET /api/v1/regions` returns the first shape — `key, label, short` — plus `subdivisions[]` spelled out and `storeIds[]`, because that is what the blueprint's brief for this endpoint names literally, and because sending the raw shape lets a client derive `MarketRegion` itself (as the frontend does) without the API needing to know about a frontend-only presentation type.

---

## IT classes get their own Spring context, to survive `SignupRateLimiter`

`SignupRateLimiter` (identity) allows 10 `/auth/signup` calls per client IP per hour, in-process, no test override. Spring's test-context cache shares one `ApplicationContext` — and therefore one limiter instance — across every `@SpringBootTest` class in a Maven run whose configuration matches, which is every `*IT` class using the plain `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")` trio. `SignupIT` alone uses most of that budget; a naive one-signup-per-`@Test` catalog/ingest suite would blow through the rest immediately; run together under one `mvn verify`, they would trip `429 too_many_signups` regardless of which suite runs first.

`DataSourceIT` and `CatalogIT` each carry a `@TestPropertySource(properties = "spring.application.name=...")` with a distinct, otherwise-inert value. Spring's context cache key includes inlined test properties, so each class gets a fresh `ApplicationContext` — and a fresh limiter — decoupled from `SignupIT` and from each other. Each class also keeps its own signup count well under 10 regardless (`DataSourceIT`: one signup per test, 8 total; `CatalogIT`: one shared, connected tenant for every read-only test via `@BeforeAll`, plus one dedicated unconnected tenant for the gate test — 2 total), so the isolation is a second line, not a way to paper over an unbounded suite.

This is a real seam other builders will hit the same way: any `*IT` class that calls `/auth/signup` more than a couple of times risks tripping the limiter once every module's suite runs in the same `mvn verify`. Either every signup-heavy suite adopts this isolation trick, or `SignupRateLimiter` grows a way to reset or raise its ceiling under `@ActiveProfiles("test")` — worth raising with whoever owns `identity` before wave 2.

## Wave 1: `suppliers`

## Suppliers module (V8)

Ports `intel/suppliers.ts`, `intel/terms.ts` and `intel/buy2.ts`'s `supplierRisk` to Java, with seven tables under `suppliers`/`suppliers.internal`.

**Certifications live on `supplier_terms`, not `suppliers`.** The frontend's `SupplierProfile.certifications` is really a profile fact, but the table that already exists for one-to-one-with-a-supplier data with room for order-mechanics columns (MOQ, order multiple, quality PPM, response hours — none of which are in the ported TypeScript, but all of which the seed JSON's `record` carries and a later Buy-side compare will want) is `supplier_terms`. Putting a second `certifications` column on `suppliers` for the same list would be two places that can drift; `SupplierProfileView` reads it from `supplier_terms` when assembling the wire shape either way.

**`supplier_terms`, `supplier_ratings` and `supplier_risk` key off the supplier's own id**, not a fresh uuid: there is exactly one row per supplier by definition, and a second key would only be something to keep in sync. `UuidV7Generator` honours an id assigned explicitly (see its javadoc — imports and tests already rely on this), so the constructor just sets `supplierId = supplier.getId()` before insert rather than fighting `@GeneratedValue`.

**The seeder stamps `tenant_id` explicitly, never via `TenantScopedEntity`'s `@PrePersist`.** The public seam other modules call, `SupplierPanelSeeder.seedForTenant(tenantId)`, is invoked by an `@ApplicationModuleListener` running on a Modulith worker thread, which does not inherit `TenantContext`'s `InheritableThreadLocal` — that only propagates at thread *creation*, and the listener's thread pool is created once, long before any tenant exists. Every row the seeder writes sets `tenantId` by hand for this reason. The read/write paths reachable from a real request (the controller and service) still rely on `TenantContext`, exactly per convention, since `TenantContextFilter` has already bound it there.

**The trigger is a local stand-in, on purpose.** `com.aatlas.ingest.SampleDataConnected` — the real event — is owned by another builder's worktree and is not visible here. `SuppliersSeedRequested(tenantId)` is a record in this module's own package root that `SuppliersSeedListener` listens for instead; `POST /api/v1/suppliers/seed` (director only) calls the same seeder directly so the path is exercisable today. `SuppliersSeedRequested`'s javadoc carries a `TODO(merge)` naming exactly what to retarget.

**`GET /suppliers/{id}` and friends take the frontend's id (`sup-2`, `cus-f26j4f`), never the row's uuid.** Every seeded figure for a supplier — its defect rate, its stock position, its terms, its risk — hashes that string, so it is what the frontend already has and what every screen navigates by; the internal uuid never appears on the wire.

**Test-only clock override in `SuppliersIT`.** `application-test.yml` freezes `AatlasClock` at 2026-09-01 for golden-file determinism; `TokenService` stamps a JWT's `iat`/`exp` from that clock, but the `JwtDecoder` bean validates them against the real system clock. In this sandbox the real clock has since passed 2026-09-01 plus the 15-minute access-token TTL, so any token minted under the frozen test clock decodes as already expired — reproducible from a clean checkout with `-Dit.test=SignupIT` alone (no suppliers code involved): `SignupIT.tokenCarriesTenantAndRole` fails the same way, as does `SignupIT.duplicateEmailIsRejected` on an unrelated pre-existing issue. Neither is caused by this module, and neither is touched by it; `SuppliersIT` instead overrides `aatlas.clock.fixed=false` for its own Spring context via `@TestPropertySource`, which is why its own token-authenticated calls work in the same run. Fixed centrally since: `wave1-auth`'s `JwtConfig.jwtDecoder` now binds `JwtTimestampValidator` to `AatlasClock` (see "Wave 1: `identity`..." above), so `SuppliersIT`'s own `@TestPropertySource` override is redundant after this merge but harmless.

**Order-mechanics columns (`moq`, `order_multiple`, `quality_ppm`, `response_hours`) are populated but not on the wire.** The seeded panel gets them from `seed/suppliers.json`'s `record`; a supplier added from a lookup gets simple deterministic defaults (`moq`/`order_multiple` = 1, `quality_ppm` derived from the defect rate, `response_hours` seeded). None of the four are part of `CommercialTerms` in `intel/terms.ts`, so `GET /{id}/terms` does not return them — they are there for whichever module builds the Buy-side compare next.

## Wave 2: `bulk`, `integrations`, `assistant` (V13)

This track carries the most cross-module stand-ins of any wave-2 track, by the brief's own admission — `sell`, `buy`, `decisions`, `insights` and `catalog` are all owned by tracks whose worktrees this one cannot see. Everything below is written for the lead to retarget by hand at merge; every stand-in also carries a `TODO(merge)` comment at its implementation naming the module it expects to be replaced by.

### `catalog` exposes no reader, so `bulk` and `assistant` each read the seed files directly

`catalog`'s only public type is `CatalogSeeding` — built for `ingest` to copy the seed into a tenant's tables, not for another module to query. Rather than invent a reader that might not match whatever `catalog` eventually publishes, `com.aatlas.bulk.internal.BulkSeedCatalog` and `com.aatlas.assistant.internal.AssistantCatalog` each load `seed/products.json` and `seed/stores.json` (US branches only) straight from the classpath, the same files `catalog` itself loads and the same static lists the frontend's `mock/catalog.ts` exports as `PRODUCTS`/`TENANTS`. This is faithful (the fixtures are genuinely static reference data, not tenant-mutated — the only data source today is "sample") and sidesteps the missing reader entirely, at the cost of two small, near-identical loaders rather than one shared one, and of not reflecting a tenant's own catalogue should a non-sample source ever diverge from it. `TODO(merge): replace both with the catalog module's public reader, if/when it gets one.`

`bulk` additionally reads `seed/suppliers.json` directly for the same reason, standing in for the `suppliers` module's internal panel.

### `bulk`'s engine stand-ins: what is exact and what is not

- **`PricingEngine`** (`com.aatlas.bulk.internal`) is an **exact port** of `mock/pricing.ts`'s `getPricingModel` — not a stand-in for another module, since that file is shared foundation every intelligence layer calls and nothing in this worktree had ported it yet. Pinned field for field against `golden/pricing-model.json` (all 84 rows) by `PricingEngineGoldenTest`.
- **`SellLineReader`/`SellLine`** (`com.aatlas.bulk`, impl `SellLineReaderImpl`) stand in for the `sell` module's `getSellIntel`. `TODO(merge): replace with the sell module's public SellIntel reader.` What's real: `cost`, `currentPrice`, `recommended`, `stretchPrice`, `marginFloor` (all straight from `PricingEngine`), `monthlyUnits`/`inventoryUnits`/`weeksOfCover` (exact ports of `sell.ts`'s `monthlyUnitsFor`/`inventoryFor`), the sell-side elasticity coefficient (exact port of `platform/elasticity.ts`'s one relevant line), and `confidence` (exact port of the confidence formula). What's left out: the six-step pricing chain, the twelve-month timeline, the now-vs-wait recommendation, and full competitor rows — none of it is read by `bulkSellPlan`'s own arithmetic or by `src/app/app/sell/bulk/page.tsx` (checked directly: it reads only `description`, `monthlyUnits`, `marginFloor` off a line's `intel`). Because every field `bulkSellPlan` *does* use is exact, `BulkSellEngineGoldenTest` pins the whole basket in `golden/bulk.json` to the cent — bulk sell has no fidelity gap worth flagging.
- **`BuyLineReader`/`BuyLine`/`SupplierEval`** (`com.aatlas.bulk`, impl `BuyLineReaderImpl`) stand in for the `buy` module's `getBuyIntel`. `TODO(merge): replace with the buy module's public BuyIntel/SupplierEval reader.` What's real: given a landed cost, `evaluate()` is an exact port of `buy.ts`'s own reliability/lead-time/quality/fulfilment/MOQ/commercial-terms adjustments and risk call, reading each of the 8 suppliers' real seeded facts (country, lead time, OTIF, defect rate, and — read straight from `seed/suppliers.json`'s `terms` object rather than re-derived — commercial terms) via an exact port of `terms.ts`'s value formulas (`TermsMath`). What's simplified: how a supplier's *landed* cost is derived in the first place. The real `getBuyIntel` calls `buildBuyRecommendation` (`platform/api.ts`), which prices a full freight-lane model (mode, gateway, inbound/duty percent by country, inland percent and days by region, from `logistics.ts`). That model belongs to `buy`, not reproduced here; `syntheticQuote()` instead derives an ex-works cost from the supplier's seeded price index and a flat, explicitly-simplified freight/duty percentage (domestic vs. import). The target cost the real engine reaches through a negotiation-benchmark chain is approximated the way the chain's own note describes it — "best observed plus a third of the gap to the benchmark" — computed from this reader's own panel. **Consequence:** unlike bulk sell, bulk buy's numbers do not match the TypeScript's to the cent; `BulkBuyEngineTest` checks shape and internal consistency (five strategies in order, a valid recommendation, lowest-cost genuinely cheapest) rather than golden values, and says so in its own doc.
- **Buy-side priceability is item-only, not per-branch.** `BuyLineReaderImpl` first gated priceability the same way the sell side does — `product.sellable() && tenantsSellingItem(item).contains(destinationBranch)` — and three of `golden/bulk.json`'s own seven `bulkBuyPlan` items (`HRD772310`, `HRD335590`, `HRD107744`) failed that check at region south's primary branch (100959), even though the golden file itself proves the real `getBuyIntel` finds them priceable there. Confirmed by hand-computing `tenantsSellingItem` in Python against the seed data before changing anything, rather than guessing: a regional buy does not require that the specific landing branch already has *retail* sales history for the item the way a sell recommendation does; it only needs the product to be a real, purchasable SKU (`hasSales !== false`). The per-branch check is dropped for buy accordingly - priceable is now `product.sellable()` alone.
- **`DecisionRecorder`** (`com.aatlas.bulk`, impl `DecisionRecorderImpl`) stands in for the `decisions` module's ledger. `TODO(merge): replace with the decisions module's recorder.` At the time this was built, `decisions` was an empty `package-info.java` placeholder in every worktree reachable from here, *including `wave2-history`'s own branch* (checked directly via `git ls-tree wave2-history` before writing this), so there was no real interface to peek at. `bulk_decision`/`bulk_deal` (migration V13) exist only for this reason and should be dropped once `decisions` owns the write path; a bulk apply is a kind of decision like a single-item price change or an RFQ award; it should end up in the same ledger, not a table `bulk` keeps forever.

### `assistant`'s intents: which are real, which are simplified stand-ins

`com.aatlas.assistant.internal.AssistantService` ports `assistant.ts`'s `ask()` regex-for-regex. Per intent:

| Intent | Status | Notes |
|---|---|---|
| Raise prices | **Real** | Loops every sellable item × branch through `SellLineReader`, exactly as the TypeScript loops `getSellIntel`. |
| Which supplier | **Simplified stand-in** for `getBuyIntel` + `procurementPlan` (`buy2.ts`, not ported — out of scope, owned by `buy`). Picks a supplier from `BuyLineReader`'s real panel by the question's priority word (cost/speed/reliability/balanced) rather than running the real multi-factor planner (`urgencyDays`, weighted scoring). |
| What should I liquidate | **Simplified stand-in** for `liquidationSignal` (`sell2.ts`, not ported — owned by `sell`). Flags a line when it is overstocked (16+ weeks of cover) and demand is not high — the same *shape* of signal the real function uses — but the erosion estimate (12% of inventory value) is a stated flat assumption, not the real function's elasticity-based projection. |
| Hold vs. sell | **Simplified stand-in** for `holdVsSell` (`sell2.ts`, not ported). Extrapolates `SellLine`'s own demand-move percent into a rough 30-day price change rather than the real function's forecast, and prices a month of holding at a stated 8%-a-year carrying cost. |
| What changed | **Simplified stand-in** for `getOverview` (`overview.ts`, the `insights` module's engine, not ported — and there is no `change_event` ledger yet regardless). Aggregates the same demand signal every other intent reads across the flagship basket into an honest but approximate "since yesterday" summary; does not attempt the real change-event feed. |
| Demand by region | **Real, but a simplified aggregate.** Averages `SellLine.demandPct()` — the same per-item demand signal `geo.ts`'s `allRegions` itself reads — across every store in a region, rather than reproducing that file's own region-level scaling formula. Close enough that the "fastest-growing region" answer is a genuine computation, not fabricated, but the exact percentage will not match `geo.ts`'s. |
| A store | **Real** | `storeCity`/`storeLabel`, trivial. |
| Fallback | **Real** | The six suggested questions, verbatim. |

`GET /assistant/suggestions`'s persona-aware reordering (sell-side seats see raise-prices/liquidate/hold-vs-sell first; buy-side seats see which-supplier first) is new for the API — the TypeScript always returns the same order — and reads `policy`'s real, merged `PolicyReader`/`Persona`, not a stand-in.

### `assistant` depends on `bulk`'s public types, not a third copy of the same stand-in

`AssistantService` reuses `com.aatlas.bulk.SellLineReader`/`BuyLineReader` directly rather than porting `getSellIntel`/`getBuyIntel` a third time. Both modules are owned by this same track in this same worktree, so this is the intended reuse, not a shortcut — duplicating the stand-in a second time inside `assistant` would only be a second place for it to drift from `bulk`'s. At merge time, when `sell`/`buy` land for real, `bulk`'s `SellLineReader`/`BuyLineReader` get retargeted once and `assistant`'s dependency follows automatically; note the two are logically separate retargets (`bulk`'s own `TODO(merge)` comments, plus updating `assistant`'s package-info, which currently documents the `bulk`-mediated dependency explicitly so the lead does not mistake it for a direct one).

### `integration_connection.config` is plain `jsonb`, not encrypted at rest

The brief allows this explicitly for a demo: "config for a demo is not a live secret." A real deployment would want `@Convert`-based AES-GCM (the blueprint's own note for this column) before a tenant's actual ERP credentials ever reached this table; nothing here should be mistaken for that.

### `mcp_permission.requires_approval` is stored per tenant but not currently editable

Migration V13 gives `mcp_permission` its own `requires_approval` column, matching the blueprint's table shape, so a tenant could in principle tighten a permission above the catalogue's default. `IntegrationsService.updatePermissions` only ever writes `enabled` today, because the frontend's own `McpState` (`integrations.ts`) only tracks `permissions: Record<string, boolean>` — there is no UI for editing `requiresApproval` to port against. The column is there for when there is.

### `assistant_question` is new: no existing table fit

The brief allowed adding one minimal history table if nothing else covered it, and nothing did — `decisions` (which might one day carry a generic activity log) was the empty placeholder discussed above. Kept deliberately narrow: `tenant_id`, `user_id`, `question`, `asked_at`. No answer payload is stored; a history row is a prompt to re-ask, not a cache of the response.
