package com.retail.aiprocessor.model;

public record ChatTurn(String role, String content, String thoughtSignature) {
    public ChatTurn(String role, String content) { this(role, content, null); }
}
