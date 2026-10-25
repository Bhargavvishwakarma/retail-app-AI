package com.retail.aiprocessor.security;

import com.retail.aiprocessor.config.ServiceAuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ServiceAuthFilter extends OncePerRequestFilter {
    private static final String MODELS_PATH = "/internal/v1/models";
    private static final String SIGNATURE_HEADER = "X-Internal-Signature";
    private static final String TIMESTAMP_HEADER = "X-Internal-Timestamp";
    private static final String NONCE_HEADER = "X-Internal-Nonce";
    private static final int MAX_BODY_BYTES = 16 * 1024;

    private final ServiceAuthProperties properties;
    private final Map<String, Long> usedNonces = new ConcurrentHashMap<>();

    public ServiceAuthFilter(ServiceAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean allowedMethod = HttpMethod.POST.matches(request.getMethod())
                || (HttpMethod.GET.matches(request.getMethod()) && MODELS_PATH.equals(request.getRequestURI()));
        if (!allowedMethod || properties.sharedSecret() == null
                || properties.sharedSecret().isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid service authentication");
            return;
        }

        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        String nonce = request.getHeader(NONCE_HEADER);
        String suppliedSignature = request.getHeader(SIGNATURE_HEADER);
        if (!validTimestamp(timestamp) || nonce == null || nonce.isBlank() || nonce.length() > 128
                || suppliedSignature == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid service authentication");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES || (HttpMethod.GET.matches(request.getMethod()) && body.length != 0)
                || !validSignature(request, timestamp, nonce, body, suppliedSignature)
                || isReplay(nonce)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid service authentication");
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private boolean validTimestamp(String value) {
        try {
            long requestTime = Long.parseLong(value);
            long now = Instant.now().getEpochSecond();
            long skew = properties.maxClockSkewSeconds();
            return skew >= 0 && requestTime >= now - skew && requestTime <= now + skew;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean validSignature(HttpServletRequest request, String timestamp, String nonce,
                                   byte[] body, String supplied) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.sharedSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String canonical = request.getMethod() + "\n" + request.getRequestURI() + "\n"
                    + timestamp + "\n" + nonce + "\n" + bodyHash;
            byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            byte[] actual = HexFormat.of().parseHex(supplied);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean isReplay(String nonce) {
        long now = Instant.now().getEpochSecond();
        usedNonces.entrySet().removeIf(entry -> entry.getValue() < now);
        return usedNonces.putIfAbsent(nonce, now + properties.maxClockSkewSeconds()) != null;
    }

    private static class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("Asynchronous request reading is not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
