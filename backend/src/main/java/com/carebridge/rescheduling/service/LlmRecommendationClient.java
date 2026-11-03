package com.carebridge.rescheduling.service;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Calls the configured OpenAI-compatible gateway for recommendation explanations. */
@Component
public class LlmRecommendationClient {
    private static final Logger log = LoggerFactory.getLogger(LlmRecommendationClient.class);
    private final RestClient client;
    private final ObjectMapper json;
    private final String model;
    private final String apiKey;
    private final String product;

    public LlmRecommendationClient(RestClient.Builder builder, ObjectMapper json,
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.model}") String model,
            @Value("${llm.product:PC1}") String product) {
        this.client = builder.baseUrl(baseUrl).build();
        this.json = json;
        this.apiKey = apiKey;
        this.model = model;
        this.product = product;
        log.info("LLM recommendation client configured: model={}, product={}, apiKeyConfigured={}", model, product,
                apiKey != null && !apiKey.isBlank());
    }

    public Recommendation explain(Context context) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("LLM recommendation fallback: LLM_API_KEY is not configured");
            return null;
        }
        try {
            log.info("LLM recommendation request started: model={}, trigger={}, currentSlot={}, suggestedSlot={}",
                    model, context.triggerType(), context.currentSlot(), context.recommendedStart());
            String prompt = ("You are a clinical scheduling assistant. Explain the safe replacement appointment below. "
                    + "Do not invent providers, times, availability, or medical facts. Return ONLY valid JSON with "
                    + "exactly one field: explanation. Plain English, maximum 180 characters.\n"
                    + "Patient: %s\nAppointment type: %s\nTrigger: %s\nCurrent doctor: %s\nCurrent time: %s\n"
                    + "Suggested doctor: %s\nSuggested time: %s\n"
                    + "Candidate rule: earliest available time with a different available doctor.")
                    .formatted(context.patientName(), context.appointmentType(), context.triggerType(),
                            context.currentProvider(), context.currentSlot(), context.recommendedProvider(),
                            context.recommendedStart());
            Map<String, Object> request = Map.of("model", model, "temperature", 0.1,
                    "messages", List.of(Map.of("role", "user", "content", prompt)));
            String response = client.post().contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey).header("product", product)
                    .body(request).retrieve().body(String.class);
            JsonNode root = json.readTree(response);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                log.warn("LLM recommendation fallback: gateway response did not contain message content");
                return null;
            }
            JsonNode result = json.readTree(stripMarkdownFence(content));
            String explanation = result.path("explanation").asText();
            if (explanation.isBlank() || explanation.length() > 500) {
                log.warn("LLM recommendation fallback: gateway returned invalid recommendation fields");
                return null;
            }
            log.info("LLM recommendation success: trigger={}, currentSlot={}, suggestedSlot={}",
                    context.triggerType(), context.currentSlot(), context.recommendedStart());
            return new Recommendation(explanation);
        } catch (Exception exception) {
            log.warn("LLM recommendation fallback: gateway request failed ({})", exception.getMessage());
            return null;
        }
    }

    private String stripMarkdownFence(String value) {
        String cleaned = value.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```$", "");
        }
        return cleaned.trim();
    }

    public record Context(String patientName, String appointmentType, String triggerType,
            String currentProvider, String currentSlot, String recommendedProvider, String recommendedStart) {}
    public record Recommendation(String explanation) {}
}