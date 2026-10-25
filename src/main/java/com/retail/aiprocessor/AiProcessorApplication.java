package com.retail.aiprocessor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiProcessorApplication.class, args);
    }
}
