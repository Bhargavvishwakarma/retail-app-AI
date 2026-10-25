package com.retail.aiprocessor.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException exception,
                                                              HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        String message = exception.getReason();
        if (message == null || message.isBlank()) message = status.getReasonPhrase();
        log.warn("api_request_failed status={} code={} path={} requestId={} message={}", status.value(),
                codeFor(status), request.getRequestURI(), requestId(), message);
        return response(status, codeFor(status), message, request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception,
                                                          HttpServletRequest request) {
        List<ApiViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toViolation).toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.",
                request, violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException exception,
                                                                   HttpServletRequest request) {
        List<ApiViolation> violations = exception.getConstraintViolations().stream()
                .map(violation -> new ApiViolation(violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.",
                request, violations);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException exception,
                                                              HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is missing or malformed.",
                request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("api_unexpected_error method={} path={} requestId={}", request.getMethod(),
                request.getRequestURI(), requestId(), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Contact support with the requestId.", request, List.of());
    }

    private ApiViolation toViolation(FieldError error) {
        return new ApiViolation(error.getField(), error.getDefaultMessage() == null
                ? "Invalid value." : error.getDefaultMessage());
    }

    private ResponseEntity<ProblemDetail> response(HttpStatus status, String code, String message,
                                                   HttpServletRequest request, List<ApiViolation> violations) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(java.net.URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("requestId", requestId());
        if (!violations.isEmpty()) problem.setProperty("violations", violations);
        return ResponseEntity.status(status).body(problem);
    }

    private String requestId() {
        return MDC.get(RequestCorrelationFilter.MDC_KEY);
    }

    private String codeFor(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "BAD_REQUEST";
            case UNAUTHORIZED -> "UNAUTHENTICATED";
            case FORBIDDEN -> "FORBIDDEN";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "CONFLICT";
            case GATEWAY_TIMEOUT -> "UPSTREAM_TIMEOUT";
            case SERVICE_UNAVAILABLE -> "UPSTREAM_UNAVAILABLE";
            default -> status.is4xxClientError() ? "CLIENT_ERROR" : "REQUEST_FAILED";
        };
    }

    public record ApiViolation(String field, String message) { }
}
