package com.retail.aiprocessor.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/conversations")
public class ConversationController {
    private final ConversationHistoryService history;

    public ConversationController(ConversationHistoryService history) { this.history = history; }

    @PostMapping("/list")
    public Object list(@Valid @RequestBody ConversationRequest request) {
        return history.list(UUID.fromString(request.userId()), request.page() == null ? 0 : request.page(),
                request.size() == null ? 10 : request.size());
    }

    @PostMapping("/get")
    public Object get(@Valid @RequestBody ConversationRequest request) {
        return history.get(UUID.fromString(request.userId()), request.conversationId());
    }

    public record ConversationRequest(@NotBlank @Size(max=128) String userId,
                                      @Size(max=100) String conversationId,
                                      @Min(0) Integer page,
                                      @Min(1) @Max(50) Integer size) { }
}
