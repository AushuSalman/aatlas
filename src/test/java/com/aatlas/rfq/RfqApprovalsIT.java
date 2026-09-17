package com.aatlas.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.realdata.SampleTenant;
import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * The RFQ round end to end against a real PostgreSQL: create against the flagship item/region
 * ("HRD118902" / "south", the same pair {@code buy.BuyRealDataIT} pins against {@code
 * SampleOracle}), send, get simulated replies, read a recommendation, and award both ways - a
 * small order a purchase-manager can commit alone, and a large one that raises a real {@code
 * approval_request} instead, which {@code approvals} then grants and {@code
 * ApprovalOutcomeListener} turns into an actual purchase.
 *
 * <p>This is the sample tenant, so every quote {@code POST /rfqs/{id}/quotes} enters without a
 * typed reply is simulated ({@code RfqEngine.simulate}, spec-A S2 "rfq") - {@code
 * buy.BuyRealDataIT#rfqSimulatesOnlyOnTheSampleTenant} covers the gate itself (real tenant data
 * never gets a simulated reply); this class only needs every entered quote here to say so.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.application.name=aatlas-api-rfq-it",
    "aatlas.clock.fixed=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RfqApprovalsIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private static final String ITEM = "HRD118902";
    private static final String REGION = "south";

    private static String uniqueEmail() {
        return "buyer-" + UUID.randomUUID() + "@kestrelsupply.com";
    }

    /** {@code purchase-manager}: approveLimit 50,000, approver "Head of purchasing". */
    private String signUpAndConnect() throws Exception {
        MvcResult signup = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Priya Shah",
                                  "email": "%s",
                                  "password": "Zephyr!42Bridge",
                                  "company": "Kestrel Supply Co.",
                                  "country": "US",
                                  "role": "purchase-manager"
                                }
                                """.formatted(uniqueEmail())))
                .andExpect(status().isCreated())
                .andReturn();
        String token = json.readTree(signup.getResponse().getContentAsString()).get("accessToken").asText();

        mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"sample\"}"))
                .andExpect(status().isCreated());
        // The sample history (and the purchase orders the buy side reads) loads through the
        // import path after connect; wait for every batch before reading.
        SampleTenant.awaitReady(mvc, json, token);
        return token;
    }

    private List<String> supplierIds(String token, int qty) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/buy/compare")
                        .header("Authorization", "Bearer " + token)
                        .param("item", ITEM)
                        .param("region", REGION)
                        .param("qty", String.valueOf(qty)))
                .andExpect(status().isOk())
                .andReturn();
        List<String> ids = new ArrayList<>();
        for (JsonNode row : json.readTree(result.getResponse().getContentAsString())) {
            ids.add(row.get("supplierId").asText());
        }
        assertThat(ids).isNotEmpty();
        return ids;
    }

    private JsonNode createRfq(String token, int qty, int requiredDays) throws Exception {
        List<String> ids = supplierIds(token, qty);
        String supplierIdsJson = ids.stream().map(id -> "\"" + id + "\"")
                .reduce((a, b) -> a + "," + b).orElseThrow();
        MvcResult result = mvc.perform(post("/api/v1/rfqs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "itemNumber": "%s",
                                  "regionKey": "%s",
                                  "qty": %d,
                                  "requiredDays": %d,
                                  "priority": "balanced",
                                  "supplierIds": [%s]
                                }
                                """.formatted(ITEM, REGION, qty, requiredDays, supplierIdsJson)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode enterQuotes(String token, UUID rfqId) throws Exception {
        MvcResult sent = mvc.perform(post("/api/v1/rfqs/" + rfqId + "/quotes")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(sent.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("create, send, simulate replies, recommend, and award a small order the seat can approve alone")
    void createSendQuoteRecommendAndAwardWithinLimit() throws Exception {
        String token = signUpAndConnect();

        JsonNode rfq = createRfq(token, 50, 7);
        UUID id = UUID.fromString(rfq.get("id").asText());
        assertThat(rfq.get("status").asText()).isEqualTo("draft");
        assertThat(rfq.get("ref").asText()).startsWith("RFQ-");
        assertThat(rfq.get("invites")).isNotEmpty();

        mvc.perform(post("/api/v1/rfqs/" + id + "/send").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value("sent"));

        JsonNode quoted = enterQuotes(token, id);
        assertThat(quoted.get("status").asText()).isEqualTo("quoted");
        assertThat(quoted.get("quotes")).hasSameSizeAs(quoted.get("invites"));
        for (JsonNode q : quoted.get("quotes")) {
            assertThat(q.get("simulated").asBoolean())
                    .as("the sample tenant's un-replied invites are simulated, spec-A S2 \"rfq\"")
                    .isTrue();
        }

        MvcResult recResult = mvc.perform(get("/api/v1/rfqs/" + id + "/recommendation")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode recommendation = json.readTree(recResult.getResponse().getContentAsString());
        String supplierId = recommendation.get("supplierId").asText();
        assertThat(recommendation.get("reason").asText()).isNotBlank();

        MvcResult awardResult = mvc.perform(post("/api/v1/rfqs/" + id + "/award")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\": \"%s\"}".formatted(supplierId)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode award = json.readTree(awardResult.getResponse().getContentAsString());
        assertThat(award.get("ok").asBoolean()).isTrue();

        MvcResult afterAward = mvc.perform(get("/api/v1/rfqs/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode after = json.readTree(afterAward.getResponse().getContentAsString());
        assertThat(after.get("status").asText()).isEqualTo("awarded");
        assertThat(after.get("awardedSupplierId").asText()).isEqualTo(supplierId);
        assertThat(after.get("decisionId").isNull()).isFalse();
    }

    @Test
    @DisplayName("awarding a large order raises an approval request instead of committing, and approving it "
            + "finishes the purchase")
    void awardOverLimitRaisesApprovalThenGrantingItCommitsThePurchase() throws Exception {
        String token = signUpAndConnect();

        // ~$7.9/unit landed (see BuyEngineGoldenIT's golden figures for this item/region) - a
        // large quantity comfortably clears purchase-manager's $50,000 limit.
        JsonNode rfq = createRfq(token, 8000, 7);
        UUID id = UUID.fromString(rfq.get("id").asText());

        mvc.perform(post("/api/v1/rfqs/" + id + "/send").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        enterQuotes(token, id);

        MvcResult recResult = mvc.perform(get("/api/v1/rfqs/" + id + "/recommendation")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String supplierId = json.readTree(recResult.getResponse().getContentAsString()).get("supplierId").asText();

        MvcResult awardResult = mvc.perform(post("/api/v1/rfqs/" + id + "/award")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\": \"%s\"}".formatted(supplierId)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode award = json.readTree(awardResult.getResponse().getContentAsString());
        assertThat(award.get("ok").asBoolean()).isFalse();
        assertThat(award.get("pending").asBoolean()).isTrue();
        assertThat(award.get("limit").asDouble()).isEqualTo(50000.0);
        assertThat(award.get("approver").asText()).isEqualTo("Head of purchasing");

        MvcResult afterAward = mvc.perform(get("/api/v1/rfqs/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode after = json.readTree(afterAward.getResponse().getContentAsString());
        assertThat(after.get("status").asText()).isEqualTo("awarded");
        UUID decisionId = UUID.fromString(after.get("decisionId").asText());

        // "Mine": the requester sees their own request regardless of role, per ApprovalService#list.
        MvcResult listResult = mvc.perform(get("/api/v1/approvals").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = json.readTree(listResult.getResponse().getContentAsString());
        JsonNode mine = null;
        for (JsonNode row : list) {
            if (row.get("decisionId").asText().equals(decisionId.toString())) {
                mine = row;
            }
        }
        assertThat(mine).as("the raised request appears in GET /approvals").isNotNull();
        assertThat(mine.get("status").asText()).isEqualTo("pending");
        assertThat(mine.get("approverRole").asText()).isEqualTo("purchase-head");
        assertThat(mine.get("mine").asBoolean()).isTrue();
        assertThat(mine.get("decision").get("status").asText()).isEqualTo("pending");
        UUID approvalId = UUID.fromString(mine.get("id").asText());

        mvc.perform(post("/api/v1/approvals/" + approvalId + "/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"Cleared for this quarter's budget.\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value("approved"));

        // ApprovalGranted is relayed after the approve transaction commits (same "relayed
        // after commit" contract every other listener in this codebase relies on) - poll
        // rather than assert immediately.
        awaitDecisionApplied(token, decisionId);
    }

    private void awaitDecisionApplied(String token, UUID decisionId) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            MvcResult result = mvc.perform(get("/api/v1/decisions/" + decisionId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode decision = json.readTree(result.getResponse().getContentAsString()).get("decision");
            if ("applied".equals(decision.get("status").asText())) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Decision was not applied within 10s of the approval being granted");
    }
}
