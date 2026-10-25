package com.retail.aiprocessor.model;

import com.retail.aiprocessor.chat.ChatRequest;
import com.retail.aiprocessor.data.RetailDataSnapshot;
import java.util.List;

public interface ChatEngine {
    String provider();

    RetailDataPlan plan(String question, List<ChatTurn> history, ChatRequest.UserContext user, String model);

    ChatResult answer(String question, List<ChatTurn> history, ChatRequest.UserContext user,
                      RetailDataSnapshot data, String model);
}
