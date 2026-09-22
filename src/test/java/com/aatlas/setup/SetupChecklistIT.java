package com.aatlas.setup;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.realdata.SampleTenant;
import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The setup checklist's contract with the connect stepper: the first four lines are the
 * stepper's steps in its order, each href opens the stepper at that step, and only the
 * product master is required - so a products-only tenant reaches {@code requiredOpen = 0}.
 *
 * <p>Own Spring context (see {@code DataSourceIT} for why) and the real clock, so the
 * tokens this suite mints are not born expired.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.application.name=aatlas-api-setup-it",
    "aatlas.clock.fixed=false"
})
class SetupChecklistIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    @DisplayName("a fresh tenant sees suppliers, products, sales, purchases in stepper order, products required")
    void freshTenantChecklistFollowsTheStepper() throws Exception {
        String token = SampleTenant.signUp(mvc, json, "both", "US");

        mvc.perform(get("/api/v1/setup/checklist").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(0))
                .andExpect(jsonPath("$.requiredOpen").value(1))
                .andExpect(jsonPath("$.items[0].key").value("suppliers"))
                .andExpect(jsonPath("$.items[0].href").value("/app/connect?step=suppliers"))
                .andExpect(jsonPath("$.items[0].required").value(false))
                .andExpect(jsonPath("$.items[0].module").value("suppliers"))
                .andExpect(jsonPath("$.items[1].key").value("products"))
                .andExpect(jsonPath("$.items[1].href").value("/app/connect?step=products"))
                .andExpect(jsonPath("$.items[1].required").value(true))
                .andExpect(jsonPath("$.items[2].key").value("sales"))
                .andExpect(jsonPath("$.items[2].href").value("/app/connect?step=sales"))
                .andExpect(jsonPath("$.items[2].required").value(false))
                .andExpect(jsonPath("$.items[3].key").value("purchases"))
                .andExpect(jsonPath("$.items[3].href").value("/app/connect?step=purchases"))
                .andExpect(jsonPath("$.items[3].required").value(false))
                .andExpect(jsonPath("$.items[3].module").value("buy"));

        // Readiness sends the same tenant into the stepper at the products step.
        mvc.perform(get("/api/v1/workspace/readiness").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextSteps[0].key").value("connect"))
                .andExpect(jsonPath("$.nextSteps[0].href").value("/app/connect?step=products"));
    }

    @Test
    @DisplayName("a products-only tenant has nothing required left open")
    void productsAloneCloseTheRequiredLines() throws Exception {
        String token = SampleTenant.signUp(mvc, json, "both", "US");
        // No Supplier cells on purpose: a products file with one would tick step 1 as well.
        String csv = """
                Item No,Description,Category,Subcategory,UOM,Commodity,List Price,Unit Cost,On Hand,Branch,Supplier,Supplier Cost,Lead Time
                NP-100,1/2 IN COPPER TYPE L HARD TUBE 10FT,Plumbing,Pipe & tube,each,copper,,19.80,240,400100,,,
                NP-200,3/4 IN BRASS BALL VALVE,Plumbing,Valves,each,brass,,11.20,90,400100,,,
                """;
        SampleTenant.importAndCommit(mvc, json, token, "products", csv);

        mvc.perform(get("/api/v1/setup/checklist").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredOpen").value(0))
                .andExpect(jsonPath("$.items[0].key").value("suppliers"))
                .andExpect(jsonPath("$.items[0].done").value(false))
                .andExpect(jsonPath("$.items[1].key").value("products"))
                .andExpect(jsonPath("$.items[1].done").value(true))
                .andExpect(jsonPath("$.items[1].count").value(2))
                .andExpect(jsonPath("$.items[2].key").value("sales"))
                .andExpect(jsonPath("$.items[2].done").value(false));
    }
}
