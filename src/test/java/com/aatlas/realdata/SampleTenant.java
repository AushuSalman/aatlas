package com.aatlas.realdata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.common.time.AatlasClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Shared test support for every IT that works on a sample or an imported tenant: sign up,
 * connect the sample, wait for the four sample batches to land, import a file and wait for
 * its commit.
 *
 * <p>The sample loads asynchronously after {@code POST /data-sources}; a test that reads
 * history right after connecting must {@link #awaitReady} first or it races the loader.
 */
public final class SampleTenant {

    private SampleTenant() {
    }

    public static String signUp(MockMvc mvc, ObjectMapper json, String role, String country) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Priya Shah",
                                  "email": "sample-%s@kestrelsupply.com",
                                  "password": "Zephyr!42Bridge",
                                  "company": "Kestrel Supply Co.",
                                  "country": "%s",
                                  "role": "%s"
                                }
                                """.formatted(UUID.randomUUID(), country, role)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    /** Decodes the JWT payload without verifying the signature; tests only read the claims. */
    public static UUID tenantIdOf(ObjectMapper json, String token) throws Exception {
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        return UUID.fromString(json.readTree(payload).get("tid").asText());
    }

    public static void connectSample(MockMvc mvc, String token) throws Exception {
        mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"sample\"}"))
                .andExpect(status().isCreated());
    }

    /** Signs up, connects the sample and waits for every sample batch to commit. */
    public static String signUpAndConnect(MockMvc mvc, ObjectMapper json, String role) throws Exception {
        String token = signUp(mvc, json, role, "US");
        connectSample(mvc, token);
        awaitReady(mvc, json, token);
        return token;
    }

    public static JsonNode readiness(MockMvc mvc, ObjectMapper json, String token) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/workspace/readiness").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Polls readiness until the sample load is over and every one of the four batches is
     * {@code COMMITTED}. A {@code FAILED} batch fails at once with its reason.
     */
    public static JsonNode awaitReady(MockMvc mvc, ObjectMapper json, String token) {
        JsonNode[] last = new JsonNode[1];
        Awaitility.await("sample history loaded")
                .atMost(Duration.ofSeconds(120))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    JsonNode readiness = readiness(mvc, json, token);
                    last[0] = readiness;
                    JsonNode loading = readiness.get("sampleLoading");
                    JsonNode batches = loading.get("batches");
                    for (JsonNode batch : batches) {
                        if ("FAILED".equals(batch.get("status").asText())) {
                            throw new IllegalStateException("Sample " + batch.get("kind").asText() + " failed: "
                                    + batch.path("failureReason").asText());
                        }
                    }
                    if (loading.get("active").asBoolean()) {
                        throw new AssertionError("sample load still active: " + batches);
                    }
                    if (batches.size() < 4) {
                        throw new AssertionError("only " + batches.size() + " sample batches claimed");
                    }
                    for (JsonNode batch : batches) {
                        if (!"COMMITTED".equals(batch.get("status").asText())) {
                            throw new AssertionError("sample " + batch.get("kind").asText() + " is "
                                    + batch.get("status").asText());
                        }
                    }
                });
        return last[0];
    }

    /** Uploads a CSV of {@code kind}, commits it, and waits for the commit; returns the final batch view. */
    public static JsonNode importAndCommit(MockMvc mvc, ObjectMapper json, String token, String kind, String csv)
            throws Exception {
        JsonNode uploaded = upload(mvc, json, token, kind, csv);
        UUID batchId = UUID.fromString(uploaded.get("id").asText());
        mvc.perform(post("/api/v1/imports/{id}/commit", batchId).header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
        return awaitCommitted(mvc, json, token, batchId);
    }

    public static JsonNode upload(MockMvc mvc, ObjectMapper json, String token, String kind, String csv)
            throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", kind + ".csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        MvcResult result = mvc.perform(multipart("/api/v1/imports").file(file)
                        .param("kind", kind)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    public static JsonNode awaitCommitted(MockMvc mvc, ObjectMapper json, String token, UUID batchId) {
        JsonNode[] last = new JsonNode[1];
        Awaitility.await("import " + batchId + " committed")
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    MvcResult result = mvc.perform(get("/api/v1/imports/{id}", batchId)
                                    .header("Authorization", "Bearer " + token))
                            .andExpect(status().isOk())
                            .andReturn();
                    JsonNode view = json.readTree(result.getResponse().getContentAsString());
                    last[0] = view;
                    String state = view.get("status").asText();
                    if ("FAILED".equals(state)) {
                        throw new IllegalStateException("import failed: " + view.path("failureReason").asText());
                    }
                    if (!"COMMITTED".equals(state)) {
                        throw new AssertionError("import is " + state);
                    }
                });
        return last[0];
    }

    public static SampleOracle oracle(AatlasClock clock) {
        return SampleOracle.load(clock.today());
    }
}
