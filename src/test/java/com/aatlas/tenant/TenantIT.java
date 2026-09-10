package com.aatlas.tenant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Company profile and settings: {@code /api/v1/tenant} and {@code /api/v1/tenant/settings}. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantIT extends PostgresIntegrationTest {

    private static final String CLIENT_IP = "203.0.113.16";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private String signUpAndGetToken(String role, String country) throws Exception {
        String email = "harper-" + UUID.randomUUID() + "@kestrelsupply.com";
        String body = """
                {"fullName":"Harper Quinn","email":"%s","password":"Zephyr!42Bridge",
                 "company":"Kestrel Supply Co.","country":"%s","role":"%s"}
                """.formatted(email, country, role);
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    @DisplayName("GET /tenant returns the company profile")
    void getReturnsProfile() throws Exception {
        String token = signUpAndGetToken("both", "US");

        mvc.perform(get("/api/v1/tenant").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Kestrel Supply Co."))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.slug").exists())
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @DisplayName("the commercial director can rename the company")
    void directorCanRename() throws Exception {
        String token = signUpAndGetToken("both", "US");

        mvc.perform(patch("/api/v1/tenant")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Kestrel Supply Renamed"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Kestrel Supply Renamed"));
    }

    @Test
    @DisplayName("a manager-level seat cannot rename the company")
    void managerCannotRename() throws Exception {
        // seller: sales-side manager, not a head and not the director.
        String token = signUpAndGetToken("seller", "US");

        mvc.perform(patch("/api/v1/tenant")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Should Not Stick"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_allowed"));
    }

    @Test
    @DisplayName("GET /tenant/settings returns the locale words for the tenant's country")
    void getSettingsReturnsLocaleWords() throws Exception {
        String token = signUpAndGetToken("finance", "UK");

        mvc.perform(get("/api/v1/tenant/settings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.country").value("UK"))
                .andExpect(jsonPath("$.currency").value("GBP"))
                .andExpect(jsonPath("$.subdivisionNoun").value("Region"))
                .andExpect(jsonPath("$.subdivisionNounPlural").value("Regions"))
                .andExpect(jsonPath("$.regionNoun").value("Territory"));
    }

    @Test
    @DisplayName("currency may be any of the ten supported codes, not only USD/GBP")
    void currencyAcceptsAnyOfTheTenCodes() throws Exception {
        String token = signUpAndGetToken("finance", "US");

        mvc.perform(put("/api/v1/tenant/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency":"INR"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.country").value("US"))
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    @DisplayName("an unsupported currency is a 400 naming the field")
    void unsupportedCurrencyIsRejected() throws Exception {
        String token = signUpAndGetToken("finance", "US");

        mvc.perform(put("/api/v1/tenant/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency":"XYZ"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    @DisplayName("a country with no currency named picks that country's default")
    void countryAloneBringsItsOwnCurrency() throws Exception {
        String token = signUpAndGetToken("finance", "US");

        mvc.perform(put("/api/v1/tenant/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"country":"UK"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.country").value("UK"))
                .andExpect(jsonPath("$.currency").value("GBP"));
    }
}
