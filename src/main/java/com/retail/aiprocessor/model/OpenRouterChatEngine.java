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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class OpenRouterChatEngine implements ChatEngine {
    private static final Logger log = LoggerFactory.getLogger(OpenRouterChatEngine.class);
    private final String apiKey;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final long timeoutSeconds;

    public OpenRouterChatEngine(@Value("${app.openrouter.api-key:}") String apiKey,
                                ObjectMapper mapper,
                                @Value("${app.ai.timeout-seconds:75}") long timeoutSeconds) {
        this.apiKey = apiKey;
        this.mapper = mapper;
        this.timeoutSeconds = Math.max(5, Math.min(timeoutSeconds, 180));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String provider() { return "openrouter"; }

    @Override
    public RetailDataPlan plan(String question, List<ChatTurn> history, ChatRequest.UserContext user, String model) {
        if (apiKey.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "OPENROUTER_API_KEY is not configured");
        java.util.ArrayList<Map<String, String>> messages = new java.util.ArrayList<>();
        messages.add(Map.of("role", "system", "content", QueryPlanPrompt.system(user)));
        history.forEach(turn -> messages.add(Map.of("role", turn.role(), "content", turn.content())));
        messages.add(Map.of("role", "user", "content", question));
        Map<String, Object> payload = Map.of("model", model, "stream", false, "messages", messages,
                "response_format", Map.of("type", "json_object"));
        try {
            byte[] body = mapper.writeValueAsBytes(payload);
            HttpRequest request = plannerRequest(body);
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 400) {
                // Some providers behind the free router do not support response_format.
                byte[] compatibleBody = mapper.writeValueAsBytes(
                        Map.of("model", model, "stream", false, "messages", messages));
                response = http.send(plannerRequest(compatibleBody), HttpResponse.BodyHandlers.ofByteArray());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw upstreamFailure("query planning", response);
            }
            String plan = messageContent(mapper.readTree(response.body()).path("choices").path(0)
                    .path("message"));
            return RetailQueryPlanParser.parse(plan, mapper);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OpenRouter query planning was interrupted", exception);
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "OpenRouter query planning timed out", exception);
        } catch (Exception exception) {
            log.warn("query_plan_failed provider=openrouter model={} errorType={}", model,
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OpenRouter could not plan the retail data query", exception);
        }
    }

    @Override
    public ChatResult answer(String question, List<ChatTurn> history, ChatRequest.UserContext user, RetailDataSnapshot data, String model) {
        if (apiKey.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "OPENROUTER_API_KEY is not configured");
        java.util.ArrayList<Map<String, String>> messages = new java.util.ArrayList<>();
        messages.add(Map.of("role", "system", "content", ChatPrompt.system(user, data)));
        history.forEach(turn -> messages.add(Map.of("role", turn.role(), "content", turn.content())));
        messages.add(Map.of("role", "user", "content", question));
        Map<String, Object> payload = Map.of("model", model, "stream", false, "messages", messages);
        try {
            byte[] body = mapper.writeValueAsBytes(payload);
            String endpoint = "https://openrouter.ai/api/v1/chat/completions";
            long startedAt = System.nanoTime();
            log.info("model_request method=POST provider=openrouter model={} endpoint={} questionChars={} contextChars={}",
                    model, endpoint, question.length(), ChatPrompt.system(user, data).length());
            if (log.isDebugEnabled()) log.debug("model_request_payload provider=openrouter model={} body={}", model,
                    new String(body, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            log.info("model_response provider=openrouter model={} status={} responseBytes={} durationMs={}", model,
                    response.statusCode(), response.body().length, (System.nanoTime() - startedAt) / 1_000_000);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw upstreamFailure("answer generation", response);
            }
            JsonNode root = mapper.readTree(response.body());
            String answer = messageContent(root.path("choices").path(0).path("message")).trim();
            if (answer.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OpenRouter returned an empty answer");
            return new ChatResult(answer);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (java.net.http.HttpTimeoutException exception) {
            log.warn("model_timeout provider=openrouter model={} timeoutSeconds={}", model, timeoutSeconds);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "OpenRouter inference timed out", exception);
        } catch (Exception exception) {
            log.warn("model_failed provider=openrouter model={} errorType={}", model,
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OpenRouter is unavailable", exception);
        }
    }

    static String messageContent(JsonNode message) {
        JsonNode content = message.path("content");
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return "";
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            String value = part.isTextual() ? part.asText() : part.path("text").asText("");
            if (!value.isBlank()) {
                if (!text.isEmpty()) text.append('\n');
                text.append(value);
            }
        }
        return text.toString();
    }

    private HttpRequest plannerRequest(byte[] body) {
        return HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private ResponseStatusException upstreamFailure(String stage, HttpResponse<?> response) {
        if (response.statusCode() == 429) {
            String retryAfter = response.headers().firstValue("Retry-After")
                    .map(value -> " Retry after " + value + ".").orElse("");
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OpenRouter rate limit reached during " + stage + ". Check the configured API key and " +
                            "account/model quotas." + retryAfter);
        }
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "OpenRouter " + stage + " returned HTTP " + response.statusCode());
    }
}
