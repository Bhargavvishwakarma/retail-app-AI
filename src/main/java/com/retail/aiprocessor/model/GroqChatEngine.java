package com.retail.aiprocessor.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.aiprocessor.chat.ChatRequest;
import com.retail.aiprocessor.data.RetailDataSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
public class GroqChatEngine implements ChatEngine {
    private static final Logger log = LoggerFactory.getLogger(GroqChatEngine.class);
    private static final String ENDPOINT = "https://api.groq.com/openai/v1/responses";
    private final String apiKey;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final long timeoutSeconds;

    public GroqChatEngine(@Value("${app.groq.api-key:}") String apiKey,
                          ObjectMapper mapper,
                          @Value("${app.ai.timeout-seconds:75}") long timeoutSeconds) {
        this.apiKey = apiKey;
        this.mapper = mapper;
        this.timeoutSeconds = Math.max(5, Math.min(timeoutSeconds, 180));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String provider() { return "groq"; }

    @Override
    public RetailDataPlan plan(String question, List<ChatTurn> history, ChatRequest.UserContext user, String model) {
        if (apiKey.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "GROQ_API_KEY is not configured");
        List<Map<String, String>> input = new ArrayList<>();
        history.forEach(turn -> input.add(Map.of("role", turn.role(), "content", turn.content())));
        input.add(Map.of("role", "user", "content", question));
        Map<String, Object> payload = Map.of("model", model,
                "instructions", QueryPlanPrompt.system(user), "input", input);
        try {
            byte[] body = mapper.writeValueAsBytes(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Groq query planning returned HTTP " + response.statusCode());
            }
            JsonNode root = mapper.readTree(response.body());
            String plan = root.path("output_text").asText("").trim();
            if (plan.isEmpty()) {
                plan = StreamSupport.stream(root.path("output").spliterator(), false)
                        .flatMap(item -> StreamSupport.stream(item.path("content").spliterator(), false))
                        .filter(part -> "output_text".equals(part.path("type").asText()))
                        .map(part -> part.path("text").asText(""))
                        .collect(Collectors.joining()).trim();
            }
            return RetailQueryPlanParser.parse(plan, mapper);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Groq query planning was interrupted", exception);
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "Groq query planning timed out", exception);
        } catch (Exception exception) {
            log.warn("query_plan_failed provider=groq model={} errorType={}", model,
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Groq could not plan the retail data query", exception);
        }
    }

    @Override
    public ChatResult answer(String question, List<ChatTurn> history, ChatRequest.UserContext user,
                             RetailDataSnapshot data, String model) {
        if (apiKey.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "GROQ_API_KEY is not configured");
        List<Map<String, String>> input = new ArrayList<>();
        history.forEach(turn -> input.add(Map.of("role", turn.role(), "content", turn.content())));
        input.add(Map.of("role", "user", "content", question));
        Map<String, Object> payload = Map.of(
                "model", model,
                "instructions", ChatPrompt.system(user, data),
                "input", input);
        try {
            byte[] body = mapper.writeValueAsBytes(payload);
            long startedAt = System.nanoTime();
            log.info("model_request method=POST provider=groq model={} endpoint={} questionChars={} contextChars={}",
                    model, ENDPOINT, question.length(), ChatPrompt.system(user, data).length());
            if (log.isDebugEnabled()) log.debug("model_request_payload provider=groq model={} body={}", model,
                    new String(body, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            log.info("model_response provider=groq model={} status={} responseBytes={} durationMs={}", model,
                    response.statusCode(), response.body().length, (System.nanoTime() - startedAt) / 1_000_000);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Groq returned HTTP " + response.statusCode());
            }
            JsonNode root = mapper.readTree(response.body());
            String answer = root.path("output_text").asText("").trim();
            if (answer.isEmpty()) {
                answer = StreamSupport.stream(root.path("output").spliterator(), false)
                        .flatMap(item -> StreamSupport.stream(item.path("content").spliterator(), false))
                        .filter(part -> "output_text".equals(part.path("type").asText()))
                        .map(part -> part.path("text").asText(""))
                        .collect(Collectors.joining()).trim();
            }
            if (answer.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Groq returned an empty answer");
            return new ChatResult(answer);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Groq request was interrupted", exception);
        } catch (java.net.http.HttpTimeoutException exception) {
            log.warn("model_timeout provider=groq model={} timeoutSeconds={}", model, timeoutSeconds);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Groq inference timed out", exception);
        } catch (Exception exception) {
            log.warn("model_failed provider=groq model={} errorType={}", model,
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Groq is unavailable", exception);
        }
    }
}
