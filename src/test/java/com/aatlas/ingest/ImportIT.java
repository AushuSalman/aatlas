package com.aatlas.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * CSV import end to end: upload, report, commit, rows on disk.
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
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private static byte[] sampleExport() throws Exception {
        try (InputStream in = ImportIT.class.getResourceAsStream("/ingest/sample-export.csv")) {
            assertThat(in).isNotNull();
            return in.readAllBytes();
        }
    }

    /** Signs a new tenant up and returns its bearer token. */
    private String signUp() throws Exception {
        String body = """
                {
                  "fullName": "Import Tester",
                  "email": "import-%s@kestrelsupply.com",
                  "password": "Zephyr!42Bridge",
                  "company": "Import Co %s",
                  "country": "US",
                  "role": "both"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return "Bearer " + json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private JsonNode upload(String token) throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "export.csv", "text/csv", sampleExport());

        MvcResult result = mvc.perform(multipart("/api/v1/imports").file(file).header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("validates the sample export exactly as the browser does")
    void validatesOnUpload() throws Exception {
        JsonNode report = upload(signUp());

        // The same numbers ImportValidatorTest pins against the prototype's own fixture.
        assertThat(report.get("status").asText()).isEqualTo("VALIDATED");
        assertThat(report.get("totalRows").asInt()).isEqualTo(24);
        assertThat(report.get("acceptedRows").asInt()).isEqualTo(22);
        assertThat(report.get("rejectedRows").asInt()).isEqualTo(2);
        assertThat(report.get("issueCount").asLong()).isEqualTo(4);
        assertThat(report.get("distinctItems").asInt()).isEqualTo(12);
        assertThat(report.get("monthsCovered").asInt()).isEqualTo(6);
        assertThat(report.get("committable").asBoolean()).isTrue();

        // Detection found every column, so nothing blocks.
        assertThat(report.get("missingRequired")).isEmpty();
        assertThat(report.get("mapping").get("item").asInt()).isZero();
        assertThat(report.get("mapping").get("branch").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("loads the accepted rows, routed into monthly partitions")
    void commitsRowsIntoPartitions() throws Exception {
        String token = signUp();
        UUID batchId = UUID.fromString(upload(token).get("id").asText());

        mvc.perform(post("/api/v1/imports/{id}/commit", batchId).header("Authorization", token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("COMMITTING"));

        // The load runs on another thread, so the status is polled the way a client would.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                mvc.perform(get("/api/v1/imports/{id}", batchId).header("Authorization", token))
                        .andExpect(jsonPath("$.status").value("COMMITTED")));

        mvc.perform(get("/api/v1/imports/{id}", batchId).header("Authorization", token))
                .andExpect(jsonPath("$.loadedRows").value(22))
                // Twelve item numbers the catalogue had never seen, so twelve products.
                .andExpect(jsonPath("$.productsCreated").value(12));

        // Six months of history spans seven calendar months, so seven partitions exist and
        // every row is inside one of them - which is what jdbc.update() could not do.
        Integer partitions = jdbc.queryForObject("""
                select count(*) from pg_inherits i
                  join pg_class p on p.oid = i.inhparent
                 where p.relname = 'sales_transactions'
                """, Integer.class);
        assertThat(partitions).isGreaterThanOrEqualTo(7);

        Integer loaded = jdbc.queryForObject(
                "select count(*) from sales_transactions where import_batch_id = ?", Integer.class, batchId);
        assertThat(loaded).isEqualTo(22);
    }

    @Test
    @DisplayName("neither the credit nor the unreadable date reaches the fact table")
    void rejectedRowsNeverLoad() throws Exception {
        String token = signUp();
        UUID batchId = UUID.fromString(upload(token).get("id").asText());
        commitAndWait(token, batchId);

        // A negative quantity is refused by a check constraint as well as by validation;
        // if either stopped working, this would have loaded.
        Integer credits = jdbc.queryForObject(
                "select count(*) from sales_transactions where import_batch_id = ? and qty <= 0",
                Integer.class, batchId);
        assertThat(credits).isZero();

        // HRD661204 appears twice in the file; one of them is the credit.
        Integer pexRows = jdbc.queryForObject("""
                select count(*) from sales_transactions
                 where import_batch_id = ? and item_number = 'HRD661204'
                """, Integer.class, batchId);
        assertThat(pexRows).isEqualTo(1);
    }

    @Test
    @DisplayName("branches are created from their codes, with no region until someone places them")
    void branchesAreCreatedUnassigned() throws Exception {
        String token = signUp();
        UUID batchId = UUID.fromString(upload(token).get("id").asText());
        commitAndWait(token, batchId);

        MvcResult result = mvc.perform(get("/api/v1/imports/{id}", batchId).header("Authorization", token))
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
                """, Integer.class, batchId);
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
                """, Integer.class, batchId);
        assertThat(unassigned).isEqualTo(8);
    }

    @Test
    @DisplayName("row issues are readable by line, and filterable by severity")
    void reportsIssuesPerRow() throws Exception {
        String token = signUp();
        UUID batchId = UUID.fromString(upload(token).get("id").asText());

        mvc.perform(get("/api/v1/imports/{id}/issues", batchId).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));

        mvc.perform(get("/api/v1/imports/{id}/issues", batchId)
                        .param("severity", "error")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // 1-based and counting the header, so it matches what a spreadsheet shows.
                .andExpect(jsonPath("$[0].line").value(11));
    }

    @Test
    @DisplayName("a mapping that clears a required column blocks the import")
    void clearingARequiredColumnBlocksTheImport() throws Exception {
        String token = signUp();
        UUID batchId = UUID.fromString(upload(token).get("id").asText());

        // Everything except a price column.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/imports/{id}/mapping", batchId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mapping\":{\"item\":0,\"date\":2,\"qty\":3}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_MAPPING"))
                .andExpect(jsonPath("$.missingRequired[0]").value("price"))
                .andExpect(jsonPath("$.committable").value(false));

        mvc.perform(post("/api/v1/imports/{id}/commit", batchId).header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("mapping_incomplete"));
    }

    @Test
    @DisplayName("committing twice is refused rather than loading twice")
    void doubleCommitIsRefused() throws Exception {
        String token = signUp();
        UUID batchId = UUID.fromString(upload(token).get("id").asText());
        commitAndWait(token, batchId);

        mvc.perform(post("/api/v1/imports/{id}/commit", batchId).header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("import_already_committed"));
    }

    @Test
    @DisplayName("one tenant cannot see another tenant's import")
    void importsAreTenantScoped() throws Exception {
        UUID batchId = UUID.fromString(upload(signUp()).get("id").asText());

        // A different tenant asking for the same id gets a 404, not someone else's file.
        mvc.perform(get("/api/v1/imports/{id}", batchId).header("Authorization", signUp()))
                .andExpect(status().isNotFound());
    }

    private void commitAndWait(String token, UUID batchId) throws Exception {
        mvc.perform(post("/api/v1/imports/{id}/commit", batchId).header("Authorization", token))
                .andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                mvc.perform(get("/api/v1/imports/{id}", batchId).header("Authorization", token))
                        .andExpect(jsonPath("$.status").value("COMMITTED")));
    }
}
