package com.retail.aiprocessor.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.aiprocessor.chat.ChatRequest;
import com.retail.aiprocessor.data.RetailDataSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
public class GeminiChatEngine implements ChatEngine {
    private static final Logger log = LoggerFactory.getLogger(GeminiChatEngine.class);
    private final String apiKey;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final long timeoutSeconds;

    public GeminiChatEngine(@Value("${app.gemini.api-key:}") String apiKey,
                            ObjectMapper mapper,
                            @Value("${app.ai.timeout-seconds:75}") long timeoutSeconds) {
        this.apiKey = apiKey;
        this.mapper = mapper;
        this.timeoutSeconds = Math.max(5, Math.min(timeoutSeconds, 180));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String provider() { return "gemini"; }

    @Override
    public RetailDataPlan plan(String question, List<ChatTurn> history, ChatRequest.UserContext user, String model) {
        if (apiKey.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "GEMINI_API_KEY is not configured");
        java.util.ArrayList<Map<String, Object>> contents = new java.util.ArrayList<>();
        history.forEach(turn -> {
            Map<String, Object> part = new java.util.LinkedHashMap<>();
            part.put("text", turn.content());
            if ("assistant".equals(turn.role()) && turn.thoughtSignature() != null
                    && !turn.thoughtSignature().isBlank()) part.put("thoughtSignature", turn.thoughtSignature());
            contents.add(Map.of("role", "assistant".equals(turn.role()) ? "model" : "user",
                    "parts", List.of(part)));
        });
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", question))));
        Map<String, Object> payload = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", QueryPlanPrompt.system(user)))),
                "contents", contents,
                "generationConfig", Map.of("maxOutputTokens", 256,
                        "responseMimeType", "application/json",
                        "thinkingConfig", Map.of("thinkingLevel", "LOW")));
        try {
            byte[] body = mapper.writeValueAsBytes(payload);
            String encodedModel = URLEncoder.encode(model, StandardCharsets.UTF_8);
            String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + encodedModel + ":generateContent";
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type", "application/json")
                    .header("X-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Gemini query planning returned HTTP " + response.statusCode() + ": " + providerError(response.body()));
            }
            JsonNode parts = mapper.readTree(response.body()).path("candidates").path(0).path("content").path("parts");
            String plan = StreamSupport.stream(parts.spliterator(), false)
                    .map(part -> part.path("text").asText(""))
                    .collect(Collectors.joining()).trim();
            return RetailQueryPlanParser.parse(plan, mapper);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Gemini query planning was interrupted", exception);
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "Gemini query planning timed out", exception);
        } catch (Exception exception) {
            log.warn("query_plan_failed provider=gemini model={} errorType={}", model,
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Gemini could not plan the retail data query", exception);
        }
    }

    @Override
    public ChatResult answer(String question, List<ChatTurn> history, ChatRequest.UserContext user, RetailDataSnapshot data, String model) {
        if (apiKey.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "GEMINI_API_KEY is not configured");
        java.util.ArrayList<Map<String, Object>> contents = new java.util.ArrayList<>();
        history.forEach(turn -> {
            Map<String, Object> part = new java.util.LinkedHashMap<>();
            part.put("text", turn.content());
            if ("assistant".equals(turn.role()) && turn.thoughtSignature() != null
                    && !turn.thoughtSignature().isBlank()) {
                part.put("thoughtSignature", turn.thoughtSignature());
            }
            contents.add(Map.of("role", "assistant".equals(turn.role()) ? "model" : "user",
                    "parts", List.of(part)));
        });
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", question))));
        Map<String, Object> payload = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", ChatPrompt.system(user, data)))),
                "contents", contents,
                "generationConfig", Map.of(
                        "maxOutputTokens", 512,
                        "thinkingConfig", Map.of("thinkingLevel", "LOW")));
        try {
            byte[] body = mapper.writeValueAsBytes(payload);
            String encodedModel = URLEncoder.encode(model, StandardCharsets.UTF_8);
            String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + encodedModel + ":generateContent";
            long startedAt = System.nanoTime();
            log.info("model_request method=POST provider=gemini model={} endpoint={} questionChars={} contextChars={}",
                    model, endpoint, question.length(), ChatPrompt.system(user, data).length());
            if (log.isDebugEnabled()) log.debug("model_request_payload provider=gemini model={} body={}", model,
                    new String(body, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type", "application/json")
                    .header("X-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<byte[]> response = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 503 || attempt == 2) break;
                log.warn("model_retry provider=gemini model={} status=503 attempt=2", model);
                Thread.sleep(500);
            }
            log.info("model_response provider=gemini model={} status={} responseBytes={} durationMs={}", model,
                    response.statusCode(), response.body().length, (System.nanoTime() - startedAt) / 1_000_000);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String providerReason = providerError(response.body());
                log.warn("model_provider_error provider=gemini model={} status={} reason={}", model,
                        response.statusCode(), providerReason);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Gemini returned HTTP " + response.statusCode() + ": " + providerReason);
            }
            JsonNode parts = mapper.readTree(response.body()).path("candidates").path(0).path("content").path("parts");
            String answer = StreamSupport.stream(parts.spliterator(), false)
                    .map(part -> part.path("text").asText(""))
                    .collect(Collectors.joining()).trim();
            String thoughtSignature = StreamSupport.stream(parts.spliterator(), false)
                    .map(part -> part.path("thoughtSignature").asText(null))
                    .filter(signature -> signature != null && !signature.isBlank())
                    .findFirst().orElse(null);
            if (answer.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Gemini returned an empty answer");
            return new ChatResult(answer, thoughtSignature);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Gemini request was interrupted", exception);
        } catch (java.net.http.HttpTimeoutException exception) {
            log.warn("model_timeout provider=gemini model={} timeoutSeconds={}", model, timeoutSeconds);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Gemini inference timed out", exception);
        } catch (Exception exception) {
            log.warn("model_failed provider=gemini model={} errorType={}", model,
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Gemini is unavailable", exception);
        }
    }

    private String providerError(byte[] body) {
        try {
            String reason = mapper.readTree(body).path("error").path("message").asText("No provider error details");
            return reason.length() <= 300 ? reason : reason.substring(0, 300);
        } catch (Exception ignored) {
            return "Unable to parse provider error response";
        }
    }
}
