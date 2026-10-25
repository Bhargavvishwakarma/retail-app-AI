package com.retail.aiprocessor.model;

public record ChatResult(String answer, String thoughtSignature) {
    public ChatResult(String answer) { this(answer, null); }
}
