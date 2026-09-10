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

## Wave 2: `decisions`, `analytics` (V12)

Ports `intel/decisions.ts`, `intel/history.ts`, the impact half of `platform/api.ts` (`decisions`), and `platform/procurement.ts` (`analytics`) — the History/Analytics track of wave 2. Unlike most of wave 2, this track owns real new fact tables (`decision`, `deal`, `quote`, `purchase_order`) rather than computing everything on demand, because both modules exist to record and replay *what actually happened*, not to derive a number from a hash.

### Two real cross-module calls inside one track, no stand-in needed

`decisions.internal.HistoryService.buildImpact()` needs the buy side of the impact rollup, which the frontend computes from the procurement ledger (`summariseBuy` calling `computeBuyAnalytics`), not from `deal` rows. Because both `decisions` and `analytics` are built in the same worktree by the same track, this is a genuine public-API dependency, not the wave-2 brief's "stand-in now, retarget at merge" situation: `analytics` exposes `ProcurementAnalytics.trailingTwelveMonthImpact()` (read) and `ProcurementLedger.recordAward(...)` (write — badges a live-recorded buy decision onto the ledger, `decision_id` and all) from its package root, and `decisions` depends on them like any other module would. `ModularityTests` enforces the same boundary between these two as between any other pair; nothing here reaches into `analytics.internal`.

### The one real stand-in: `PricingCost`

`analytics.internal.ledger.LedgerBuilder` (the port of `buildLedger()`) needs exactly two things from the frontend's pricing engine: an item's base cost and whether an (item, store) pair is priceable. The real `getPricingModel` port belongs to `sell`/a future shared `engine` module, neither of which exists in this worktree. `PricingCost` ports only the `baseCost`/priceable slice — a pure function of the item number and the static fixture, so it is a faithful subset rather than an approximation; its javadoc carries `TODO(merge): retarget onto the real getPricingModel port`.

### Fixtures are read from `seed/*.json` directly, not from `catalog`'s or `suppliers`' tables

`catalog` exposes no public read API yet (only `CatalogSeeding`, for writing a tenant's copy), and reaching into `catalog.internal`/`suppliers.internal` fails `ModularityTests` regardless. The frontend's own `procurement.ts` is built the same way — it imports the static fixtures (`PRODUCTS`, `TENANTS`, `SUPPLIERS`) directly, because the ledger is fixture-derived seeded arithmetic identical for every tenant that connects the sample source, exactly like `suppliers.json`'s eight-supplier panel. `analytics.internal.fixtures.Fixtures` reads `products.json`, `stores.json`'s `US` array and `suppliers.json`'s `record` objects directly — verified to already be in the frontend's exact `PRODUCTS`/`TENANTS_US`/`SUPPLIERS` order (see `API-BRIEF.md`'s seed-file notes) — plus `logistics.json` for the freight-lane tables. `decisions.internal.ProductNames` does the same for the one field it needs (an item's `shortName`, for `getHistory`'s `name` column).

### `deal.deal_key` and `purchase_order.seq`: two columns beyond the frontend's own shape

The frontend's `DealRow.id` and `PurchaseOrder.id` are strings the fixtures assign (`d-s-99`, `PO-2609-1000`), not uuids — `deals.json`'s 181 rows already carry them. Following the convention that every business table's primary key is a uuid v7, `deal` and `purchase_order` keep the uuid as `id` and carry the frontend's own string separately: `deal_key` (unique per tenant) is what `DealRecord.id` actually serialises as on the wire — golden-tested, since `history.json`'s rows are keyed by exactly these strings. `purchase_order.seq` is not on the wire at all (`PoRow` is `@JsonIgnoreProperties({"seq"})`); it records build order so a read can reproduce `buildLedger()`'s exact stable sort (`order_date DESC`, ties broken by build order) via `ORDER BY order_date DESC, seq ASC` — without it, a database read has no way to break a same-day tie the way the frontend's stable `Array.sort` does, and the golden file's first-50-row slice would only match in aggregate, not row for row.

### `DecisionRecorder`'s shape, for the tracks retargeting onto it

```java
public interface DecisionRecorder {
    Decision record(RecordDecisionRequest request);        // ports recordDecision
    DealRecord recordSale(RecordSaleRequest request);       // ports platform/recorded.ts's recordSale
    DealRecord recordPurchase(RecordPurchaseRequest request); // ports recordPurchase; also badges the ledger
    Decision get(UUID id);
    CursorPage<Decision> list(int limit, String cursor);
}
```

`recordSale` is not literally in `intel/decisions.ts` — it ports `platform/recorded.ts`'s `recordSale`, a different frontend file the UI calls from the same page action as `recordDecision` (two separate `localStorage` writes for one user action, per that file's own docblock). The Java port makes it one linked call instead: pass the `Decision.id` `record(...)` returned as `RecordSaleRequest.decisionId`/`RecordPurchaseRequest.decisionId` to join a deal to the decision that produced it. `recordPurchase` additionally takes `supplierId`/`country`/`category` — not on the frontend's `recordPurchase` — solely so it can also call `analytics.ProcurementLedger.recordAward(...)` and badge a real purchase order alongside the seeded ledger backdrop, the Java equivalent of the frontend's RFQ-award-then-`recordPurchase` pairing (`intel/rfq.ts`'s `awardRfq`, `intel/decisions.ts`'s `recordPurchase`). Omit them to record the deal only.

None of the wave-2 brief's Group L endpoint list includes a `POST /decisions` route — the brief expects `sell`/`buy`/`bulk` to call `DecisionRecorder` as a Java dependency directly. Since none of those modules exist yet in this worktree, `DecisionsController` also exposes `POST /decisions`, `/decisions/sale` and `/decisions/purchase` over HTTP, clearly marked in its class javadoc as additions — without them nothing could ever populate `deal`/`decision` beyond the 181 seeded rows, and the module would not be demoable standalone.

### `storeName` read `legal_name` instead of `msa_name` — caught by the golden test, not by hand

`data.ts`'s `storeName(storeId)` builds a branch label from `msa_name.split('-')[0]` (`"Dallas-Fort Worth-Arlington"` → `"Dallas"`), not from `legal_name` (`"Dallas Branch"`). The first port read `legal_name`, which still produces a plausible-looking label (`"Dallas Branch — TX"`) — exactly the kind of bug that looks fine on a screen and would have shipped unnoticed without a byte-exact comparison. `ProcurementAnalyticsGoldenTest.computeBuyAnalytics90dMatches` caught it immediately (`"Dallas Branch — TX"` vs. golden's `"Dallas — TX"`); fixed by adding `msa_name` to `StoreFixture` and reading it in `Fixtures.storeName`.

