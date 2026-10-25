package com.retail.aiprocessor.model;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Component
public class ModelCatalog {
    private final JdbcTemplate jdbc;

    public ModelCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AvailableModel> available() {
        return jdbc.query("select id,provider,model,display_name from ai_processor.ai_models " +
                        "where enabled=true order by sort_order,display_name",
                (rs, row) -> new AvailableModel(rs.getString("id"), rs.getString("provider"),
                        rs.getString("model"), rs.getString("display_name")));
    }

    public AvailableModel require(String id) {
        return available().stream().filter(model -> model.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Requested model is not available"));
    }
}
