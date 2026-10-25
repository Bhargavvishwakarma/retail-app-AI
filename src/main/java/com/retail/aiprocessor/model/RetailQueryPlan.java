package com.retail.aiprocessor.model;

import java.util.Set;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.time.LocalDate;

public record RetailQueryPlan(String operation, String query, String from, String to,
                              Integer threshold, Integer limit, List<String> fields) {
    private static final Set<String> ALLOWED_OPERATIONS = Set.of(
            "NONE", "PRODUCT_SEARCH", "CURRENT_INVENTORY", "INVENTORY_SUMMARY", "LOW_STOCK",
            "SALES_SUMMARY", "BEST_SELLERS", "RECENT_SALES", "CUSTOMER_VISITS",
            "CATEGORY_SUMMARY", "CUSTOMER_SUMMARY", "CUSTOMER_DIRECTORY");
    private static final Set<String> CUSTOMER_FIELDS = Set.of("name", "phone", "email", "address");

    public RetailQueryPlan(String operation, String query, String from, String to,
                           Integer threshold, Integer limit) {
        this(operation, query, from, to, threshold, limit, null);
    }

    public RetailQueryPlan {
        operation = operation == null ? "NONE" : operation.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException("Unsupported retail query operation");
        }
        if (limit != null) limit = Math.max(1, Math.min(limit, 50));
        if (threshold != null) threshold = Math.max(0, Math.min(threshold, 10000));
        LocalDate today = LocalDate.now();
        if ("LOW_STOCK".equals(operation) && threshold == null) threshold = 5;
        if ("CUSTOMER_DIRECTORY".equals(operation)) limit = null;
        if ("CUSTOMER_DIRECTORY".equals(operation)) {
            var selected = new LinkedHashSet<String>();
            selected.add("name");
            if (fields != null) fields.stream().filter(java.util.Objects::nonNull)
                    .map(field -> field.trim().toLowerCase(Locale.ROOT))
                    .filter(CUSTOMER_FIELDS::contains).forEach(selected::add);
            fields = List.copyOf(selected);
        } else {
            fields = List.of();
        }
        if (Set.of("SALES_SUMMARY", "BEST_SELLERS").contains(operation)) {
            if (from == null && to == null) {
                from = today.minusDays(30).toString();
                to = today.plusDays(1).toString();
            } else if (from == null) {
                from = LocalDate.parse(to).minusDays(1).toString();
            } else if (to == null) {
                to = LocalDate.parse(from).plusDays(1).toString();
            }
        }
        if ("CUSTOMER_VISITS".equals(operation)) {
            if (from == null) from = today.toString();
            if (to == null) to = today.plusDays(1).toString();
        }
    }

    public boolean requiresRetailData() {
        return !"NONE".equals(operation);
    }

    public boolean requiresOwnerAccess() {
        return Set.of("LOW_STOCK", "SALES_SUMMARY", "BEST_SELLERS", "RECENT_SALES", "CUSTOMER_VISITS")
                .contains(operation);
    }

    public static RetailQueryPlan noData() {
        return new RetailQueryPlan("NONE", null, null, null, null, null, List.of());
    }
}
