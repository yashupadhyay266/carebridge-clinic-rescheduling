package com.carebridge.rescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

class LlmRecommendationClientTest {

    private static final String URL = "https://llm.test/v1/chat/completions";
    private static final String KEY = "test-only-key";

    @Test
    void sendsCompatibleRequestAndParsesExplanation() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + KEY))
                .andExpect(header("product", "PC1"))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\"explanation\\":\\"Nearest suitable opening with an available doctor.\\"}"}}]}
                        """, MediaType.APPLICATION_JSON));

        LlmRecommendationClient client = new LlmRecommendationClient(
                builder, new ObjectMapper(), URL, KEY, "test-model", "PC1");

        LlmRecommendationClient.Recommendation result = client.explain(
                new LlmRecommendationClient.Context("Patient", "ROUTINE", "PROVIDER_OFFLINE",
                        "Dr. Current", "SLT-001", "Dr. Next", "2026-11-03T10:00:00Z"));

        assertThat(result).isNotNull();
        assertThat(result.explanation()).isEqualTo("Nearest suitable opening with an available doctor.");
        server.verify();
    }

    @Test
    void fallsBackWhenKeyIsMissing() {
        LlmRecommendationClient client = new LlmRecommendationClient(
                RestClient.builder(), new ObjectMapper(), URL, "", "test-model", "PC1");

        assertThat(client.explain(context())).isNull();
    }

    @Test
    void fallsBackWhenGatewayResponseIsInvalid() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"choices\":[{\"message\":{\"content\":\"not-json\"}}]}",
                MediaType.APPLICATION_JSON));

        LlmRecommendationClient client = new LlmRecommendationClient(
                builder, new ObjectMapper(), URL, KEY, "test-model", "PC1");

        assertThat(client.explain(context())).isNull();
        server.verify();
    }

    private LlmRecommendationClient.Context context() {
        return new LlmRecommendationClient.Context("Patient", "ROUTINE", "PROVIDER_OFFLINE",
                "Dr. Current", "SLT-001", "Dr. Next", "2026-11-03T10:00:00Z");
    }
}