package com.retail.aiprocessor.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudflareChatEngineTest {
    @Test
    void registersCloudflareProviderAndBuildsWorkersAiEndpoint() {
        var engine = new CloudflareChatEngine("0123456789abcdef0123456789abcdef", "test-token",
                new ObjectMapper(), 70);

        assertEquals("cloudflare", engine.provider());
        assertEquals("https://api.cloudflare.com/client/v4/accounts/0123456789abcdef0123456789abcdef/" +
                "ai/v1/chat/completions", CloudflareChatEngine.endpoint("0123456789abcdef0123456789abcdef").toString());
    }

    @Test
    void extractsTextFromOpenAiCompatibleContentBlocks() throws Exception {
        var message = new ObjectMapper().readTree("{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}");

        assertEquals("hello", CloudflareChatEngine.messageContent(message));
    }
}
