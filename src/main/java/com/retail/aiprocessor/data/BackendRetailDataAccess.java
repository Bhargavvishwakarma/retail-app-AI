package com.retail.aiprocessor.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.aiprocessor.chat.ChatRequest;
import com.retail.aiprocessor.model.RetailQueryPlan;
import com.retail.aiprocessor.security.InternalRequestSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class BackendRetailDataAccess implements ApprovedRetailDataAccess {
    private static final Logger log = LoggerFactory.getLogger(BackendRetailDataAccess.class);
    private static final String PATH = "/internal/v1/ai-data/query";
    private final String backendUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final InternalRequestSigner signer;

    public BackendRetailDataAccess(@Value("${app.retail-backend.url:http://localhost:8080}") String backendUrl,
                                   ObjectMapper mapper, InternalRequestSigner signer) {
        this.backendUrl = backendUrl.replaceAll("/+$", "");
        this.mapper = mapper;
        this.signer = signer;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public RetailDataSnapshot retrieve(RetailQueryPlan plan, ChatRequest.UserContext user) {
        var request = toRequest(plan);
        long startedAt = System.nanoTime();
        try {
            byte[] body = mapper.writeValueAsBytes(request);
            var headers = signer.sign("POST", PATH, body);
            log.info("retail_data_request method=POST path={} operation={} limit={} from={} to={} threshold={}",
                    PATH, request.get("operation"), request.get("limit"), request.get("from"),
                    request.get("to"), request.get("threshold"));
            if (log.isDebugEnabled()) {
                log.debug("retail_data_request_payload method=POST path={} payload={}", PATH,
                        new String(body, java.nio.charset.StandardCharsets.UTF_8));
            }
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(backendUrl + PATH))
                    .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                    .header("X-Request-Id", requestId())
                    .header("X-Internal-Timestamp", headers.timestamp())
                    .header("X-Internal-Nonce", headers.nonce())
                    .header("X-Internal-Signature", headers.signature())
                    .header("X-Actor-Assertion", user.actorToken())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<byte[]> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            log.info("retail_data_response method=POST path={} status={} durationMs={}", PATH,
                    response.statusCode(), elapsedMs(startedAt));
            if (response.statusCode() == 403) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "The authenticated user is not authorized for the requested retail data");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(response.statusCode() == 503
                        ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY,
                        "Retail data service returned HTTP " + response.statusCode());
            }
            var root = mapper.readTree(response.body());
            Map<String, Object> contextValues = new LinkedHashMap<>();
            contextValues.put("operation", request.get("operation"));
            if (request.containsKey("limit")) contextValues.put("limit", request.get("limit"));
            if (request.containsKey("threshold")) contextValues.put("threshold", request.get("threshold"));
            if (request.containsKey("from")) contextValues.put("from", request.get("from"));
            if (request.containsKey("to")) contextValues.put("to", request.get("to"));
            contextValues.put("results", root.path("data"));
            String context = mapper.writeValueAsString(contextValues);
            String dataSource = root.path("dataSource").asText("retail");
            log.info("retail_data_loaded operation={} dataSource={} rowCount={}", request.get("operation"),
                    dataSource, root.path("data").size());
            return new RetailDataSnapshot(context, List.of(dataSource));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Retail data request timed out", exception);
        } catch (Exception exception) {
            log.warn("retail_data_failed operation={} errorType={} durationMs={}", request.get("operation"),
                    exception.getClass().getSimpleName(), elapsedMs(startedAt));
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Retail data service is unavailable", exception);
        }
    }

    private String requestId() {
        String value = MDC.get("requestId");
        return value == null ? "" : value;
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private Map<String, Object> toRequest(RetailQueryPlan plan) {
        if (!plan.requiresRetailData()) throw new IllegalArgumentException("A retail data operation is required");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("operation", plan.operation());
        if (plan.query() != null && !plan.query().isBlank()) request.put("query", plan.query());
        if (plan.from() != null) request.put("from", plan.from());
        if (plan.to() != null) request.put("to", plan.to());
        if (plan.threshold() != null) request.put("threshold", plan.threshold());
        if (plan.limit() != null) request.put("limit", plan.limit());
        if ("CUSTOMER_DIRECTORY".equals(plan.operation())) request.put("fields", plan.fields());
        return request;
    }
}
