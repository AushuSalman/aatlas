package com.aatlas.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code /api/v1/reference/*}: countries and currencies, public and cacheable. No signup, no
 * token - these are what the sign-up form's country picker calls before an account exists.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReferenceIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    @DisplayName("countries is public, carries an ETag, and lists US and UK")
    void countriesIsPublicWithEtag() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/reference/countries"))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")))
                .andReturn();

        JsonNode countries = json.readTree(result.getResponse().getContentAsString());
        List<String> codes = new ArrayList<>();
        countries.forEach(c -> codes.add(c.get("code").asText()));
        assertThat(codes).containsExactly("US", "UK");
    }

    @Test
    @DisplayName("a conditional request with the current ETag is a 304")
    void conditionalRequestIsNotModified() throws Exception {
        MvcResult first = mvc.perform(get("/api/v1/reference/countries"))
                .andExpect(status().isOk())
                .andReturn();
        String etag = first.getResponse().getHeader("ETag");

        mvc.perform(get("/api/v1/reference/countries").header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    @DisplayName("currencies is public and lists the ten seeded codes in the seed's order")
    void currenciesIsPublicAndOrdered() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/reference/currencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").exists())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        List<String> codes = new ArrayList<>();
        body.get("currencies").forEach(c -> codes.add(c.get("code").asText()));

        // The seed's order, which is also src/lib/platform/money.ts's CURRENCIES order and
        // the order the currency picker should show them in. Map.copyOf silently scrambling
        // this was a real regression this test would have caught.
        assertThat(codes).containsExactly("USD", "GBP", "EUR", "CAD", "AUD", "INR", "MXN", "CNY", "VND", "JPY");
    }

    @Test
    @DisplayName("USD's rate is exactly 1, the base currency")
    void usdIsTheBase() throws Exception {
        mvc.perform(get("/api/v1/reference/currencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencies[0].code").value("USD"))
                .andExpect(jsonPath("$.currencies[0].perUsd").value(1));
    }
}
