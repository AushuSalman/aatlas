package com.aatlas.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The data-source lifecycle end to end: {@code GET/POST/DELETE /api/v1/data-sources}
 * against a real, freshly signed-up tenant.
 *
 * <p>Connecting the sample dataset is exercised here for its own contract (the row it
 * returns, the 409 on a second connect); {@link com.aatlas.catalog.CatalogIT} exercises
 * what it seeds into the catalogue tables.
 *
 * <p>One test = one {@code /auth/signup} call, kept comfortably under identity's
 * {@code SignupRateLimiter} (10 per client per hour, in-process). The
 * {@code spring.application.name} override is not read by anything - its only job is to
 * give this class its own Spring context, and with it its own rate-limiter instance,
 * separate from every other {@code *IT} class. Without it, Spring's test context cache
 * would hand every {@code @SpringBootTest} class in the same Maven run the *same*
 * context, and the limiter would count this class's signups against whatever
 * {@code SignupIT} (or any other suite) already used in that JVM - a shared mutable
 * singleton becoming inter-test-class coupling nobody asked for.
 *
 * <p>{@code aatlas.clock.fixed=false} overrides the {@code test} profile's frozen demo
 * clock (2026-09-01) for this context only. {@code TokenService} stamps a token's {@code
 * iat}/{@code exp} from {@code AatlasClock}; Spring Security's default {@code JwtDecoder}
 * validator checks them against the real system clock. Once the two diverge by more than
 * the access-token TTL (fifteen minutes) - which the frozen date and the real calendar
 * already do - every token this suite mints is born expired and every authenticated call
 * after signup gets a 401 no matter how correct the endpoint is. Running on the real clock
 * here sidesteps it without touching the frozen clock other suites may depend on for
 * golden-file comparisons.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.application.name=aatlas-api-ingest-it",
    "aatlas.clock.fixed=false"
})
class DataSourceIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private static String uniqueEmail() {
        return "buyer-" + UUID.randomUUID() + "@kestrelsupply.com";
    }

    private String signUp(String country) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Priya Shah",
                                  "email": "%s",
                                  "password": "Zephyr!42Bridge",
                                  "company": "Kestrel Supply Co.",
                                  "country": "%s",
                                  "role": "both"
                                }
                                """.formatted(uniqueEmail(), country)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        return response.get("accessToken").asText();
    }

    @Test
    @DisplayName("a fresh tenant has no data sources")
    void freshTenantHasNoSources() throws Exception {
        String token = signUp("US");

        mvc.perform(get("/api/v1/data-sources").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("connecting the sample dataset seeds the catalogue and records the source")
    void connectingSampleSeedsCatalogueAndRecordsSource() throws Exception {
        String token = signUp("US");

        MvcResult result = mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"sample\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.kind").value("sample"))
                .andExpect(jsonPath("$.label").value("Sample dataset"))
                .andExpect(jsonPath("$.detail").value("Hardin Supply Co — 26 months of history"))
                .andExpect(jsonPath("$.status").value("connected"))
                .andExpect(jsonPath("$.connectedAt").isNotEmpty())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();

        JsonNode source = json.readTree(result.getResponse().getContentAsString());
        UUID tenantId = UUID.fromString(json.readTree(decodeJwtPayload(token)).get("tid").asText());

        // The seeded rows actually landed, tenant-scoped, not just the data_sources row.
        Integer stores = jdbc.queryForObject("select count(*) from stores where tenant_id = ?", Integer.class, tenantId);
        Integer products = jdbc.queryForObject("select count(*) from products where tenant_id = ?", Integer.class, tenantId);
        Integer customers = jdbc.queryForObject("select count(*) from customers where tenant_id = ?", Integer.class, tenantId);
        Integer dataSources = jdbc.queryForObject(
                "select count(*) from data_sources where tenant_id = ?", Integer.class, tenantId);
        assertThat(stores).isEqualTo(9);
        assertThat(products).isGreaterThan(0);
        assertThat(customers).isGreaterThan(0);
        assertThat(dataSources).isEqualTo(1);
        assertThat(source.get("id").asText()).isNotBlank();

        mvc.perform(get("/api/v1/data-sources").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("sample"));
    }

    @Test
    @DisplayName("connecting the sample dataset twice is a 409, not a duplicate catalogue")
    void connectingTwiceIsAlreadyConnected() throws Exception {
        String token = signUp("US");

        mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"sample\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"sample\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_connected"))
                .andExpect(jsonPath("$.dataSourceId").isNotEmpty());
    }

    @Test
    @DisplayName("disconnecting removes the source but leaves the catalogue")
    void disconnectingLeavesTheCatalogue() throws Exception {
        String token = signUp("US");

        MvcResult connect = mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"sample\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String sourceId = json.readTree(connect.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(delete("/api/v1/data-sources/{id}", sourceId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/data-sources").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        // The catalogue itself was not touched by the disconnect.
        UUID tenantId = UUID.fromString(json.readTree(decodeJwtPayload(token)).get("tid").asText());
        Integer stores = jdbc.queryForObject("select count(*) from stores where tenant_id = ?", Integer.class, tenantId);
        assertThat(stores).isEqualTo(9);
    }

    @Test
    @DisplayName("an ERP source is recorded pending, with no connector run")
    void erpSourceIsRecordedPending() throws Exception {
        String token = signUp("US");

        mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"erp","label":"Epicor Prophet 21","detail":"Nightly at 02:00",
                                 "config":{"host":"erp.kestrel.example.com"},"schedule":"0 2 * * *"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("erp"))
                .andExpect(jsonPath("$.label").value("Epicor Prophet 21"))
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    @DisplayName("an ERP source with no label is a 400 the form can show")
    void erpSourceWithoutLabelIsRejected() throws Exception {
        String token = signUp("US");

        mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"erp\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.fields.label").isNotEmpty());
    }

    @Test
    @DisplayName("CSV history is refused here: it goes through /imports instead")
    void csvIsRefused() throws Exception {
        String token = signUp("US");

        mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"csv\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("use_imports"));
    }

    @Test
    @DisplayName("a UK tenant's sample dataset seeds the UK branch network")
    void ukTenantGetsUkStores() throws Exception {
        String token = signUp("UK");

        mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"sample\"}"))
                .andExpect(status().isCreated());

        UUID tenantId = UUID.fromString(json.readTree(decodeJwtPayload(token)).get("tid").asText());
        String country = jdbc.queryForObject(
                "select distinct country from stores where tenant_id = ?", String.class, tenantId);
        assertThat(country).isEqualTo("UK");
    }

    @Test
    @DisplayName("removing the sample rolls back its history and deletes every unreferenced sample row")
    void removingTheSampleLeavesNothingHardin() throws Exception {
        String token = signUp("US");
        UUID tenantId = UUID.fromString(json.readTree(decodeJwtPayload(token)).get("tid").asText());
        mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"sample\"}"))
                .andExpect(status().isCreated());
        com.aatlas.realdata.SampleTenant.awaitReady(mvc, json, token);

        // Uploads are refused while the sample is connected.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/imports")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "s.csv", "text/csv",
                                "Item,Date,Qty,Price\nX1,2026-03-04,1,2.00\n".getBytes()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("remove_sample_first"));

        mvc.perform(delete("/api/v1/data-sources/sample").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/data-sources/sample").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("no_sample"));

        for (String table : new String[] {
            "sales_transactions", "purchase_order", "product_prices", "inventory_positions", "competitor_prices",
            "import_batches", "products", "stores", "customers", "suppliers", "data_sources"}) {
            Integer left = jdbc.queryForObject(
                    "select count(*) from " + table + " where tenant_id = ?", Integer.class, tenantId);
            assertThat(left).as(table).isZero();
        }
        mvc.perform(get("/api/v1/workspace/readiness").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogue.products").value(0))
                .andExpect(jsonPath("$.nextSteps[0].key").value("connect"));
    }

    /** Decodes the JWT payload without verifying the signature; tests only read the claims. */
    private static String decodeJwtPayload(String jwt) {
        String[] parts = jwt.split("\\.");
        return new String(java.util.Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
    }
}
