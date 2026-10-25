package com.retail.aiprocessor.model;

import java.util.List;
import java.util.stream.Collectors;

public record RetailDataPlan(List<RetailQueryPlan> queries) {
    public RetailDataPlan {
        queries = queries == null ? List.of() : List.copyOf(queries.stream().limit(5).toList());
    }

    public boolean requiresRetailData() {
        return queries.stream().anyMatch(RetailQueryPlan::requiresRetailData);
    }

    public String operations() {
        return queries.stream().filter(RetailQueryPlan::requiresRetailData)
                .map(RetailQueryPlan::operation).distinct().collect(Collectors.joining(","));
    }

    public static RetailDataPlan noData() {
        return new RetailDataPlan(List.of());
    }
}
