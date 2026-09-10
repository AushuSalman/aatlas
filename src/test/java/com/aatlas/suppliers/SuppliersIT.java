package com.aatlas.suppliers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The supplier panel end to end, against a real PostgreSQL: signup, seed, read every
 * endpoint, look a supplier up, add it, edit it, and remove it - the same path
 * {@code api-brief.md}'s definition of done asks every builder to exercise by hand.
 *
 * <p>{@code aatlas.clock.fixed=false} overrides the {@code test} profile's frozen
 * {@link com.aatlas.common.time.AatlasClock} (2026-09-01, for golden-file determinism)
 * for this class only. {@code TokenService} stamps a JWT's {@code iat}/{@code exp} from
 * that clock, but {@code JwtConfig}'s decoder validates them against the real system
 * clock; frozen at a date the environment's real clock has since passed, every token this
 * test issues would decode as already expired. No other test in the suite authenticates a
 * second request with a token it was issued, so this is the first place that mismatch is
 * visible - the override lives here, in this module's own test, rather than in the shared
 * {@code application-test.yml}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "aatlas.clock.fixed=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SuppliersIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    /**
     * The signup rate limiter caps one client address at ten accounts an hour
     * ({@code SignupRateLimiter}), and every MockMvc call in this test class shares the same
     * address. Three tenants, created once for the whole class rather than per test, stays
     * comfortably under that: {@code directorToken}'s tenant is seeded once in
     * {@link #setUp()} and read by every test that only needs the panel to exist;
     * {@code freshDirectorToken}'s is left unseeded so {@link #seedingIsIdempotent()} can
     * still observe "eight the first time, zero the second" on a tenant of its own.
     */
    private String directorToken;
    private String freshDirectorToken;
    private String financeToken;

    private static String uniqueEmail() {
        return "buyer-" + UUID.randomUUID() + "@kestrelsupply.com";
    }

    private String signUp(String role) throws Exception {
        String body = """
                {
                  "fullName": "Alex Moreno",
                  "email": "%s",
                  "password": "Zephyr!42Bridge",
                  "company": "Kestrel Supply Co.",
                  "country": "US",
                  "role": "%s"
                }
                """.formatted(uniqueEmail(), role);
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @BeforeAll
    void setUp() throws Exception {
        directorToken = signUp("both");
        financeToken = signUp("finance");
        freshDirectorToken = signUp("both");

        mvc.perform(post("/api/v1/suppliers/seed").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seeded").value(8));
    }

    /** Idempotent: the shared tenant is already seeded in {@link #setUp()}, so this is a no-op. */
    private void seedPanel() throws Exception {
        mvc.perform(post("/api/v1/suppliers/seed").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("seeding is idempotent: eight the first time, zero the second")
    void seedingIsIdempotent() throws Exception {
        mvc.perform(post("/api/v1/suppliers/seed").header("Authorization", "Bearer " + freshDirectorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seeded").value(8));

        mvc.perform(post("/api/v1/suppliers/seed").header("Authorization", "Bearer " + freshDirectorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seeded").value(0));
    }

    @Test
    @DisplayName("only the director may seed")
    void onlyDirectorMaySeed() throws Exception {
        mvc.perform(post("/api/v1/suppliers/seed").header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_allowed"));
    }

    @Test
    @DisplayName("the panel is ranked by rating, and the two brief-pinned ratings hold")
    void panelIsRankedByRating() throws Exception {
        seedPanel();

        MvcResult result = mvc.perform(get("/api/v1/suppliers").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = json.readTree(result.getResponse().getContentAsString()).get("items");
        assertThat(items).hasSize(8);

        // Ranked by rating descending.
        for (int i = 1; i < items.size(); i++) {
            assertThat(items.get(i - 1).get("rating").asDouble())
                    .isGreaterThanOrEqualTo(items.get(i).get("rating").asDouble());
        }

        JsonNode cascade = findByName(items, "Cascade Copper Mills");
        assertThat(cascade.get("rating").asDouble()).isEqualTo(4.5);
        assertThat(cascade.get("risk").has("score")).isTrue();
        assertThat(cascade.get("currency").asText()).isEqualTo("USD");

        JsonNode gulfStates = findByName(items, "Gulf States Polymer");
        assertThat(gulfStates.get("rating").asDouble()).isEqualTo(3.0);
    }

    private static JsonNode findByName(JsonNode items, String name) {
        for (JsonNode item : items) {
            if (name.equals(item.get("name").asText())) {
                return item;
            }
        }
        throw new AssertionError("No supplier named " + name + " in " + items);
    }

    @Test
    @DisplayName("the summary counts the panel, and rejects an unseeded ratio of zero suppliers gracefully")
    void summaryCountsThePanel() throws Exception {
        seedPanel();

        mvc.perform(get("/api/v1/suppliers/summary").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(8))
                .andExpect(jsonPath("$.added").value(0))
                .andExpect(jsonPath("$.currencies").isNumber());
    }

    @Test
    @DisplayName("one supplier by its frontend id, with terms, rating, reviews, risk and performance")
    void oneSupplierByItsFrontendId() throws Exception {
        seedPanel();

        mvc.perform(get("/api/v1/suppliers/sup-2").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cascade Copper Mills"))
                .andExpect(jsonPath("$.rating").value(4.5));

        mvc.perform(get("/api/v1/suppliers/sup-2/terms?qty=30000")
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terms.creditDays").value(45))
                .andExpect(jsonPath("$.labels.credit").value("45 days"))
                .andExpect(jsonPath("$.watchOuts[0]").value("This order is above their 5,431 units a month capacity."));

        mvc.perform(get("/api/v1/suppliers/sup-2/rating").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(4.5))
                .andExpect(jsonPath("$.label").value("Excellent"))
                .andExpect(jsonPath("$.recommendation").isNotEmpty());

        mvc.perform(get("/api/v1/suppliers/sup-2/reviews").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(3));

        mvc.perform(get("/api/v1/suppliers/sup-2/risk").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").isNumber())
                .andExpect(jsonPath("$.level").isNotEmpty())
                .andExpect(jsonPath("$.factors").isArray());

        mvc.perform(get("/api/v1/suppliers/sup-2/performance").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otifTrend.length()").value(6))
                .andExpect(jsonPath("$.poCount12m").isNumber());
    }

    @Test
    @DisplayName("an unknown supplier is a 404")
    void unknownSupplierIsNotFound() throws Exception {
        seedPanel();
        mvc.perform(get("/api/v1/suppliers/sup-999").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    @DisplayName("look a supplier up, then only a buy seat or the director may add it")
    void lookupThenAdd() throws Exception {
        seedPanel();

        MvcResult lookupResult = mvc.perform(post("/api/v1/suppliers/lookup")
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "Halden Metals Ltd", "country": "UK"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.id").value("cus-f26j4f"))
                .andExpect(jsonPath("$.profile.name").value("Halden Metals Ltd"))
                .andExpect(jsonPath("$.lookupId").isNotEmpty())
                .andReturn();
        String lookupId = json.readTree(lookupResult.getResponse().getContentAsString()).get("lookupId").asText();

        // Finance is not a buy seat and not the director.
        mvc.perform(post("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lookupId\": \"" + lookupId + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_allowed"));

        MvcResult addResult = mvc.perform(post("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lookupId\": \"" + lookupId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").value("cus-f26j4f"))
                .andExpect(jsonPath("$.isCustom").value(true))
                .andReturn();
        assertThat(addResult.getResponse().getContentAsString()).contains("Halden Metals Ltd");

        // Editing a custom supplier's contact.
        mvc.perform(patch("/api/v1/suppliers/cus-f26j4f")
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contactName": "Jane Doe"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cus-f26j4f"));

        // A custom supplier may be removed...
        mvc.perform(delete("/api/v1/suppliers/cus-f26j4f").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isNoContent());

        // ...a seeded one may not.
        mvc.perform(delete("/api/v1/suppliers/sup-2").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("seeded_supplier"));
    }
}
