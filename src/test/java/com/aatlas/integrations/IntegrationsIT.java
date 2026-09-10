package com.aatlas.integrations;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/** Integrations and MCP end to end, against a real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "aatlas.clock.fixed=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegrationsIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private String token;

    @BeforeAll
    void setUp() throws Exception {
        String body = """
                {
                  "fullName": "Alex Moreno",
                  "email": "integrations-%s@kestrelsupply.com",
                  "password": "Zephyr!42Bridge",
                  "company": "Kestrel Supply Co.",
                  "country": "US",
                  "role": "both"
                }
                """.formatted(UUID.randomUUID());
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        token = json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    @DisplayName("catalogue lists 15 systems, all available; connecting and disconnecting one flips its status")
    void catalogueAndConnection() throws Exception {
        mvc.perform(get("/api/v1/integrations").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(15))
                .andExpect(jsonPath("$[0].key").value("p21"))
                .andExpect(jsonPath("$[0].status").value("available"));

        mvc.perform(post("/api/v1/integrations/salesforce/connect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"config": {"instanceUrl": "https://kestrel.my.salesforce.com"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("connected"));

        mvc.perform(get("/api/v1/integrations").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='salesforce')].status").value("connected"));

        mvc.perform(delete("/api/v1/integrations/salesforce/connect").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/integrations").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='salesforce')].status").value("available"));
    }

    @Test
    @DisplayName("connecting an unknown integration is a 404")
    void unknownIntegrationIsNotFound() throws Exception {
        mvc.perform(post("/api/v1/integrations/not-a-system/connect")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("mcp: endpoint and three clients, connect claude, permissions default then updated")
    void mcpClientsAndPermissions() throws Exception {
        mvc.perform(get("/api/v1/mcp").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endpoint").value("https://aatlas-ai.vercel.app/mcp"))
                .andExpect(jsonPath("$.clients.length()").value(3))
                .andExpect(jsonPath("$.clients[0].key").value("claude"))
                .andExpect(jsonPath("$.clients[0].connected").value(false));

        mvc.perform(post("/api/v1/mcp/clients/claude/connect").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true));

        mvc.perform(get("/api/v1/mcp").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clients[0].connected").value(true));

        mvc.perform(get("/api/v1/mcp/permissions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(16))
                .andExpect(jsonPath("$.groups.read.title").value("Read"))
                .andExpect(jsonPath("$.permissions[?(@.key=='read-products')].enabled").value(true))
                .andExpect(jsonPath("$.permissions[?(@.key=='restricted-apply-price')].enabled").value(false));

        mvc.perform(put("/api/v1/mcp/permissions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissions": {"restricted-apply-price": true, "read-orders": true}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[?(@.key=='restricted-apply-price')].enabled").value(true))
                .andExpect(jsonPath("$.permissions[?(@.key=='restricted-apply-price')].requiresApproval")
                        .value(true))
                .andExpect(jsonPath("$.permissions[?(@.key=='read-orders')].enabled").value(true));
    }
}
