package com.retail.aiprocessor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.service-auth")
public record ServiceAuthProperties(String sharedSecret, long maxClockSkewSeconds) {
}
