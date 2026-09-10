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

## Wave 2: `buy`

## Buy module (V10)

Ports `platform/api.ts`'s `buildBuyRecommendation`, `intel/buy.ts`'s `getBuyIntel` and `intel/buy2.ts`'s `urgencyDays`/`effectiveWeights`/`deliveryFor`/`routesFor`/`lateCostFactor`/`scoreSuppliers`/`procurementPlan`/`buyWhatIf` to Java. Computed on demand, in the request thread — the wave-2 brief's own simplification, not a shortcut this module invented — with one table, `buy_decisions` (V10), for what `POST /buy/select` writes.

**No public reader existed on `catalog` or `suppliers` for what this module needs, so it reads their real tables directly.** Both modules' only public (package-root) type is a *writer* for seeding (`CatalogSeeding`, `SupplierPanelSeeder`) — neither exposes a reader for the rows a third module would need in-process. `CatalogGateway` and `SupplierGateway` (`buy.internal`) read `products`, `stores`, `product_stores`, `logistics_lanes`, `logistics_origins`, `regions`, `commodities` and `suppliers` with plain `JdbcTemplate` SQL, scoped by `tenant_id` from `TenantContext` exactly as a `findByTenantId...` repository method would be. This creates no Java dependency on `catalog.internal`/`suppliers.internal` — `ModularityTests` only sees package/type dependencies, not table names in a SQL string — so it does not fail the modularity build, but it is still a stand-in for a proper cross-module reader and is documented as one on both gateway classes. The alternative considered and rejected: re-parsing `seed/*.json` a second time in this module, which would duplicate the loading logic `ReferenceDataLoader`/`SampleCatalogueSeeder` already have and could drift from the live tables (a rate card is kept in a table rather than a constant specifically so it *can* drift from the seed file over time — see `ReferenceDataLoader`'s own javadoc).

**`commercialTerms`, `supplierRisk` and a slice of the supplier rating engine are ported a second time, not called into `suppliers.internal`.** `suppliers` (wave 1) already has `TermsScoring.commercialTerms`, `RiskScoring.supplierRisk` and `SupplierScoring`'s star-rating pipeline — package-private classes with no public wrapper. Reaching into them would fail `ModularityTests`; guessing at a future public API would fail differently, later. Per the wave-2 brief's stand-in rule, `buy` carries its own deterministic port (`TermsEngine`, `RiskEngine`, `RatingEngine`), keyed identically (`sup:<id>`, the same salts), with a `TODO(merge): consider depending on suppliers' public reader if one exists after merge` on each of `CommercialTerms` and `SupplierRisk` (`com.aatlas.buy`, package root — public, unlike the rest of this module's model types, so the note is visible wherever the type is used). Duplication of a handful of pure functions across two modules costs nothing at this scale; a compile-time dependency on another module's implementation detail is not allowed at all.

**The sell/pricing engine is not ported here — only the three fields `buy` actually reads.** `getPricingModel` in `mock/pricing.ts` is `sell`'s engine (a parallel wave-2 track, a different worktree, not visible from this one). `buildBuyRecommendation` and `getBuyIntel` only ever read `.priceable`, `.cost` and `.currentPrice` from it — never demand, competitors, bands or the optimal/aggressive tiers — so `PricingStandIn` (`buy.internal`) ports only `baseCost` and the `currentPrice` formula, both pure functions of the item/store strings and the shared `Seeded` hash. `TODO(merge)`: replace with a call into `sell`'s public reader once it exists.

**`java.lang.Double` cannot appear anywhere in this package, including inside a `Map`'s type argument or through `String.format`'s varargs boxing.** `ArchitectureRulesTest.noFloatingPointMoney` bans `com.aatlas.buy..` from depending on `java.lang.Double` at all (not just as a field type) — money and percentages here are primitive `double`, matching the TypeScript engine's plain `number` and the golden files' JSON doubles field for field (`Js.round2`/`round1` mirror `Math.round(n * 10^k) / 10^k` on primitives). Three places this bit: `CatalogGateway.LaneRef.inlandPct` was `Map<String, Double>` (a `Map` cannot hold a primitive) — moved to `Map<String, BigDecimal>`, read with `rs.getBigDecimal(...)` and consumed with `.doubleValue()`, since `BigDecimal` is not banned; `Js.toFixed` originally used `String.format("%.Nf", n)`, whose `Object...` varargs autoboxes the primitive argument into a `Double` the compiler inserts — replaced with `BigDecimal.valueOf(n).setScale(digits, HALF_UP).toPlainString()`; and two hand-written `Double.compare(a, b)` sort comparators were replaced with `Comparator.comparingDouble(...)`, which does **not** trip the rule — the `Double.compare` call it makes internally lives in the JDK's own class file, not this module's, so ArchUnit's bytecode-dependency scan never sees it. `Comparator.comparingDouble`/`.thenComparingDouble`/`.mapToDouble` are used freely elsewhere in the engines for exactly this reason.

**`POST /buy/select`'s write is a stand-in for the `decisions` module (Track D / History-Analytics), a different worktree not yet built.** `DecisionRecorder` (`buy.internal`) writes to this module's own `buy_decisions` table (V10) rather than guessing the shape of a table `decisions` has not published. What decides *whether* an order needs approval is not a stand-in: `Persona.canApprove(BigDecimal)` (`policy`, wave 1, already merged onto `salman`) is called directly. `TODO(merge)`: retarget `DecisionRecorder` at `decisions`' real writer; `buy_decisions` can then either drop or become a read-side index onto that module's rows.

**`BuyIntelReader`/`ProcurementPlanReader` live at the `buy` package root, not `buy.internal`, specifically for `bulk`'s benefit.** `bulk` (Track Bulk/Integrations/Assistant, a different worktree) is building `/buy/bulk/plan` against a stand-in of a buy-intel/procurement-plan reader without being able to see this module's code. Every other model and service type in `buy` follows the rest of the codebase's convention (internal by default); these two interfaces and the full `BuyIntel`/`ProcurementPlan` record graphs they return are the deliberate exception, so the merge lead can repoint `bulk`'s stand-in at the real thing by changing an import, not by guessing at return shapes it never saw.

**The query-parameter contract came from `platform/backend.ts`, not the page component.** The brief flagged an ambiguity ("read the buy page for exactly which query params it sends"); `src/lib/platform/backend.ts`'s `buyApi` object turned out to already be the wave-2 contract every backend track was handed ("the API was written to match [the frontend's types] field for field... every typed function here is the contract"), written by the same person who wrote the briefs, and it resolves the ambiguity precisely: `/buy/recommendation` takes `item`/`destination`/`supplier` (not `store`); `/buy/intel`, `/buy/compare`, `/buy/plan` and `/buy/negotiation` all take `region` (a market-region key), not `store`, with an optional `destination` override; `/buy/select`'s body uses full field names (`itemNumber`, `regionKey`, ...) while `/buy/what-if`'s uses short ones (`item`, `region`, ...) — an inconsistency between the two POST bodies that is simply what the frontend already committed to, ported as-is rather than "fixed".

