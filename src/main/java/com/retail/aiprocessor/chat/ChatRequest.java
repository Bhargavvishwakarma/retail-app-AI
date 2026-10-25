package com.retail.aiprocessor.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank @Size(max = 4000) String question,
        @NotBlank @Size(max = 180) String modelId,
        @NotNull UserContext user,
        @Size(max = 100) String conversationId
) {
    public record UserContext(@NotBlank @Size(max = 128) String userId, @NotNull UserRole role,
                              @NotBlank @Size(max = 4096) String actorToken) {
    }

    public enum UserRole {
        OWNER,
        RECEPTIONIST
    }
}
