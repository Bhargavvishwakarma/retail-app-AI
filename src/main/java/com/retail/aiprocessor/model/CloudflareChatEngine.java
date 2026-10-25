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

@Component
public class CloudflareChatEngine implements ChatEngine {
    private static final Logger log = LoggerFactory.getLogger(CloudflareChatEngine.class);
    private static final String ENDPOINT_TEMPLATE =
            "https://api.cloudflare.com/client/v4/accounts/%s/ai/v1/chat/completions";

    private final String accountId;
    private final String apiToken;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final long timeoutSeconds;

    public CloudflareChatEngine(@Value("${app.cloudflare.account-id:}") String accountId,
                                @Value("${app.cloudflare.api-token:}") String apiToken,
                                ObjectMapper mapper,
                                @Value("${app.ai.timeout-seconds:75}") long timeoutSeconds) {
        this.accountId = accountId == null ? "" : accountId.trim();
        this.apiToken = apiToken == null ? "" : apiToken.trim();
        this.mapper = mapper;
        this.timeoutSeconds = Math.max(5, Math.min(timeoutSeconds, 180));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String provider() {
        return "cloudflare";
    }

    @Override
    public RetailDataPlan plan(String question, List<ChatTurn> history,
                               ChatRequest.UserContext user, String model) {
        requireCredentials();
        List<Map<String, String>> messages = messages(question, history,
                QueryPlanPrompt.system(user));
        Map<String, Object> payload = Map.of("model", model, "stream", false, "messages", messages);
        try {
            HttpResponse<byte[]> response = send(payload);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw providerFailure("query planning", response.statusCode());
            }
            String plan = messageContent(mapper.readTree(response.body()).path("choices").path(0)
                    .path("message"));
            return RetailQueryPlanParser.parse(plan, mapper);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Cloudflare query planning was interrupted", exception);
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "Cloudflare query planning timed out", exception);
        } catch (Exception exception) {
            log.warn("query_plan_failed provider=cloudflare model={} errorType={}", model,
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Cloudflare could not plan the retail data query", exception);
        }
    }

    @Override
    public ChatResult answer(String question, List<ChatTurn> history, ChatRequest.UserContext user,
                             RetailDataSnapshot data, String model) {
        requireCredentials();
        String systemPrompt = ChatPrompt.system(user, data);
        List<Map<String, String>> messages = messages(question, history, systemPrompt);
        Map<String, Object> payload = Map.of("model", model, "stream", false,
                "max_tokens", 512, "messages", messages);
        long startedAt = System.nanoTime();
        try {
            log.info("model_request method=POST provider=cloudflare model={} questionChars={} contextChars={}",
                    model, question.length(), systemPrompt.length());
            if (log.isDebugEnabled()) log.debug("model_request_payload provider=cloudflare model={} body={}",
                    model, mapper.writeValueAsString(payload));
            HttpResponse<byte[]> response = send(payload);
            log.info("model_response provider=cloudflare model={} status={} responseBytes={} durationMs={}",
                    model, response.statusCode(), response.body().length,
                    (System.nanoTime() - startedAt) / 1_000_000);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw providerFailure("answer generation", response.statusCode());
            }
            JsonNode root = mapper.readTree(response.body());
            String answer = messageContent(root.path("choices").path(0).path("message")).trim();
            if (answer.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Cloudflare returned an empty answer");
            return new ChatResult(answer);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Cloudflare request was interrupted", exception);
        } catch (java.net.http.HttpTimeoutException exception) {
            log.warn("model_timeout provider=cloudflare model={} timeoutSeconds={}", model, timeoutSeconds);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "Cloudflare inference timed out", exception);
        } catch (Exception exception) {
            log.warn("model_failed provider=cloudflare model={} errorType={}", model,
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Cloudflare is unavailable", exception);
        }
    }

    static URI endpoint(String accountId) {
        return URI.create(ENDPOINT_TEMPLATE.formatted(accountId));
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

    private List<Map<String, String>> messages(String question, List<ChatTurn> history, String systemPrompt) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        history.forEach(turn -> messages.add(Map.of("role", turn.role(), "content", turn.content())));
        messages.add(Map.of("role", "user", "content", question));
        return messages;
    }

    private HttpResponse<byte[]> send(Map<String, Object> payload) throws Exception {
        byte[] body = mapper.writeValueAsBytes(payload);
        HttpRequest request = HttpRequest.newBuilder(endpoint(accountId))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private void requireCredentials() {
        if (accountId.isBlank() || apiToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "CLOUDFLARE_ACCOUNT_ID and CLOUDFLARE_API_TOKEN are required for Cloudflare models");
        }
    }

    private ResponseStatusException providerFailure(String stage, int status) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Cloudflare " + stage + " returned HTTP " + status);
    }
}
