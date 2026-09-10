# Aatlas API

Backend for the Aatlas decision-intelligence platform: what to charge, who to buy from, and what it was worth.

A **modular monolith** on Java 21 and Spring Boot — one deployable, sixteen modules whose boundaries are enforced at build time, with the compute-heavy work running out-of-band as workers. This is the scaffold described in `backend-blueprint.html`; the engines and endpoints land on top of it.

> **Status: wave 1 landed; this worktree adds wave 2's `buy` track.** Infrastructure, configuration, module boundaries and the build are in place and verified. `identity` (signup, login, refresh, logout, password reset, `/me`), `tenant` (settings, reference data, FX), `policy` (role policy, guardrails), `catalog` and `ingest` (products, stores, regions, customers, sample-data provisioning), and `suppliers` (panel, terms, ratings, reviews, risk, lookup) all carry real endpoints and tables — see "Wave 1" below. `buy` (`V10`, branch `wave2-buy`) now carries the landed-cost panel, buy intel, the procurement plan and its what-ifs, negotiation, and select — see "Wave 2 — Buy" below. Sell, Insights, History/Analytics and Bulk/Integrations/Assistant are being built in parallel, in worktrees this one does not see.

---

## The one idea worth knowing

Reads never run an engine. The prototype computes on the client — the overview walks every product at every branch, the buying dashboard reduces a 26-month ledger on each filter change. At a thousand SKUs across dozens of branches that is what makes a page slow.

So: **engines write snapshot tables on a schedule and on events; requests read rows.** A Sell recommendation becomes a primary-key lookup. A controller that finds no snapshot returns `202` with a job id rather than computing one inline. Everything in this scaffold — the cache tiers, the outbox, the worker roles, the `aatlas.snapshots.compute-on-miss=false` default — exists to hold that line.

---

## Running it

**Needs:** JDK 21, Maven 3.9+ (or the bundled `./mvnw`), PostgreSQL 16+. Docker is optional — only the integration tests use it, and they skip without it.

### 1. Database

With Docker:

```bash
docker compose up -d postgres
```

Or against a PostgreSQL you already have:

```bash
psql -U postgres -f scripts/create-database.sql
```

