package com.retail.aiprocessor.model;

import com.retail.aiprocessor.chat.ChatRequest;
import com.retail.aiprocessor.data.RetailDataSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ChatEngineRouter {
    private final Map<String, ChatEngine> engines;

    public ChatEngineRouter(List<ChatEngine> engines) {
        this.engines = engines.stream().collect(Collectors.toUnmodifiableMap(ChatEngine::provider, Function.identity()));
    }

    public ChatResult answer(AvailableModel model, String question, List<ChatTurn> history,
                             ChatRequest.UserContext user, RetailDataSnapshot data) {
        ChatEngine engine = engines.get(model.provider());
        if (engine == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Selected model provider is not configured");
        }
        return engine.answer(question, history, user, data, model.model());
    }

    public RetailDataPlan plan(AvailableModel model, String question, List<ChatTurn> history,
                               ChatRequest.UserContext user) {
        ChatEngine engine = engines.get(model.provider());
        if (engine == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Selected model provider is not configured");
        }
        return engine.plan(question, history, user, model.model());
    }
}
