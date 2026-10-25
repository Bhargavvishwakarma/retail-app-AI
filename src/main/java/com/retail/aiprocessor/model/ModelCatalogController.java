package com.retail.aiprocessor.model;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(path = "/internal/v1/models", produces = MediaType.APPLICATION_JSON_VALUE)
public class ModelCatalogController {
    private final ModelCatalog modelCatalog;

    public ModelCatalogController(ModelCatalog modelCatalog) {
        this.modelCatalog = modelCatalog;
    }

    @GetMapping
    public List<AvailableModel> availableModels() {
        return modelCatalog.available();
    }
}
