package com.retail.aiprocessor.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

final class RetailQueryPlanParser {
    private RetailQueryPlanParser() { }

    static RetailDataPlan parse(String text, ObjectMapper mapper) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Query planner returned no plan");
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalArgumentException("Query planner returned invalid JSON");
        try {
            JsonNode root = mapper.readTree(text.substring(start, end + 1));
            JsonNode queries = root.path("queries");
            if (!queries.isArray()) throw new IllegalArgumentException("Query planner did not return a query list");
            List<RetailQueryPlan> plans = new ArrayList<>();
            for (JsonNode query : queries) {
                if (plans.size() == 5) break;
                plans.add(mapper.treeToValue(query, RetailQueryPlan.class));
            }
            return new RetailDataPlan(plans);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Query planner returned an invalid plan", exception);
        }
    }
}