On Windows, `psql.exe` is under `C:\Program Files\PostgreSQL\<version>\bin\`.

### 2. Configuration

```bash
cp .env.example .env
```

Only `DB_URL`, `DB_USER` and `DB_PASSWORD` matter to start. Everything else has a working default or is switched off in the `local` profile.

### 3. Run

```bash
./mvnw spring-boot:run
```

| | |
|---|---|
| API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI 3.1 | http://localhost:8080/v3/api-docs |
| Health | http://localhost:8080/actuator/health |
| Module structure | http://localhost:8080/actuator/modulith |

Flyway applies the migrations on startup. On a fresh database you should see `V1` through `V3` applied and the app up in a few seconds.

### 4. Tests

```bash
./mvnw test          # unit + architecture; no Docker needed
./mvnw verify        # adds the Testcontainers integration tests
```

---

## Wave 1 — auth, tenant, policy and guardrails

Built on branch `wave1-auth`, on top of the signup and tenant-provisioning scaffold. Every route is `/api/v1/...`; the ones marked **public** are in `SecurityConfig.PUBLIC` and need no bearer token.

**`identity`** (`V5` adds `password_reset_tokens`)
- `POST /auth/login`, `/auth/refresh` (rotates; reusing a spent token revokes the whole family, `refresh_reused`), `/auth/logout` — all public.
- `POST /auth/password/forgot` (always 202) and `/auth/password/reset` — public. `LogMailer` writes the reset link to the log; swap for SMTP before production.
- `GET /me` — user, tenant, persona and data source in one round trip. `dataSource` is `null` until the `integrations` module's `data_sources` table (its `V7`) exists; `NoDataSourceYet` is the seam, with a `TODO(integrations)` marking where to plug in the real reader.

**`policy`** (new module; `V5` adds `role_policy`, `V6` adds `pricing_guardrails` / `pricing_guardrail_history`)
- `GET /roles` — the eight personas for the caller's tenant, merged from the platform defaults (`role_policy` rows with `tenant_id null`) and any per-tenant override.
- `PolicyReader.personaFor(tenantId, role)` is the module's public API; `identity` calls it for `/me` and to fetch the persona's title at signup, `tenant` calls it for the rename permission.
- `GET`/`PUT /guardrails`, `POST /guardrails/reset`, `GET /guardrails/history` — writes are refused (`403 not_allowed`) for any seat whose persona has `guardrails: false`; ranges are pinned to the frontend's `GUARDRAIL_FIELDS` by `GuardrailLimitsTest`.

**`tenant`** (`V5` adds `tenant_settings`; `V6` adds `fx_rate`)
- `GET`/`PATCH /tenant` — rename is restricted to heads of either side and the commercial director.
- `GET`/`PUT /tenant/settings` — country and trading currency. `tenant_settings` is the source of truth since `V5`; `tenants.country` / `tenants.trading_currency` are a denormalised copy `TenantService` rewrites in the same transaction (kept because the session and reports already read from there). Currency accepts any of the ten codes in `seed/currencies.json`, not only the pair the country implies — `V5` relaxes `tenants_currency_ck` to match.
- `GET /reference/countries` (public, `seed/countries.json` byte for byte, ETag) and `GET /reference/currencies` (public, static half from the seed joined to the live rate in `fx_rate`).

**Two scaffold fixes, in `JwtConfig`:**
- The `prod` profile now fails to start rather than minting an ephemeral RSA key pair when `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` are unset — a missing secret should be a page, not a pod that silently can't verify another pod's tokens.
- The JWT decoder's expiry check is bound to `AatlasClock` rather than the system clock, so token minting and verification agree even when the clock is frozen for the demo tenant (and for every test) — see `docs/decisions.md`.

---

## Wave 2 — Buy

Built on branch `wave2-buy`, `V10`. **Computed on demand, synchronously, in the request
thread** — a deliberate, temporary departure from "the one idea worth knowing" above. The
wave-2 brief's priority is every route reading and writing real data correctly for a demo,
over the blueprint's full snapshot/worker/cache architecture; a controller here calls the
same engine the TypeScript prototype did, at request time instead of on a schedule, which is
exactly what the blueprint says happens later once caching is needed for scale. No new fact
tables besides `buy_decisions` (below) — everything else is a pure function of the item,
region/branch, quantity and the deterministic hash, read against `catalog`'s and
`suppliers'` real seeded rows.

