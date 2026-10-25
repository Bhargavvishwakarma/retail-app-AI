package com.retail.aiprocessor.model;

import com.retail.aiprocessor.chat.ChatRequest;
import com.retail.aiprocessor.data.RetailDataSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatPromptTest {
    @Test
    void permitsGeneralAnswersWhileGroundingCurrentStoreFacts() {
        var user = new ChatRequest.UserContext("user-1", ChatRequest.UserRole.OWNER, "actor-token");
        var data = new RetailDataSnapshot("{\"results\":[]}", List.of("current_inventory"));

        String prompt = ChatPrompt.system(user, data);

        assertTrue(prompt.contains("Answer general knowledge"));
        assertTrue(prompt.contains("do not refuse just because the retrieved retail data does not cover them"));
        assertTrue(prompt.contains("For current, store-specific facts"));
        assertTrue(prompt.contains("never make up missing business facts"));
        assertTrue(prompt.contains("For CUSTOMER_VISITS, use today's recorded sales"));
        assertTrue(prompt.contains("do not conclude that nobody visited"));
        assertTrue(prompt.contains("For CUSTOMER_DIRECTORY, report totalCustomers"));
        assertTrue(prompt.contains("include every returned customer record"));
        assertTrue(prompt.contains("salesCount and revenue are already aggregated"));
        assertTrue(prompt.contains("do not say a daily figure is unavailable"));
        assertTrue(prompt.contains("This means access was restricted; it does not mean there were no records"));
    }
}