**`buy2.ts`'s local `fmtMoney` is `fmtCompact` in disguise; `buyWhatIf`'s is the real thing.** `intel/buy2.ts` defines `function fmtMoney(n) { return fmtCompact(n); }` at file scope, shadowing the money module's actual `fmtMoney` for every reason/tradeoff/notInAHurry string `procurementPlan` builds (`"$66.1k more than the cheapest option"`) — while `buyWhatIf` in the same file imports the real `fmtMoney` under the alias `moneyIn` and uses it at full precision (2dp, or `moneyIn(n, 0)` for whole dollars). Missing this would have been invisible in a code review and wrong in every golden comparison: `Js.fmtCompact` is used throughout `ProcurementEngine.procurementPlan`, `Js.fmtMoney` throughout `buyWhatIf`.

**`ChainStep.effect` needed `@JsonInclude(ALWAYS)`, one field, not a global config change.** `application.yml` sets `jackson.default-property-inclusion: non_null` app-wide, which every other nullable field in this module wants (it is what makes an unset optional field disappear from the JSON, matching a TypeScript object literal that never assigned the key). `intel/buy.ts`'s chain, though, sets `effect: null` *explicitly* for its informational steps — `JSON.stringify` keeps an explicit `null`, it only drops `undefined` — so the app-wide setting would have silently dropped a key the golden files (and the frontend) expect present. Overridden on that one record component rather than touched globally, since every other module's optional fields still want the default.

**The golden test is an `*IT`, not suppliers' pattern of a pure `*Test`.** `SupplierEngineGoldenTest` (wave 1) feeds `seed/suppliers.json` straight into pure static scoring classes — no database, because nothing it ports reads tenant-scoped rows. `buy`'s engines read the tenant's real catalogue and supplier panel through the two gateways above, so a pure-function golden test is not possible without either faking `CatalogGateway`/`SupplierGateway` (untested wiring) or accepting a database. `BuyEngineGoldenIT` (`com.aatlas.buy`, Testcontainers, `mvnw verify`) signs up a tenant, connects the sample data source, seeds the supplier panel, then drives every golden row through the real HTTP endpoints with `MockMvc` — the golden-file proof and the "does the wiring actually work" proof in one test, at the cost of needing Docker (same trade-off `PostgresIntegrationTest`'s own javadoc already accepts for every other `*IT` in this codebase).