**`buy`** (`V10` adds `buy_decisions`)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/buy/recommendation` | `item`, `destination`, `supplier?` — the landed-cost panel for one supplier (the incumbent, or an override) into one branch, ranked against the panel. `buildBuyRecommendation`. |
| GET | `/api/v1/buy/intel` | `item`, `region`, `qty`, `destination?` — effective cost per supplier (landed plus reliability, quality, terms), now-vs-wait, the negotiation letter. `getBuyIntel`. `destination` defaults to the region's busiest branch. |
| GET | `/api/v1/buy/compare` | `item`, `region`, `qty`, `destination?` — `BuyIntel.suppliers` alone, for the side-by-side compare. |
| GET | `/api/v1/buy/plan` | `item`, `region`, `qty`, `requiredDays`, `priority`, `destination?` — options A–D, ranked suppliers, trade-offs, a decision score, a cost/speed matrix. `procurementPlan`. |
| POST | `/api/v1/buy/what-if` | `{item, region, qty, requiredDays, priority, scenario}` — one of the four named scenarios against the plan's recommendation. `buyWhatIf`. |
| GET | `/api/v1/buy/negotiation` | `item`, `region`, `qty`, `destination?` — `BuyIntel.negotiation` alone. |
| POST | `/api/v1/buy/select` | `{itemNumber, regionKey, destinationId, optionKey, orderValue}` — writes a decision (`buy_decisions`); `{ok:true}` when the signed-in seat's `Persona.approveLimit` covers `orderValue`, `{ok:false, pending:true, limit, approver}` when it does not. |

Query parameter names match `buyApi` in the frontend's `src/lib/platform/backend.ts`
exactly — that file is the contract every wave-2 backend track was given, field for field, so
it settles the ambiguity the brief itself flagged (`store` vs `destination`, whether
`supplier` is part of the recommendation call) rather than guessing from the page component.

**Stand-ins** (see each type's own Javadoc for the full note; every one is a real,
straightforward, deterministic port — not a mock):

- `CommercialTerms`, `SupplierRisk` (`com.aatlas.buy` package root) — `suppliers` (wave 1) already ported `intel/terms.ts`'s `commercialTerms` and `intel/buy2.ts`'s `supplierRisk`, but its only public type is `SupplierPanelSeeder`; nothing exposes them for reuse. Ported again here, keyed the same way. `TODO(merge)`: depend on a `suppliers` public reader if one lands.
- A small slice of `intel/suppliers.ts`'s rating engine (`RatingEngine`, internal) — needed only for the "Buyer rating" factor `supplierRisk` reads and the relationship sub-score `scoreSuppliers` reads. Same situation as above.
- The three fields of `sell`'s `PricingModel` that `buy` actually reads — `priceable`, `cost`, `currentPrice` (`PricingStandIn`, internal) — `sell` (a parallel wave-2 track, different worktree) is not visible here. Nothing else of that engine (demand, competitors, bands, optimal/aggressive tiers) is reproduced.
- `CatalogGateway`, `SupplierGateway` (internal): `catalog` and `suppliers` own the products/branches/logistics-reference and supplier-panel tables respectively, but expose no public (package-root) reader for them — only `CatalogSeeding` and `SupplierPanelSeeder`, both writers. Rather than re-deriving that data from the seed files a second time, these gateways read the **real, live tables** those modules already seeded, with plain SQL (`JdbcTemplate`) scoped by `tenant_id` from `TenantContext` — no Java dependency on `catalog.internal`/`suppliers.internal`, so `ModularityTests` passes, but still a stand-in for a proper cross-module reader. `TODO(merge)`: a public reader on each module.
- `DecisionRecorder` + `buy_decisions` (`V10`, internal): the decision/deal write on `POST /buy/select` belongs to the `decisions` module (Track D / History-Analytics, a different worktree, not yet built here). Recorded to `buy` module's own table instead of guessing `decisions`'s shape. `TODO(merge)`: retarget at `decisions`' real writer.
- `Persona.approveLimit`/`canApprove` is **not** a stand-in — `policy` is wave 1, already merged onto `salman`, this branch's ancestor — `POST /buy/select` depends on it directly.

**Exposed for `bulk`'s future stand-in.** `BuyIntelReader` and `ProcurementPlanReader`
(`com.aatlas.buy` package root), returning the public `BuyIntel`/`ProcurementPlan` record
graphs, are implemented by `BuyIntelEngine`/`ProcurementEngine`. The `bulk` module (Track
Bulk/Integrations/Assistant, a different worktree) is building `/buy/bulk/plan` against a
stand-in of this reader without being able to see this code; keeping the interface and its
return types at the package root — not `buy.internal` — is what lets that stand-in be
retargeted here at merge time.

**`GET /buy/what-if`'s data model:** `buyWhatIf(intel, plan, scenario)` already exists in the
prototype's `intel/buy2.ts`, so it is ported, not skipped as P2.

---

## Layout

```
src/main/java/com/aatlas/
├─ AatlasApplication.java     one deployable, three roles
├─ common/                    shared kernel — tenant, errors, money, clock, events
├─ config/                    security, JWT, cache, persistence, scheduling, OpenAPI
│
├─ identity/     signup, login, JWT rotation, users, seat policy
├─ tenant/       settings, country, trading currency, FX
├─ policy/       pricing guardrails and their history
├─ catalog/      products, stores, regions, customers, competitors
├─ suppliers/    panel, terms, ratings, reviews, lookup, risk
├─ sell/         recommendation, quote, scenarios, ATP, apply, bulk
├─ buy/          landed cost, intel, plan, negotiation, bulk awards
├─ rfq/          rounds, invites, quotes, award
├─ approvals/    requests raised over a seat's limit
├─ decisions/    decisions, deals, history, impact
├─ insights/     overview, region and store intel, scores, demographics
├─ analytics/    procurement analytics over monthly rollups
├─ integrations/ data sources, business systems, MCP, webhooks
├─ assistant/    intent routing over the other modules
├─ engine/       batch engines that write the snapshot tables
└─ ingest/       CSV import, ERP sync, warehouse share
```

`common` and `config` are **shared modules**: every business module may depend on them, and neither may depend on a business module. Everything else may only use another module's package-root types — reaching into an internal package fails `ModularityTests`.

---

## Rules the build enforces

`ModularityTests` and `ArchitectureRulesTest` are not ceremony. Each rule exists because of a specific failure mode:

| Rule | Why |
|---|---|
| Modules may not use each other's internals | The blueprint wants clean seams to split into services later. Seams only stay clean if something checks. |
| Engines may not call `Instant.now()` | Golden-file tests compare Java to the TypeScript prototype **to the cent**. A hidden system clock makes a test pass in the morning and fail at night. Inject `AatlasClock`. |
| No `Double` in the pricing path | Money is `numeric(14,4)` end to end. A double silently loses cents and the golden files stop meaning anything. |
| Controllers may not touch repositories | The "never walk item × store on the request thread" rule needs a service layer to live in. |
| `common` may not depend on a business module | Otherwise the shared kernel becomes the god package. |

---

## How things are wired

**Tenancy.** The tenant is a claim in the signed JWT, never a URL segment or a header. `TenantContextFilter` binds it per request; `TenantScopedEntity` stamps it on insert; and with `aatlas.rls.enabled=true` every connection carries it into PostgreSQL so row-level security makes a forgotten `where tenant_id = ?` return nothing instead of another company's prices. Belt and braces, deliberately.

**Writes.** Transactional outbox. A decision's row and its event commit in the same transaction (`event_publication`, `V3`), and a relay publishes afterwards. That is what makes "a recorded deal can never be lost or double-counted" true rather than aspirational: a crash between commit and publish leaves an incomplete row, retried on restart.

**Events.** `DomainEventPublisher` is the only thing application code sees. Behind it, Spring Modulith routes each event to a destination named by its own `type()` and keyed by tenant, so everything that happens to one tenant is consumed in order. Redis Streams at launch, Kafka when volume justifies it, no application change either way.

**Caching.** Two tiers. Redis for tenant-scoped hot reads that every pod must agree on; in-process Caffeine for reference data identical across tenants. Invalidation is by event — workers evict what they recompute — and TTLs are only a backstop against a missed eviction. `CACHE_MODE=caffeine` runs a laptop without Redis; more than one pod needs `redis`.

**Roles.** `AATLAS_ROLE` selects `api`, `engine-worker` or `ingest-worker` from the same artifact. Scale the workers on queue lag and the API on p95 independently, without a second build.

**The clock.** The prototype freezes "now" at 1 September 2026 so the seeded story lines up. `AatlasClock` is the only sanctioned source of time; production runs on the system clock and demo tenants stay frozen.

---

## Stack

| Layer | Choice |
|---|---|
| Runtime | Java 21, virtual threads on |
| Framework | Spring Boot 3.5.16, Spring MVC, Spring Modulith 1.4.13 |
| Security | Spring Security, OAuth2 resource server, RS256 JWT, BCrypt(12) |
| Data | Spring Data JPA for writes, jOOQ for analytics SQL, Flyway for schema |
| Database | PostgreSQL 16+ (`pgcrypto`, `pg_trgm`, `btree_gin`), PgBouncer in production |
| Cache | Redis 7 + Caffeine |
| Events | Spring Modulith outbox → Redis Streams / Kafka |
| Jobs | Spring Batch, `@Scheduled` + ShedLock |
| Files | S3-compatible object storage |
| Docs | springdoc OpenAPI 3.1, five groups |
| Observability | Micrometer, Prometheus, OpenTelemetry, JSON logs |
| Testing | JUnit 5, Testcontainers, ArchUnit, Spring Modulith test |

**Why Spring Boot 3.5 rather than 4.1.** Boot 4.1 is GA, but springdoc, Spring Modulith and ShedLock all have their Boot 4 lines at milestone quality right now. 3.5.16 is the newest release where every dependency in this stack is stable together. The upgrade is a version bump plus the Spring Framework 7 migration when that ecosystem settles; nothing here is designed around 3.x specifics.

---

## Schema

Flyway owns the schema; Hibernate only validates against it (`ddl-auto: validate`).

| | |
|---|---|
| `V1__foundations.sql` | Extensions, `app.uuid_generate_v7()`, `app.current_tenant()`, `app.touch_updated_at()`, `app.enable_tenant_rls()`, ShedLock |
| `V2__spring_batch.sql` | Batch metadata, verbatim from spring-batch-core |
| `V3__event_publication.sql` | The outbox, verbatim from spring-modulith-events-jdbc |
| `V4__identity.sql` | `tenants`, `users`, `refresh_tokens` (identity module) |
| `V5__policy_and_settings.sql` | `role_policy` (seeded from `seed/personas.json`), `tenant_settings`, `password_reset_tokens`; relaxes `tenants_currency_ck` to the full ten-currency set |
| `V6__guardrails_and_fx.sql` | `pricing_guardrails`, `pricing_guardrail_history`, `fx_rate` (seeded from `seed/currencies.json`) |
| `V7__catalog_and_data_sources.sql` | Regions/subdivisions, the logistics rate card, commodities (reference — not tenant-scoped); stores, products, product_stores, customers (catalogue — tenant-scoped); data_sources (catalog + ingest modules) |
| `V8__suppliers.sql` | `suppliers`, `supplier_terms`, `supplier_ratings`, `supplier_reviews`, `supplier_risk`, `supplier_lookups`, `supplier_performance_months` (suppliers module) |
| `V10__buy_decisions.sql` | `buy_decisions` — what `POST /buy/select` recorded (buy module; stand-in for the `decisions` module's own table, see "Wave 2 — Buy") |

Business tables arrive with the modules that own them, so a table and the code reading it are reviewed together. Migration numbers are reserved per builder and are not contiguous in any one worktree (V5–V7 belong to other in-flight modules).

**Conventions** every business table follows: `id uuid` defaulted from `app.uuid_generate_v7()` (time-ordered, so inserts stay clustered instead of scattering a 24-month import across the whole index), `tenant_id uuid not null` as the first column of every composite index, `created_at` / `updated_at` / `version`, money as `numeric(14,4)` in USD with the currency beside it, and `jsonb` only for payloads rendered verbatim — anything filtered or sorted on is a real column.

---

## Catalog and ingest (blueprint groups E sample-data path, F)

`catalog` owns the tenant's master data — stores, products, product_stores, customers — plus
country-wide reference data (regions/subdivisions, the logistics rate card, commodity
trend), loaded idempotently at startup from `seed/*.json`. `ingest` owns onboarding: what a
tenant has connected, and copying the seed catalogue into a tenant when it connects the
sample dataset. The dependency runs one way — `ingest` calls `catalog`'s public
`CatalogSeeding` interface; `catalog` knows nothing about `ingest`.

**Data sources** (`ingest`, `/api/v1/data-sources`)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/data-sources` | The tenant's sources, newest first. Empty routes the client to onboarding. |
| POST | `/api/v1/data-sources` | `{"kind":"sample"}` seeds the catalogue for the tenant's country and records the source (201). Calling it again is `409 already_connected` with the existing source's id — a partial unique index (`data_sources_sample_uk`) makes that race-proof, not just a check-then-act. `{"kind":"erp"\|"warehouse", "label", "detail", "config", "schedule"}` records a `pending` row; no connector runs yet. `{"kind":"csv"}` is refused (`400 use_imports`) — CSV goes through `POST /api/v1/imports`. |
| DELETE | `/api/v1/data-sources/{id}` | 204. Removes the source only; the catalogue it brought stays, so reconnecting the sample source later is a no-op re-seed, not a duplicate. |

Connecting the sample dataset publishes `SampleDataConnected(tenantId, dataSourceId, occurredAt)` after the seed and the source row commit, for the engines that will react to a tenant's first history.

**Catalogue reads** (`catalog`, tenant-scoped; all 404 `no_catalogue` until a source is connected)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/products` | `q` matches item number or description, case-insensitive; `category`, `hasSales`, `limit`/`cursor`. |
| GET | `/api/v1/products/{item}` | Row plus `commodityTrend` and `storeIds` (branches with history). |
| GET | `/api/v1/products/{item}/stores` | Branches with sales history for the item. |
| GET | `/api/v1/stores` | `region`, `limit`/`cursor`. |
| GET | `/api/v1/stores/{id}` | By branch code (`100959`) or our uuid. |
| GET | `/api/v1/regions` | The tenant's country's market regions — `key, label, short, states, subdivisions[], storeIds[]` — the raw shape `countryInfo().regions` carries, not the frontend's derived `MarketRegion` (which swaps `label`/`short` into `label`/`name`). |
| GET | `/api/v1/customers` | `limit`/`cursor`. |
| GET | `/api/v1/customers/{id}` | By account code (`c-1`) or our uuid. |
| GET | `/api/v1/reference/logistics` | `{regions, origins}` verbatim from the reference tables. Public (`SecurityConfig.PUBLIC`), identical for every tenant, cached 12h in-process and sent with `Cache-Control: public, max-age=3600`. |

`stores` on the wire keeps the frontend's `StoreItem` keys verbatim — `store_id`, `legal_name`, `msa_name`, `item_count` — because that type was ported from the original service and every screen reads it as-is; everything added (`country`, `regionKey`, `map`) is camelCase like the rest of the API.

**Which branches sell an item.** `SellersRule` (`catalog.internal.seed`) is a bit-for-bit port of the frontend's `hashString` (FNV-1a, `Math.imul`-equivalent 32-bit multiply) and `tenantsSellingItem`: the item's default branch always sells it, every other branch sells it unless `(hash(itemNumber) >> branchIndex) % 3 == 0`. Reproduced rather than replaced with something tidier because it decides which (item, branch) pairs get a `product_stores` row, and a different rule would silently diverge from the frontend fixtures the demo story and later golden-file tests depend on. Verified in `SellersRuleTest` against values computed by running the actual TypeScript in Node, not re-derived in Java.

**What the sample provisioner seeds**, from `seed/stores.json`, `seed/products.json`, `seed/customers.json`: every branch for the tenant's country (`tenants.country`, US or UK); every product, with `has_sales` and `default_store_code` carried straight from the seed; a `product_stores` row for every (item, branch) pair `SellersRule` selects for a sellable item, with `first_sale_at`/`last_sale_at` spanning the trailing 12 months from `AatlasClock`; every customer. Idempotent by tenant: a tenant that already has branches is left alone and the summary says so, so reconnecting after a disconnect never duplicates rows.

---

## What comes next

Following the blueprint's build order:

1. **Foundations** — identity, tenant, catalog: JWT auth, personas as data, settings, guardrails, FX, and the seeded catalogue as the first tenant. *(this scaffold is step 0 of it)*
2. **The engine, proven** — port `pricing.ts`, `sell.ts`, `score.ts`, supplier ratings to Java, with golden-file tests that must match the prototype to the cent before anything switches over.
3. **Sell and buy live** — snapshot reads; apply, quote and bulk write through the outbox.
4. **The aggregate screens** — geo, demographics, overview workers; procurement rollups; Redis in front. Load test to 1,000 RPS.
5. **Onboarding for real** — streaming CSV import, sample provisioning, first ERP connector.
6. **Procurement V2** — plan, RFQ, approvals, webhooks, admin.

The frontend switches one `src/lib/platform/api.ts` body at a time — that file is already one async function per screen with a fake delay, which is the seam this whole design is built to slot into.
