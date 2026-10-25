package com.retail.aiprocessor.data;

import com.retail.aiprocessor.chat.ChatRequest;
import com.retail.aiprocessor.model.RetailQueryPlan;

public interface ApprovedRetailDataAccess {
    RetailDataSnapshot retrieve(RetailQueryPlan plan, ChatRequest.UserContext user);
}
