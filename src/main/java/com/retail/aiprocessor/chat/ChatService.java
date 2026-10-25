package com.retail.aiprocessor.chat;

import com.retail.aiprocessor.data.ApprovedRetailDataAccess;
import com.retail.aiprocessor.data.RetailDataSnapshot;
import com.retail.aiprocessor.model.ChatResult;
import com.retail.aiprocessor.model.ChatEngineRouter;
import com.retail.aiprocessor.model.ModelCatalog;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;


@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final Pattern GREETING = Pattern.compile(
            "^(?:hi+|hello+|hey|how are you(?: doing)?|good morning|good afternoon|good evening)(?: how are you(?: doing)?)?$");
    private final ChatEngineRouter chatEngineRouter;
    private final ModelCatalog modelCatalog;
    private final ApprovedRetailDataAccess retailDataAccess;
    private final ConversationHistoryService historyService;

    public ChatService(ChatEngineRouter chatEngineRouter, ModelCatalog modelCatalog,
                       ApprovedRetailDataAccess retailDataAccess, ConversationHistoryService historyService) {
        this.chatEngineRouter = chatEngineRouter;
        this.modelCatalog = modelCatalog;
        this.retailDataAccess = retailDataAccess;
        this.historyService = historyService;
    }

    public ChatResponse answer(ChatRequest request) {
        var selectedModel = modelCatalog.require(request.modelId());
        long startedAt = System.nanoTime();
        log.info("chat_started modelId={} provider={} userId={} role={} conversationId={} questionChars={}",
                selectedModel.id(), selectedModel.provider(), request.user().userId(), request.user().role(),
                request.conversationId(), request.question().length());
        if (log.isDebugEnabled()) log.debug(
                "chat_request_payload question={} modelId={} conversationId={} userId={} role={} actorToken=[REDACTED]",
                request.question(), request.modelId(), request.conversationId(), request.user().userId(), request.user().role());
        try {
            UUID userId = UUID.fromString(request.user().userId());
            UUID conversationId = historyService.open(userId, request.conversationId(), request.question());
            var history = historyService.context(conversationId);
            RetailDataSnapshot data;
            ChatResult result;
            if (isGreeting(request.question())) {
                data = new RetailDataSnapshot("{}", List.of());
                result = new ChatResult("Hello! I'm here to help with your store. What would you like to know?");
            } else {
                var queryPlan = chatEngineRouter.plan(selectedModel, request.question(), history, request.user());
                log.info("retail_query_plan modelId={} operations={} queryCount={}", selectedModel.id(),
                        queryPlan.operations(), queryPlan.queries().size());
                var contexts = new ArrayList<String>();
                var dataSources = new LinkedHashSet<String>();
                var dataQueries = queryPlan.queries().stream()
                        .filter(query -> query.requiresRetailData()).toList();
                var restrictedQueries = dataQueries.stream()
                        .filter(query -> query.requiresOwnerAccess())
                        .filter(query -> request.user().role() != ChatRequest.UserRole.OWNER)
                        .toList();
                boolean allQueriesRestricted = !dataQueries.isEmpty() && restrictedQueries.size() == dataQueries.size();
                if (allQueriesRestricted) {
                    data = new RetailDataSnapshot("{}", List.of());
                    result = new ChatResult("This report is available only to store owners. Please sign in " +
                            "with an owner account to view it.");
                } else {
                    for (var query : dataQueries) {
                        if (restrictedQueries.contains(query)) {
                            contexts.add("{\"operation\":\"" + query.operation() + "\",\"access\":\"OWNER_ONLY\"}");
                            continue;
                        }
                        var snapshot = retailDataAccess.retrieve(query, request.user());
                        contexts.add(snapshot.context());
                        dataSources.addAll(snapshot.dataSources());
                    }
                    String combinedContext = contexts.isEmpty() ? "{}" : "{\"queries\":[" +
                            String.join(",", contexts) + "]}";
                    data = new RetailDataSnapshot(combinedContext, List.copyOf(dataSources));
                    result = chatEngineRouter.answer(selectedModel, request.question(), history, request.user(), data);
                }
            }
            historyService.append(conversationId, request.question(), result.answer(), result.thoughtSignature(), data.dataSources());
            var response = new ChatResponse(result.answer(), conversationId.toString(), selectedModel.id(), data.dataSources());
            log.info("chat_completed modelId={} dataSources={} answerChars={} durationMs={}", selectedModel.id(),
                    data.dataSources(), result.answer() == null ? 0 : result.answer().length(),
                    (System.nanoTime() - startedAt) / 1_000_000);
            return response;
        } catch (RuntimeException exception) {
            if (exception instanceof ResponseStatusException responseException) {
                log.warn("chat_failed modelId={} provider={} errorType={} status={} reason={} durationMs={}",
                        selectedModel.id(), selectedModel.provider(), exception.getClass().getSimpleName(),
                        responseException.getStatusCode().value(), responseException.getReason(),
                        (System.nanoTime() - startedAt) / 1_000_000);
            } else {
                log.warn("chat_failed modelId={} provider={} errorType={} durationMs={}", selectedModel.id(),
                        selectedModel.provider(), exception.getClass().getSimpleName(),
                        (System.nanoTime() - startedAt) / 1_000_000);
            }
            throw exception;
        }
    }

    private boolean isGreeting(String question) {
        String normalized = question.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z ]", " ").trim().replaceAll("\\s+", " ");
        return GREETING.matcher(normalized).matches();
    }
}
