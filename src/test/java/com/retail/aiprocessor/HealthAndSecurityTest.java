package com.retail.aiprocessor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/health-test",
        "spring.datasource.username=health-test-user",
        "spring.datasource.password=health-test-password",
        "management.health.db.enabled=false"
})
@AutoConfigureMockMvc
@Import(HealthAndSecurityTest.DatabaseHealthTestConfiguration.class)
class HealthAndSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointIsPublicAndReportsUpWithDatabase() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void healthEndpointHidesSensitiveDetailsAndCredentials() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.details").doesNotExist())
                .andExpect(jsonPath("$.components.db.details.database").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertFalse(
                        result.getResponse().getContentAsString().contains("health-test-password")))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertFalse(
                        result.getResponse().getContentAsString().contains("127.0.0.1")))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertFalse(
                        result.getResponse().getContentAsString().contains("health-test-user")));
    }

    @Test
    void onlyHealthIsPublicAndOtherRoutesStayProtected() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/internal/v1/chat")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/some-business-route"))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthProbePathsRemainPublic() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @TestConfiguration
    static class DatabaseHealthTestConfiguration {
        @Bean
        HealthIndicator db() {
            return () -> Health.up()
                    .withDetail("database", "jdbc:postgresql://127.0.0.1:1/private")
                    .withDetail("username", "health-test-user")
                    .withDetail("password", "health-test-password")
                    .build();
        }
    }
}
