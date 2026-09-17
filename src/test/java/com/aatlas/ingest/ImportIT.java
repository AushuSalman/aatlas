package com.aatlas.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.realdata.SampleTenant;
import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * CSV import end to end: upload, report, commit, rows on disk, rollback - and the other
 * three kinds through the same path.
 *
 * <p>Against a real PostgreSQL, because the parts most likely to be wrong here cannot be
 * mocked. Monthly partitions are created by a PL/pgSQL function at load time; the tenant
 * predicate is enforced by row-level security rather than by any Java the tests could
 * inspect; and the bulk insert goes through JDBC, not JPA, so nothing type-checks the
 * statement until it reaches the server.
 *
 * <p>Written after exactly that class of bug: {@code ensure_month_partition} was called with
 * {@code jdbc.update()}, which treats the one-row result of a {@code SELECT} as an error.
 * Every unit test passed and every import failed.
 *
 * <p>Three tenants for the class (the signup limiter allows ten an hour): one that only
 * uploads, one whose sample export is committed once and read by several tests, and one
 * that is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.application.name=aatlas-api-import-it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    /** Uploads only; nothing is ever committed here. */
    private String readerToken;
    /** The sample export, committed once in {@link #setUp}. */
    private String committedToken;
    private UUID committedTenant;
    private UUID committedBatch;

    private static byte[] sampleExport() throws Exception {
        try (InputStream in = ImportIT.class.getResourceAsStream("/ingest/sample-export.csv")) {
            assertThat(in).isNotNull();
            return in.readAllBytes();
        }
    }

    /** Signs a new tenant up and returns its bearer token. */
    private String signUp() throws Exception {
        return "Bearer " + SampleTenant.signUp(mvc, json, "both", "US");
    }

    private JsonNode upload(String token) throws Exception {
        return upload(token, "sales", "export.csv", sampleExport());
    }

    private JsonNode upload(String token, String kind, String name, byte[] bytes) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name, "text/csv", bytes);
        MvcResult result = mvc.perform(multipart("/api/v1/imports").file(file)
                        .param("kind", kind)
                        .header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    @BeforeAll
    void setUp() throws Exception {
        readerToken = signUp();
        committedToken = signUp();
        committedTenant = SampleTenant.tenantIdOf(json, committedToken.substring("Bearer ".length()));
        committedBatch = UUID.fromString(upload(committedToken).get("id").asText());
        commitAndWait(committedToken, committedBatch);
    }

    @Test
    @DisplayName("validates the sample export exactly as the browser does")
    void validatesOnUpload() throws Exception {
        JsonNode report = upload(readerToken);

        // The same numbers ImportValidatorTest pins against the prototype's own fixture.
        assertThat(report.get("kind").asText()).isEqualTo("sales");
        assertThat(report.get("source").asText()).isEqualTo("upload");
        assertThat(report.get("status").asText()).isEqualTo("VALIDATED");
        assertThat(report.get("totalRows").asInt()).isEqualTo(24);
        assertThat(report.get("acceptedRows").asInt()).isEqualTo(22);
        assertThat(report.get("rejectedRows").asInt()).isEqualTo(2);
        assertThat(report.get("issueCount").asLong()).isEqualTo(4);
        assertThat(report.get("distinctItems").asInt()).isEqualTo(12);
        assertThat(report.get("monthsCovered").asInt()).isEqualTo(6);
        assertThat(report.get("committable").asBoolean()).isTrue();
        assertThat(report.get("isSample").asBoolean()).isFalse();
        assertThat(report.get("contentHash").asText()).hasSize(64);

        // Detection found every column, so nothing blocks.
        assertThat(report.get("missingRequired")).isEmpty();
        assertThat(report.get("mapping").get("item").asInt()).isZero();
        assertThat(report.get("mapping").get("branch").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("loads the accepted rows, routed into monthly partitions")
    void commitsRowsIntoPartitions() throws Exception {
        mvc.perform(get("/api/v1/imports/{id}", committedBatch).header("Authorization", committedToken))
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.loadedRows").value(22))
                // Twelve item numbers the catalogue had never seen, so twelve products.
                .andExpect(jsonPath("$.productsCreated").value(12))
                // Every "Bill To" is an account the catalogue had never seen either.
                .andExpect(jsonPath("$.summary.customersCreated").value(13))
                .andExpect(jsonPath("$.dateShiftDays").value(0));

        // Six months of history spans seven calendar months, so seven partitions exist and
        // every row is inside one of them - which is what jdbc.update() could not do.
        Integer partitions = jdbc.queryForObject("""
                select count(*) from pg_inherits i
                  join pg_class p on p.oid = i.inhparent
                 where p.relname = 'sales_transactions'
                """, Integer.class);
        assertThat(partitions).isGreaterThanOrEqualTo(7);

        Integer loaded = jdbc.queryForObject(
                "select count(*) from sales_transactions where import_batch_id = ?", Integer.class, committedBatch);
        assertThat(loaded).isEqualTo(22);

        // The commit recorded the csv data source, which is what opens the workspace.
        mvc.perform(get("/api/v1/me").header("Authorization", committedToken))
                .andExpect(jsonPath("$.dataSource.kind").value("csv"))
                .andExpect(jsonPath("$.dataSource.label").value("CSV imports"));
    }

    @Test
    @DisplayName("neither the credit nor the unreadable date reaches the fact table")
    void rejectedRowsNeverLoad() {
        // A negative quantity is refused by a check constraint as well as by validation;
        // if either stopped working, this would have loaded.
        Integer credits = jdbc.queryForObject(
                "select count(*) from sales_transactions where import_batch_id = ? and qty <= 0",
                Integer.class, committedBatch);
        assertThat(credits).isZero();

        // HRD661204 appears twice in the file; one of them is the credit.
        Integer pexRows = jdbc.queryForObject("""
                select count(*) from sales_transactions
                 where import_batch_id = ? and item_number = 'HRD661204'
                """, Integer.class, committedBatch);
        assertThat(pexRows).isEqualTo(1);
    }

    @Test
    @DisplayName("branches are created from their codes, with no region until someone places them")
    void branchesAreCreatedUnassigned() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/imports/{id}", committedBatch).header("Authorization", committedToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode report = json.readTree(result.getResponse().getContentAsString());

        // Eight branch codes across the accepted rows, none of which the catalogue had seen.
        assertThat(report.get("branchesCreated").asInt()).isEqualTo(8);
        assertThat(report.get("branchesNeedingRegion")).hasSize(8);

        // Created, so every row resolves to a real branch - which is what opens the
        // catalogue endpoints. The region is left unknown rather than guessed.
        Integer unresolved = jdbc.queryForObject("""
                select count(*) from sales_transactions
                 where import_batch_id = ? and store_id is null and branch_code is not null
                """, Integer.class, committedBatch);
        assertThat(unresolved).isZero();

        // Scoped through the batch rather than counted globally: other tests in this class
        // import too, and a bare count over stores would drift with the run order.
        Integer unassigned = jdbc.queryForObject("""
                select count(distinct s.id)
                  from stores s
                  join sales_transactions t on t.store_id = s.id
                 where t.import_batch_id = ?
                   and s.region_key = 'unassigned'
                   and s.source = 'import'
                   and s.import_batch_id = ?
                """, Integer.class, committedBatch, committedBatch);
        assertThat(unassigned).isEqualTo(8);

        // Customers were created as unassigned and every row points at one.
        Integer noCustomer = jdbc.queryForObject("""
                select count(*) from sales_transactions where import_batch_id = ? and customer_id is null
                """, Integer.class, committedBatch);
        assertThat(noCustomer).isZero();
        Integer unassignedCustomers = jdbc.queryForObject("""
                select count(*) from customers where tenant_id = ? and segment = 'unassigned' and import_batch_id = ?
                """, Integer.class, committedTenant, committedBatch);
        assertThat(unassignedCustomers).isEqualTo(13);
    }

    @Test
    @DisplayName("row issues are readable by line, and filterable by severity")
    void reportsIssuesPerRow() throws Exception {
        UUID batchId = UUID.fromString(upload(readerToken).get("id").asText());

        mvc.perform(get("/api/v1/imports/{id}/issues", batchId).header("Authorization", readerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));

        mvc.perform(get("/api/v1/imports/{id}/issues", batchId)
                        .param("severity", "error")
                        .header("Authorization", readerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // 1-based and counting the header, so it matches what a spreadsheet shows.
                .andExpect(jsonPath("$[0].line").value(11));
    }

    @Test
    @DisplayName("a mapping that clears a required column blocks the import")
    void clearingARequiredColumnBlocksTheImport() throws Exception {
        UUID batchId = UUID.fromString(upload(readerToken).get("id").asText());

        // Everything except a price column.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/imports/{id}/mapping", batchId)
                        .header("Authorization", readerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mapping\":{\"item\":0,\"date\":2,\"qty\":3}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_MAPPING"))
                .andExpect(jsonPath("$.missingRequired[0]").value("price"))
                .andExpect(jsonPath("$.committable").value(false));

        mvc.perform(post("/api/v1/imports/{id}/commit", batchId).header("Authorization", readerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("mapping_incomplete"));
    }

    @Test
    @DisplayName("committing twice is refused rather than loading twice")
    void doubleCommitIsRefused() throws Exception {
        mvc.perform(post("/api/v1/imports/{id}/commit", committedBatch).header("Authorization", committedToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("import_already_committed"));
    }

    @Test
    @DisplayName("one tenant cannot see another tenant's import")
    void importsAreTenantScoped() throws Exception {
        // A different tenant asking for the same id gets a 404, not someone else's file.
        mvc.perform(get("/api/v1/imports/{id}", committedBatch).header("Authorization", readerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("uploading the same bytes again names the earlier committed batch")
    void duplicateUploadIsFlagged() throws Exception {
        JsonNode again = upload(committedToken);

        assertThat(again.get("duplicateOf").asText()).isEqualTo(committedBatch.toString());
        assertThat(again.get("status").asText()).isEqualTo("VALIDATED");
        // The server warns, it never refuses: appending the same history twice is the
        // user's call. So the list filter and the fields endpoint still work as usual.
        mvc.perform(get("/api/v1/imports").param("kind", "sales").header("Authorization", committedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/imports").param("kind", "purchases").header("Authorization", committedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind=='sales')]").doesNotExist());
    }

    @Test
    @DisplayName("the Hardin sample file is recognised, and refused on a real workspace")
    void sampleFileIsRefused() throws Exception {
        byte[] sample;
        try (InputStream in = ImportIT.class.getResourceAsStream("/samples/competitor-prices.csv")) {
            assertThat(in).isNotNull();
            sample = in.readAllBytes();
        }
        JsonNode report = upload(readerToken, "competitor_prices", "competitor-prices.csv", sample);
        assertThat(report.get("isSample").asBoolean()).isTrue();
        assertThat(report.get("status").asText()).isEqualTo("VALIDATED");

        mvc.perform(post("/api/v1/imports/{id}/commit", report.get("id").asText()).header("Authorization", readerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("sample_file"));

        // Deleting a batch that never loaded removes it outright.
        mvc.perform(delete("/api/v1/imports/{id}", report.get("id").asText()).header("Authorization", readerToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/imports/{id}", report.get("id").asText()).header("Authorization", readerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the field tables and the public templates describe every kind")
    void fieldsAndTemplates() throws Exception {
        mvc.perform(get("/api/v1/imports/fields").param("kind", "purchases").header("Authorization", readerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("purchases"))
                .andExpect(jsonPath("$.label").value("Purchase history"))
                .andExpect(jsonPath("$.required").value(org.hamcrest.Matchers.contains(
                        "item", "orderDate", "supplier", "qty", "unitCost")))
                .andExpect(jsonPath("$.fields[0].header").value("Item No"))
                .andExpect(jsonPath("$.fields[0].synonyms").isArray());
        mvc.perform(get("/api/v1/imports/fields").param("kind", "nope").header("Authorization", readerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown_kind"));

        // Templates and samples are public: no token.
        MvcResult template = mvc.perform(get("/api/v1/reference/templates/purchases.csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"aatlas-purchases-template.csv\""))
                .andReturn();
        assertThat(template.getResponse().getContentType()).startsWith("text/csv");
        assertThat(template.getResponse().getContentAsString()).startsWith("PO Number,Order Date,Supplier,");
        assertThat(template.getResponse().getContentAsString().split("\r\n")).hasSize(4);
        for (String kind : List.of("sales", "products", "competitor_prices", "suppliers")) {
            mvc.perform(get("/api/v1/reference/samples/{kind}.csv", kind))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", "attachment; filename=\"hardin-" + kind + ".csv\""));
            mvc.perform(get("/api/v1/reference/templates/{kind}.csv", kind)).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("a two-line PO loads as two ledger rows sharing a reference, with baselines restated")
    void purchasesWithAMultiLinePo() throws Exception {
        // Ship To 100349 already exists for this tenant (the sample export committed in
        // setUp() named it), so only the blank Ship To on PO-7002 creates a branch (MAIN).
        String csv = """
                PO Number,Order Date,Supplier,Supplier Country,Item No,Item Description,Qty Ordered,Unit Cost,Freight,Duty,Landed Cost,Currency,Ship To,Promised Date,Received Date,Qty Received
                PO-7001,2026-07-08,Cascade Copper Mills,USA,HRD118902,1/2 IN COPPER TYPE L HARD TUBE 10FT,1200,19.40,0.00,0.00,19.40,USD,100349,2026-07-25,2026-07-27,1200
                PO-7001,2026-07-08,Cascade Copper Mills,USA,HRD772310,3/4 IN BRASS BALL VALVE FULL PORT THREADED,600,9.85,0.77,1.23,11.85,USD,100349,2026-07-25,,
                PO-7002,2026-08-10,Cascade Copper Mills,USA,HRD118902,1/2 IN COPPER TYPE L HARD TUBE 10FT,100.4,19.00,,,,USD,,2026-08-20,2026-08-19,100
                """;
        JsonNode uploaded = upload(committedToken, "purchases", "po.csv", csv.getBytes());
        assertThat(uploaded.get("kind").asText()).isEqualTo("purchases");
        assertThat(uploaded.get("acceptedRows").asInt()).isEqualTo(3);
        assertThat(uploaded.get("distinctSuppliers").asInt()).isEqualTo(1);
        // The fractional quantity is rounded with a warning, not rejected.
        assertThat(uploaded.get("issueCount").asLong()).isEqualTo(1);
        UUID batchId = UUID.fromString(uploaded.get("id").asText());
        commitAndWait(committedToken, batchId);

        JsonNode view = json.readTree(mvc.perform(get("/api/v1/imports/{id}", batchId)
                .header("Authorization", committedToken)).andReturn().getResponse().getContentAsString());
        assertThat(view.get("loadedRows").asInt()).isEqualTo(3);
        assertThat(view.get("summary").get("suppliersCreated").asInt()).isEqualTo(1);
        assertThat(view.get("summary").get("receivedRows").asInt()).isEqualTo(2);
        assertThat(view.get("summary").get("supplierLinksWritten").asInt()).isEqualTo(2);
        // The blank ship-to went to the tenant's main branch, created for it.
        assertThat(view.get("branchesCreated").asInt()).isEqualTo(1);
        assertThat(view.get("branchesNeedingRegion").get(0).asText()).isEqualTo("MAIN");

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select po_number, po_ref, item_number, qty, landed, baseline, target, followed, saved, leaked, status,
                       cost_basis, branch_id, supplier_id, on_time, product_id
                  from purchase_order where import_batch_id = ? order by source_line
                """, batchId);
        assertThat(rows).hasSize(3);
        // Two lines, one reference, distinct generated numbers.
        assertThat(rows.get(0).get("po_ref")).isEqualTo("PO-7001");
        assertThat(rows.get(1).get("po_ref")).isEqualTo("PO-7001");
        assertThat(rows.get(0).get("po_number")).isNotEqualTo(rows.get(1).get("po_number"));
        assertThat((String) rows.get(0).get("po_number")).startsWith("IMP-");
        assertThat(rows.get(0).get("status")).isEqualTo("received");
        assertThat(rows.get(0).get("on_time")).isEqualTo(false); // 27th against a promised 25th
        assertThat(rows.get(1).get("status")).isEqualTo("in-transit");
        assertThat(rows.get(1).get("on_time")).isNull();
        assertThat(rows.get(2).get("branch_id")).isEqualTo("MAIN");
        assertThat(rows.get(2).get("qty")).isEqualTo(100);
        assertThat(rows.get(2).get("cost_basis")).isEqualTo("lane-estimate");
        assertThat((String) rows.get(2).get("supplier_id")).startsWith("po-");
        assertThat(rows.get(2).get("product_id")).isNotNull();

        // First orders are their own baseline and target.
        assertThat((BigDecimal) rows.get(0).get("baseline")).isEqualByComparingTo("19.40");
        assertThat((BigDecimal) rows.get(0).get("target")).isEqualByComparingTo("19.40");
        assertThat(rows.get(0).get("followed")).isEqualTo(true);
        assertThat((BigDecimal) rows.get(0).get("saved")).isEqualByComparingTo("0");
        // The later order is measured against the trailing year: spec A 3.6a, as PoBaselineTest pins it.
        assertThat((BigDecimal) rows.get(2).get("landed")).isEqualByComparingTo("19.00");
        assertThat((BigDecimal) rows.get(2).get("target")).isEqualByComparingTo("19.40");
        assertThat((BigDecimal) rows.get(2).get("baseline")).isEqualByComparingTo("19.40");
        assertThat(rows.get(2).get("followed")).isEqualTo(true);
        assertThat((BigDecimal) rows.get(2).get("saved")).isEqualByComparingTo("40.00");
        assertThat((BigDecimal) rows.get(2).get("leaked")).isEqualByComparingTo("0");

        // The observed price list: last ex-works by order date, average actual lead over received lines.
        Map<String, Object> link = jdbc.queryForMap("""
                select sp.ex_works, sp.lead_time_days, sp.ex_works_source
                  from supplier_products sp
                  join products p on p.id = sp.product_id
                 where sp.tenant_id = ? and p.item_number = 'HRD118902'
                """, committedTenant);
        assertThat((BigDecimal) link.get("ex_works")).isEqualByComparingTo("19.00");
        assertThat(link.get("lead_time_days")).isEqualTo(14); // (19 + 9) / 2
        assertThat(link.get("ex_works_source")).isEqualTo("purchases");
    }

    @Test
    @DisplayName("rolling an import back removes its rows and everything it created that nothing else uses")
    void rollbackRemovesRowsAndCreatedRows() throws Exception {
        String token = signUp();
        UUID tenantId = SampleTenant.tenantIdOf(json, token.substring("Bearer ".length()));
        UUID batchId = UUID.fromString(upload(token).get("id").asText());
        commitAndWait(token, batchId);
        assertThat(count("products", tenantId)).isEqualTo(12);
        assertThat(count("stores", tenantId)).isEqualTo(8);
        assertThat(count("customers", tenantId)).isEqualTo(13);

        mvc.perform(delete("/api/v1/imports/{id}", batchId).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"))
                .andExpect(jsonPath("$.rolledBackAt").isNotEmpty())
                .andExpect(jsonPath("$.summary.keptSuppliers").isEmpty());

        assertThat(count("sales_transactions", tenantId)).isZero();
        assertThat(count("product_stores", tenantId)).isZero();
        assertThat(count("products", tenantId)).isZero();
        assertThat(count("stores", tenantId)).isZero();
        assertThat(count("customers", tenantId)).isZero();
        // No committed upload is left, so the csv source goes and the workspace is back at onboarding.
        assertThat(count("data_sources", tenantId)).isZero();

        mvc.perform(delete("/api/v1/imports/{id}", batchId).header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_rolled_back"));
        mvc.perform(post("/api/v1/imports/{id}/commit", batchId).header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("import_rolled_back"));
    }

    private int count(String table, UUID tenantId) {
        Integer n = jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ?", Integer.class, tenantId);
        return n == null ? 0 : n;
    }

    private void commitAndWait(String token, UUID batchId) throws Exception {
        mvc.perform(post("/api/v1/imports/{id}/commit", batchId).header("Authorization", token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("COMMITTING"));
        // The load runs on another thread, so the status is polled the way a client would.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            MvcResult result = mvc.perform(get("/api/v1/imports/{id}", batchId).header("Authorization", token))
                    .andReturn();
            JsonNode view = json.readTree(result.getResponse().getContentAsString());
            if ("FAILED".equals(view.get("status").asText())) {
                throw new IllegalStateException("import failed: " + view.get("failureReason").asText());
            }
            assertThat(view.get("status").asText()).isEqualTo("COMMITTED");
        });
    }
}