### Two IT-only bugs the golden tests could never have caught

Both seed listeners (`DealsSeedListener`, `ProcurementLedgerSeedListener`) originally carried an explicit `@Transactional` alongside `@ApplicationModuleListener`. Spring refused to start: `@ApplicationModuleListener` already composes `@TransactionalEventListener` with its own `REQUIRES_NEW` transaction, and stacking a second, default-propagation `@Transactional` on the same method is exactly what Spring's `@TransactionalEventListener` validation rejects at context-refresh time (`"must not be annotated with @Transactional unless ... REQUIRES_NEW or NOT_SUPPORTED"`). Fixed by dropping the annotation, matching `SuppliersSeedListener`'s existing pattern (no `@Transactional` there either) - worth calling out because it is an easy annotation to reach for by habit on anything writing to the database, and it fails the whole application context, not just one test.

`DecisionRepository`'s original keyset query used the same `(:cursor is null or d.createdAt < :cursor)` idiom `CatalogService` deliberately avoids: PostgreSQL's JDBC driver cannot infer a bind parameter's type when it appears only inside an `IS NULL` branch (`ERROR: could not determine data type of parameter $2`), so `GET /decisions` 500'd on every call. Fixed by switching to a `Specification` that only adds the `createdAt <` predicate when a cursor is actually present - the same shape `CatalogService`'s and `ProcurementAnalyticsService`'s keyset reads already use, which is exactly why they never hit this. Neither of these two bugs is visible to a unit test or a golden-file comparison; both only surface against a real PostgreSQL, which is what `DecisionsHistoryIT`/`AnalyticsProcurementIT` are for.

### Seeding is asynchronous after `SampleDataConnected` - IT tests must wait for it

`DomainEventPublisher`'s own javadoc says it: the event is "relayed after commit". `@ApplicationModuleListener` composes `@Async`, so `DealsSeedListener`/`ProcurementLedgerSeedListener` run on a separate thread *after* `POST /data-sources` has already returned 201 - unlike `catalog`'s seeding, which `ingest.DataSourceService` calls synchronously, in the same request, before responding. An IT test that connects the sample source and immediately asserts on `deal`/`purchase_order` row counts (or hits an endpoint that reads them) is racing the listener, and loses often enough to matter: it showed up as `GET /analytics/procurement/ranges` returning `404 no_ledger` moments before the listener's own "802 purchase orders" log line landed. `DecisionsHistoryIT`/`AnalyticsProcurementIT` poll (`select count(*) ... where tenant_id = ?`, up to 10s) after connecting, before running any assertion - safe because the whole listener method is one transaction, so the moment any row of a batch is visible to another connection, all of them are.

### Golden-file coverage and its one known gap

`HistoryEngineGoldenTest` (`decisions`) and `ProcurementAnalyticsGoldenTest` (`analytics`) are both plain, Spring-free unit tests — no database, no Testcontainers — that build the ledger and reduce the seeded deals in memory and compare against `golden/history.json`/`golden/procurement-analytics.json` structurally (numbers compared as doubles, object key order ignored), the same style as wave 1's `SupplierEngineGoldenTest`. All pass, including the first-50-row ledger slice, which confirms the Java port's `Seeded` calls run in the identical order the TypeScript's did — the brief flagged this as the one place a generation-order drift would be silent otherwise. `procurement-analytics.json` only pins the `90d`/`12m` ranges and the ledger's first 50 rows; the other range presets, the `/mix?dimension=branch` extension (not in the frontend at all — see the endpoint table), and the live-recording paths (`recordSale`/`recordPurchase`/`ProcurementLedger.recordAward`) are exercised by `DecisionsHistoryIT`/`AnalyticsProcurementIT` against a real Postgres instead, since no golden fixture covers a workspace's live writes.
