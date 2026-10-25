package com.retail.aiprocessor.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetailQueryPlanTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesModelSelectedCustomerDirectoryPlan() {
        RetailDataPlan plan = RetailQueryPlanParser.parse(
                "{\"queries\":[{\"operation\":\"customer_directory\",\"query\":null,\"from\":null," +
                        "\"to\":null,\"threshold\":null,\"limit\":50,\"fields\":[\"phone\"]}]}", mapper);

        assertEquals("CUSTOMER_DIRECTORY", plan.queries().get(0).operation());
        assertEquals(null, plan.queries().get(0).limit());
        assertEquals(java.util.List.of("name", "phone"), plan.queries().get(0).fields());
        assertTrue(plan.requiresRetailData());
    }

    @Test
    void customerDirectoryIncludesOnlyRequestedAllowlistedFieldsAndAlwaysIncludesName() {
        RetailQueryPlan plan = new RetailQueryPlan("CUSTOMER_DIRECTORY", null, null, null, null, 50,
                java.util.List.of("EMAIL", "drop table customers", "address"));

        assertEquals(java.util.List.of("name", "email", "address"), plan.fields());
    }

    @Test
    void parsesNoDataPlanForGeneralQuestions() {
        RetailDataPlan plan = RetailQueryPlanParser.parse("```json\n{\"queries\":[]}\n```", mapper);

        assertFalse(plan.requiresRetailData());
    }

    @Test
    void extractsPlannerJsonFromOpenRouterTextContentBlocks() throws Exception {
        var message = mapper.readTree("{\"content\":[{\"type\":\"text\",\"text\":" +
                "\"{\\\"queries\\\":[{\\\"operation\\\":\\\"SALES_SUMMARY\\\",\\\"from\\\":\\\"2026-10-24\\\",\\\"to\\\":\\\"2026-10-25\\\"}]}\"}]}");

        String content = OpenRouterChatEngine.messageContent(message);
        RetailDataPlan plan = RetailQueryPlanParser.parse(content, mapper);

        assertEquals("SALES_SUMMARY", plan.queries().get(0).operation());
    }

    @Test
    void acceptsMultipleOperationsForMultiPartQuestions() {
        RetailDataPlan plan = RetailQueryPlanParser.parse(
                "{\"queries\":[{\"operation\":\"INVENTORY_SUMMARY\"}," +
                        "{\"operation\":\"SALES_SUMMARY\"}]}", mapper);

        assertEquals(2, plan.queries().size());
        assertEquals("INVENTORY_SUMMARY", plan.queries().get(0).operation());
        assertEquals("SALES_SUMMARY", plan.queries().get(1).operation());
    }

    @Test
    void rejectsOperationsOutsideTheBackendAllowlist() {
        assertThrows(IllegalArgumentException.class, () -> RetailQueryPlanParser.parse(
                "{\"queries\":[{\"operation\":\"EXECUTE_SQL\"}]}", mapper));
    }

    @Test
    void clampsModelSuggestedLimitsAndThresholds() {
        RetailQueryPlan plan = new RetailQueryPlan("LOW_STOCK", null, null, null, 50000, 500);

        assertEquals(10000, plan.threshold());
        assertEquals(50, plan.limit());
    }

    @Test
    void appliesSafeDefaultsToPlansWithOptionalParameters() {
        RetailQueryPlan lowStock = new RetailQueryPlan("LOW_STOCK", null, null, null, null, null);
        RetailQueryPlan directory = new RetailQueryPlan("CUSTOMER_DIRECTORY", null, null, null, null, 50);

        assertEquals(5, lowStock.threshold());
        assertEquals(null, directory.limit());
    }

    @Test
    void completesSingleDateSalesRangesAsOneDayInsteadOfUsingThirtyDayDefaults() {
        java.time.LocalDate today = java.time.LocalDate.now();
        RetailQueryPlan plan = new RetailQueryPlan("SALES_SUMMARY", null, today.toString(), null, null, null);

        assertEquals(today.toString(), plan.from());
        assertEquals(today.plusDays(1).toString(), plan.to());
    }

    @Test
    void identifiesOwnerOnlyOperationsWithoutMarkingInventoryAsRestricted() {
        assertTrue(new RetailQueryPlan("SALES_SUMMARY", null, null, null, null, null).requiresOwnerAccess());
        assertFalse(new RetailQueryPlan("CURRENT_INVENTORY", null, null, null, null, null).requiresOwnerAccess());
    }

    @Test
    void plannerDistinguishesProductListsFromAggregateInventoryCounts() {
        String prompt = QueryPlanPrompt.system(new com.retail.aiprocessor.chat.ChatRequest.UserContext(
                "user-1", com.retail.aiprocessor.chat.ChatRequest.UserRole.RECEPTIONIST, "token"));

        assertTrue(prompt.contains("use for 'which/what products are available'"));
        assertTrue(prompt.contains("never when names or individual products are requested"));
        assertTrue(prompt.contains("Never use NONE merely because the user lacks access"));
    }
}
