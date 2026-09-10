package com.aatlas.assistant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.smoke.PostgresIntegrationTest;
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

/** Ask Aatlas end to end, against a real PostgreSQL: every intent, suggestions, history. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "aatlas.clock.fixed=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssistantIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private String sellToken;
    private String buyToken;

    private String signUp(String role) throws Exception {
        String body = """
                {
                  "fullName": "Alex Moreno",
                  "email": "assistant-%s@kestrelsupply.com",
                  "password": "Zephyr!42Bridge",
                  "company": "Kestrel Supply Co.",
                  "country": "US",
                  "role": "%s"
                }
                """.formatted(UUID.randomUUID(), role);
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @BeforeAll
    void setUp() throws Exception {
        sellToken = signUp("sales-head");
        buyToken = signUp("purchase-head");
    }

    @Test
    @DisplayName("raise prices")
    void raisePrices() throws Exception {
        ask(sellToken, "Which products should I increase prices on today?")
                .andExpect(jsonPath("$.cta.href").value("/app/sell/bulk?preset=raise"));
    }

    @Test
    @DisplayName("which supplier")
    void whichSupplier() throws Exception {
        ask(sellToken, "Which supplier should we use for a 50,000-unit copper order?")
                .andExpect(jsonPath("$.title").value(org.hamcrest.Matchers.startsWith("Recommended:")))
                .andExpect(jsonPath("$.cta.href").value(org.hamcrest.Matchers.containsString("/app/buy?")));
    }

    @Test
    @DisplayName("liquidate")
    void liquidate() throws Exception {
        ask(sellToken, "What should I liquidate?")
                .andExpect(jsonPath("$.summary").isNotEmpty());
    }

    @Test
    @DisplayName("hold vs sell")
    void holdVsSell() throws Exception {
        ask(sellToken, "Should I hold copper tube at Dallas or sell now?")
                .andExpect(jsonPath("$.cta.href").value(org.hamcrest.Matchers.containsString("panel=timing")));
    }

    @Test
    @DisplayName("what changed")
    void whatChanged() throws Exception {
        ask(sellToken, "What changed today?")
                .andExpect(jsonPath("$.title").value("Since yesterday"));
    }

    @Test
    @DisplayName("demand by region")
    void demandByRegion() throws Exception {
        ask(sellToken, "Where is demand growing?")
                .andExpect(jsonPath("$.title").value(org.hamcrest.Matchers.startsWith("Demand is growing fastest")));
    }

    @Test
    @DisplayName("a store, by city")
    void aStore() throws Exception {
        ask(sellToken, "Tell me about Dallas")
                .andExpect(jsonPath("$.title").value("Dallas #100959"));
    }

    @Test
    @DisplayName("nothing matches: the suggested questions come back")
    void nothingMatches() throws Exception {
        ask(sellToken, "xyzzy plugh")
                .andExpect(jsonPath("$.title").value("I can answer these"))
                .andExpect(jsonPath("$.lines.length()").value(6));
    }

    @Test
    @DisplayName("suggestions are persona-aware: sell-side and buy-side seats see a different order")
    void suggestionsArePersonaAware() throws Exception {
        MvcResult sellResult = mvc.perform(get("/api/v1/assistant/suggestions")
                        .header("Authorization", "Bearer " + sellToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andReturn();
        MvcResult buyResult = mvc.perform(get("/api/v1/assistant/suggestions")
                        .header("Authorization", "Bearer " + buyToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(sellResult.getResponse().getContentAsString())
                .isNotEqualTo(buyResult.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("history: recent questions come back newest first")
    void historyRecordsQuestions() throws Exception {
        ask(sellToken, "What changed today?");
        ask(sellToken, "Where is demand growing?");

        mvc.perform(get("/api/v1/assistant/history").header("Authorization", "Bearer " + sellToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].question").value("Where is demand growing?"))
                .andExpect(jsonPath("$[1].question").value("What changed today?"));
    }

    private org.springframework.test.web.servlet.ResultActions ask(String token, String question) throws Exception {
        return mvc.perform(post("/api/v1/assistant/ask")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("question", question))))
                .andExpect(status().isOk());
    }
}
