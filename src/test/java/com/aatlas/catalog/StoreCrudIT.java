package com.aatlas.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
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
 * The branch list end to end: opening one, correcting it, placing it, retiring it and
 * closing it for good.
 *
 * <p>Sibling of {@link CatalogIT}, which covers the reads. Split because the writes need a
 * second seat to prove the seat rule and a second tenant to prove the destructive delete,
 * and folding those into the read suite would make every read depend on them.
 *
 * <p>Each test uses its own branch code so the shared tenant's state is never something
 * another test has to reason about; {@code @TestInstance(PER_CLASS)} and the {@code
 * spring.application.name} override are for the same reasons {@link CatalogIT} documents -
 * one signup budget per context, and a real clock so the minted tokens are not born
 * expired.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.application.name=aatlas-api-store-crud-it",
    "aatlas.clock.fixed=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoreCrudIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    /** A commercial director with the sample catalogue connected. Owns most of the tests. */
    private String director;

    /** A sales rep, in their own company: the seat that may read branches and not touch them. */
    private String rep;

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@kestrelsupply.com";
    }

    private String signUp(String role) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Dana Ellis",
                                  "email": "%s",
                                  "password": "Zephyr!42Bridge",
                                  "company": "Kestrel Supply Co.",
                                  "country": "US",
                                  "role": "%s"
                                }
                                """.formatted(uniqueEmail(role), role)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private void connectSample(String token) throws Exception {
        mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"sample\"}"))
                .andExpect(status().isCreated());
    }

    @BeforeAll
    void tenants() throws Exception {
        director = signUp("both");
        connectSample(director);
        rep = signUp("sales-rep");
    }

    // ---- create ------------------------------------------------------------------------

    @Test
    @DisplayName("a branch with a state is placed in that state's market region")
    void createPlacesByState() throws Exception {
        mvc.perform(post("/api/v1/stores").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "store_id": "700001",
                                  "legal_name": "Phoenix Branch",
                                  "company_number": "00210",
                                  "state": "az",
                                  "msa_name": "Phoenix-Mesa-Chandler",
                                  "rpp": 97.5,
                                  "txns": 812,
                                  "item_count": 140,
                                  "segment": "Regular",
                                  "map": {"x": 180, "y": 300, "anchor": "end"}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.endsWith("/api/v1/stores/700001")))
                .andExpect(jsonPath("$.store_id").value("700001"))
                .andExpect(jsonPath("$.legal_name").value("Phoenix Branch"))
                // Sent lowercase and mixed case; stored the way the reference tables spell them.
                .andExpect(jsonPath("$.state").value("AZ"))
                .andExpect(jsonPath("$.segment").value("regular"))
                .andExpect(jsonPath("$.regionKey").value("west"))
                .andExpect(jsonPath("$.country").value("US"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.source").value("manual"))
                .andExpect(jsonPath("$.map.anchor").value("end"));

        mvc.perform(get("/api/v1/stores/{id}", "700001").header("Authorization", "Bearer " + director))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msa_name").value("Phoenix-Mesa-Chandler"));
    }

    @Test
    @DisplayName("a branch with no state opens unassigned rather than in a guessed region")
    void createWithoutStateIsUnassigned() throws Exception {
        mvc.perform(post("/api/v1/stores").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":\"700002\",\"legal_name\":\"Branch 700002\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.regionKey").value("unassigned"))
                .andExpect(jsonPath("$.state").doesNotExist());

        // And it is kept out of the four real regions, which is the whole point of the state.
        mvc.perform(get("/api/v1/stores").header("Authorization", "Bearer " + director)
                        .param("region", "unassigned"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.store_id=='700002')]").exists());
    }

    @Test
    @DisplayName("the same branch code twice is a 409, not a second branch")
    void duplicateCodeIsConflict() throws Exception {
        String body = "{\"store_id\":\"700003\",\"legal_name\":\"Branch 700003\"}";
        mvc.perform(post("/api/v1/stores").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/stores").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("store_code_taken"));
    }

    @Test
    @DisplayName("a state that is not a state, and a region that contradicts one, are both refused")
    void placementIsValidated() throws Exception {
        mvc.perform(post("/api/v1/stores").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":\"700004\",\"legal_name\":\"Nowhere\",\"state\":\"ZZ\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown_subdivision"));

        mvc.perform(post("/api/v1/stores").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"store_id":"700004","legal_name":"Dallas Annex","state":"TX","regionKey":"west"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("region_mismatch"));

        mvc.perform(post("/api/v1/stores").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":\"700004\",\"legal_name\":\"Leeds\",\"country\":\"UK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("country_mismatch"));

        mvc.perform(post("/api/v1/stores").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legal_name\":\"No code\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.fields.storeId").value("A branch needs a code."));
    }

    @Test
    @DisplayName("a seat below head may read the branches and not change them")
    void onlyHeadsAndTheDirectorMayWrite() throws Exception {
        mvc.perform(post("/api/v1/stores").header("Authorization", "Bearer " + rep)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":\"700005\",\"legal_name\":\"Rep's Branch\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_allowed"));
    }

    // ---- patch -------------------------------------------------------------------------

    @Test
    @DisplayName("placing an imported branch is one field, and the rest of the record is left alone")
    void patchPlacesAndCorrects() throws Exception {
        mvc.perform(post("/api/v1/stores").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":\"700006\",\"legal_name\":\"Branch 700006\"}"))
                .andExpect(status().isCreated());

        mvc.perform(patch("/api/v1/stores/{id}", "700006").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionKey\":\"north\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionKey").value("north"))
                .andExpect(jsonPath("$.legal_name").value("Branch 700006"));

        // A new state overrules the region that was placed by hand, rather than colliding with it.
        mvc.perform(patch("/api/v1/stores/{id}", "700006").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legal_name\":\"Austin Branch\",\"state\":\"TX\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legal_name").value("Austin Branch"))
                .andExpect(jsonPath("$.state").value("TX"))
                .andExpect(jsonPath("$.regionKey").value("south"));
    }

    @Test
    @DisplayName("a branch code cannot be renamed, and saying so beats pretending it worked")
    void storeCodeIsImmutable() throws Exception {
        mvc.perform(post("/api/v1/stores").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":\"700007\",\"legal_name\":\"Branch 700007\"}"))
                .andExpect(status().isCreated());

        mvc.perform(patch("/api/v1/stores/{id}", "700007").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":\"700077\",\"legal_name\":\"Renamed\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("store_code_immutable"));

        // Posting the record back unchanged, code included, is the ordinary form save.
        mvc.perform(patch("/api/v1/stores/{id}", "700007").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":\"700007\",\"legal_name\":\"Branch 700007 North\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legal_name").value("Branch 700007 North"));
    }

    @Test
    @DisplayName("retiring a branch keeps it, and the active filter is what hides it")
    void retireAndFilter() throws Exception {
        mvc.perform(post("/api/v1/stores").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":\"700008\",\"legal_name\":\"Closing Branch\"}"))
                .andExpect(status().isCreated());

        mvc.perform(patch("/api/v1/stores/{id}", "700008").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mvc.perform(get("/api/v1/stores").header("Authorization", "Bearer " + director)
                        .param("active", "true").param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.store_id=='700008')]").doesNotExist());

        mvc.perform(get("/api/v1/stores").header("Authorization", "Bearer " + director)
                        .param("active", "false").param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.store_id=='700008')]").exists());
    }

    // ---- search ------------------------------------------------------------------------

    @Test
    @DisplayName("q matches the branch code, the name and the MSA, case-insensitively")
    void searchByCodeNameAndMsa() throws Exception {
        mvc.perform(post("/api/v1/stores").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"store_id":"700009","legal_name":"Tulsa Depot","state":"OK",
                                 "msa_name":"Tulsa-Broken Arrow"}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/stores").header("Authorization", "Bearer " + director).param("q", "tulsa depot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.store_id=='700009')]").exists());

        mvc.perform(get("/api/v1/stores").header("Authorization", "Bearer " + director).param("q", "broken arrow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.store_id=='700009')]").exists());

        mvc.perform(get("/api/v1/stores").header("Authorization", "Bearer " + director).param("q", "700009"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    // ---- delete ------------------------------------------------------------------------

    @Test
    @DisplayName("a branch that never traded is deleted outright")
    void deleteBranchWithNoHistory() throws Exception {
        mvc.perform(post("/api/v1/stores").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":\"700010\",\"legal_name\":\"Never Opened\"}"))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/v1/stores/{id}", "700010").header("Authorization", "Bearer " + director))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/stores/{id}", "700010").header("Authorization", "Bearer " + director))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a branch with sales history is refused, and force is what takes it and the history")
    void deleteBranchWithHistoryNeedsForce() throws Exception {
        // 100959 is the seeded Dallas branch, and it sells HRD118902. Its own tenant, because
        // forcing the delete removes item-at-branch history the other tests read.
        String owner = signUp("both");
        connectSample(owner);

        mvc.perform(get("/api/v1/products/{item}/stores", "HRD118902").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.store_id=='100959')]").exists());

        mvc.perform(delete("/api/v1/stores/{id}", "100959").header("Authorization", "Bearer " + owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("store_in_use"))
                .andExpect(jsonPath("$.itemsWithHistory").value(Matchers.greaterThan(0)));

        mvc.perform(delete("/api/v1/stores/{id}", "100959").header("Authorization", "Bearer " + owner)
                        .param("force", "true"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/products/{item}/stores", "HRD118902").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.store_id=='100959')]").doesNotExist());
    }

    @Test
    @DisplayName("an unknown branch is a 404 on every verb that touches one")
    void unknownBranch() throws Exception {
        mvc.perform(patch("/api/v1/stores/{id}", "999999").header("Authorization", "Bearer " + director)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legal_name\":\"Ghost\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/stores/{id}", "999999").header("Authorization", "Bearer " + director))
                .andExpect(status().isNotFound());
    }
}
