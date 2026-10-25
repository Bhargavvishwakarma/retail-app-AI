package com.retail.aiprocessor.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class InternalRequestSigner {
    private final String secret;
    public InternalRequestSigner(@Value("${app.service-auth.shared-secret:}") String secret) { this.secret = secret; }
    public SignedHeaders sign(String method, String path, byte[] body) {
        if (secret.isBlank()) throw new IllegalStateException("AI_PROCESSOR_SHARED_SECRET is not configured");
        try {
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString();
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String canonical = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return new SignedHeaders(timestamp, nonce,
                    HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception exception) { throw new IllegalStateException("Could not sign internal request", exception); }
    }
    public record SignedHeaders(String timestamp, String nonce, String signature) { }
}
