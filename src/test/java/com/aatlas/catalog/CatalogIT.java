package com.aatlas.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.assertj.core.api.Assertions;
import org.hamcrest.Matchers;
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
 * Group F, the catalogue reads, end to end: a fresh tenant is gated until it connects a
 * source, then every list and detail endpoint reads back what the sample provisioner
 * seeded through {@link com.aatlas.ingest.SampleDataProvisioner}.
 *
 * <p>Item numbers, branch codes and figures below come straight out of
 * {@code seed/products.json}, {@code seed/stores.json} and {@code seed/customers.json} -
 * this test is deliberately data-coupled to the seed, the same way the screens are.
 *
 * <p>One tenant, signed up and connected once in {@link #connectedTenant()}, serves every
 * read-only test below; only {@link #noCatalogueBeforeConnecting()} needs its own,
 * deliberately unconnected, tenant. Two {@code /auth/signup} calls for the whole class,
 * comfortably under identity's {@code SignupRateLimiter} (10 per client per hour,
 * in-process) - which a signup per {@code @Test} would not have been. {@code
 * @TestInstance(PER_CLASS)} is what lets {@code @BeforeAll} be an ordinary instance method
 * with {@code @Autowired} fields already set, rather than a static one juggling its own
 * {@code ApplicationContext} lookup. The {@code spring.application.name} override gives
 * this class its own Spring context - and with it its own rate-limiter instance - so its
 * signups are never counted against {@code SignupIT} or {@code DataSourceIT} even when a
 * full {@code mvn verify} runs every {@code *IT} class in one JVM.
 *
 * <p>{@code aatlas.clock.fixed=false} overrides the {@code test} profile's frozen demo
 * clock (2026-09-01) for this context. {@code TokenService} stamps a token's {@code
 * iat}/{@code exp} from {@code AatlasClock}; Spring Security's default {@code JwtDecoder}
 * validator checks them against the real system clock. Once the two diverge by more than
 * the access-token TTL (fifteen minutes) - which the frozen date and the real calendar
 * already do - every token this suite mints is born expired and every authenticated call
 * after signup gets a 401 no matter how correct the endpoint is.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.application.name=aatlas-api-catalog-it",
    "aatlas.clock.fixed=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    /** The one tenant every read-only test below shares: signed up and connected once. */
    private String token;

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@kestrelsupply.com";
    }

    private String signUp(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Dana Ellis",
                                  "email": "%s",
                                  "password": "Zephyr!42Bridge",
                                  "company": "Kestrel Supply Co.",
                                  "country": "US",
                                  "role": "both"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @BeforeAll
    void connectedTenant() throws Exception {
        token = signUp(uniqueEmail("seller"));
        mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"sample\"}"))
                .andExpect(status().isCreated());
    }

    // ---- the gate --------------------------------------------------------------------------

    @Test
    @DisplayName("a tenant with no data source gets 404 no_catalogue, not an empty list")
    void noCatalogueBeforeConnecting() throws Exception {
        String freshToken = signUp(uniqueEmail("unconnected"));

        mvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("no_catalogue"));
        mvc.perform(get("/api/v1/stores").header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("no_catalogue"));
        mvc.perform(get("/api/v1/regions").header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("no_catalogue"));
        mvc.perform(get("/api/v1/customers").header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("no_catalogue"));
    }

    // ---- once a source is connected ---------------------------------------------------------

    @Test
    @DisplayName("products: search by description substring, case-insensitive")
    void searchProductsByDescription() throws Exception {
        mvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token)
                        .param("q", "copper"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.itemNumber=='HRD118902')]").exists());

        // Case-insensitive and matches the item number too.
        mvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token)
                        .param("q", "hrd118902"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].itemNumber").value("HRD118902"));
    }

    @Test
    @DisplayName("products: hasSales=false filters to the catalogued-but-never-sold items")
    void filterByHasSales() throws Exception {
        mvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token)
                        .param("hasSales", "false")
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.itemNumber=='HRD900001')]").exists())
                .andExpect(jsonPath("$.items[?(@.hasSales==true)]").doesNotExist());
    }

    @Test
    @DisplayName("one product: meta, commodity trend and the branches that sell it")
    void productDetailCarriesTrendAndStores() throws Exception {
        mvc.perform(get("/api/v1/products/{item}", "HRD118902").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemNumber").value("HRD118902"))
                .andExpect(jsonPath("$.shortName").value("Copper Tube 1/2\" Type L"))
                .andExpect(jsonPath("$.commodity").value("copper"))
                .andExpect(jsonPath("$.commodityTrend.pct90").value(6.4))
                .andExpect(jsonPath("$.commodityTrend.label").value("Copper up on the index"))
                .andExpect(jsonPath("$.storeIds", Matchers.hasItem("100959")));
    }

    @Test
    @DisplayName("an unknown item number is a 404")
    void unknownProductIs404() throws Exception {
        mvc.perform(get("/api/v1/products/{item}", "NOPE-000").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    @DisplayName("branches that sell an item, with the frontend's snake_case store keys")
    void productStoresUseSnakeCaseKeys() throws Exception {
        mvc.perform(get("/api/v1/products/{item}/stores", "HRD118902").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.store_id=='100959')]").exists())
                .andExpect(jsonPath("$[0].legal_name").isNotEmpty());
    }

    @Test
    @DisplayName("stores: list filtered by market region, and one branch by its code")
    void storesListAndOne() throws Exception {
        mvc.perform(get("/api/v1/stores").header("Authorization", "Bearer " + token)
                        .param("region", "south"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.store_id=='100959')]").exists())
                .andExpect(jsonPath("$.items[?(@.regionKey=='west')]").doesNotExist());

        mvc.perform(get("/api/v1/stores/{id}", "100959").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legal_name").value("Dallas Branch"))
                .andExpect(jsonPath("$.regionKey").value("south"))
                .andExpect(jsonPath("$.map.x").isNumber());
    }

    @Test
    @DisplayName("an unknown branch code is a 404")
    void unknownStoreIs404() throws Exception {
        mvc.perform(get("/api/v1/stores/{id}", "999999").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("regions: the four US market regions with their subdivisions and branches")
    void regionsCarrySubdivisionsAndStores() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/regions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andReturn();

        JsonNode regions = json.readTree(result.getResponse().getContentAsString());
        JsonNode south = StreamSupport.stream(regions.spliterator(), false)
                .filter(r -> "south".equals(r.get("key").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no 'south' region in the response"));

        List<String> storeIds = StreamSupport.stream(south.get("storeIds").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
        Assertions.assertThat(storeIds).contains("100959");

        List<String> subdivisionCodes = StreamSupport.stream(south.get("subdivisions").spliterator(), false)
                .map(s -> s.get("code").asText())
                .toList();
        Assertions.assertThat(subdivisionCodes).contains("TX");
    }

    @Test
    @DisplayName("customers: the picker list, and one account by its code")
    void customersListAndOne() throws Exception {
        mvc.perform(get("/api/v1/customers").header("Authorization", "Bearer " + token)
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id=='c-1')]").exists());

        mvc.perform(get("/api/v1/customers/{id}", "c-1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Halloran Mechanical"))
                .andExpect(jsonPath("$.segment").value("contractor"))
                .andExpect(jsonPath("$.tier").value("A"));
    }

    @Test
    @DisplayName("customers: the cursor a page hands back actually pages, and decodes cleanly")
    void customerCursorRoundTrips() throws Exception {
        MvcResult first = mvc.perform(get("/api/v1/customers").header("Authorization", "Bearer " + token)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();
        JsonNode page = json.readTree(first.getResponse().getContentAsString());
        String firstId = page.at("/items/0/id").asText();
        String cursor = page.get("nextCursor").asText();

        mvc.perform(get("/api/v1/customers").header("Authorization", "Bearer " + token)
                        .param("limit", "1")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(Matchers.not(firstId)));
    }

    @Test
    @DisplayName("an unknown account code is a 404")
    void unknownCustomerIs404() throws Exception {
        mvc.perform(get("/api/v1/customers/{id}", "c-does-not-exist").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ---- reference ---------------------------------------------------------------------------

    @Test
    @DisplayName("the logistics rate card is public: no bearer token required")
    void logisticsIsPublic() throws Exception {
        mvc.perform(get("/api/v1/reference/logistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions").isArray())
                .andExpect(jsonPath("$.regions").isNotEmpty())
                .andExpect(jsonPath("$.origins").isMap());
    }
}
