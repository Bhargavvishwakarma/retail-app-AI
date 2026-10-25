package com.retail.aiprocessor.config;

import com.retail.aiprocessor.data.ApprovedRetailDataAccess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Configuration
public class AdapterConfiguration {
    @Bean
    @ConditionalOnMissingBean
    ApprovedRetailDataAccess unavailableRetailDataAccess() {
        return (question, user) -> {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Approved retail data access is not configured");
        };
    }
}
