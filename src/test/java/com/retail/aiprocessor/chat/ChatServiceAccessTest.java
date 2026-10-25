package com.retail.aiprocessor.chat;

import com.retail.aiprocessor.data.ApprovedRetailDataAccess;
import com.retail.aiprocessor.model.AvailableModel;
import com.retail.aiprocessor.model.ChatEngineRouter;
import com.retail.aiprocessor.model.ModelCatalog;
import com.retail.aiprocessor.model.RetailDataPlan;
import com.retail.aiprocessor.model.RetailQueryPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceAccessTest {
    @Mock private ChatEngineRouter chatEngineRouter;
    @Mock private ModelCatalog modelCatalog;
    @Mock private ApprovedRetailDataAccess retailDataAccess;
    @Mock private ConversationHistoryService historyService;

    @Test
    void receptionistGetsAccessExplanationInsteadOfFalseNoSalesClaim() {
        String userId = UUID.randomUUID().toString();
        String conversationId = UUID.randomUUID().toString();
        var user = new ChatRequest.UserContext(userId, ChatRequest.UserRole.RECEPTIONIST, "actor-token");
        var request = new ChatRequest("today's total sales?", "groq-model", user, conversationId);
        var model = new AvailableModel("groq-model", "groq", "openai/gpt-oss-20b", "GPT OSS");
        when(modelCatalog.require("groq-model")).thenReturn(model);
        when(historyService.open(UUID.fromString(userId), conversationId, request.question()))
                .thenReturn(UUID.fromString(conversationId));
        when(historyService.context(UUID.fromString(conversationId))).thenReturn(List.of());
        when(chatEngineRouter.plan(model, request.question(), List.of(), user)).thenReturn(
                new RetailDataPlan(List.of(new RetailQueryPlan("SALES_SUMMARY", null, null, null, null, null))));

        ChatResponse response = new ChatService(chatEngineRouter, modelCatalog, retailDataAccess, historyService)
                .answer(request);

        assertTrue(response.answer().contains("only to store owners"));
        verifyNoInteractions(retailDataAccess);
        verify(chatEngineRouter, never()).answer(any(AvailableModel.class), anyString(), anyList(),
                any(ChatRequest.UserContext.class), any());
    }
}
