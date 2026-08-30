package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 公開APIのallow-list error body。内部ApiResult、stack、SQLを再利用しない。 */
public final class ExternalApiErrorWriter {
    public static final String CORRELATION_ATTRIBUTE = "external.correlation-id";
    public static final String DECISION_ATTRIBUTE = "external.decision";
    public static final String ROUTE_ATTRIBUTE = "external.route-template";
    public static final String PRINCIPAL_ATTRIBUTE = "external.principal";

    private ExternalApiErrorWriter() {
    }

    public static void write(HttpServletResponse response, ObjectMapper objectMapper,
                             String correlationId, int status, String code, boolean retryable,
                             int retryAfterSeconds) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (retryAfterSeconds > 0) {
            response.setHeader("Retry-After", Integer.toString(retryAfterSeconds));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", publicMessage(code));
        body.put("correlationId", correlationId);
        body.put("retryable", retryable);
        objectMapper.writeValue(response.getWriter(), body);
        response.flushBuffer();
    }

    public static void writeException(HttpServletResponse response, ObjectMapper objectMapper,
                                      String correlationId, ExternalApiSecurityException exception)
            throws IOException {
        write(response, objectMapper, correlationId, exception.getStatus().value(), exception.getCode(),
                exception.isRetryable(), 0);
    }

    public static String codeForStatus(int status) {
        return switch (status) {
            case 400 -> "REQUEST_INVALID";
            case 401 -> "AUTHENTICATION_FAILED";
            case 403 -> "FORBIDDEN_SCOPE";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 429 -> "RATE_LIMITED";
            default -> "INTERNAL_ERROR";
        };
    }

    public static String publicMessage(String code) {
        return switch (code) {
            case "AUTHENTICATION_FAILED" -> "Authentication failed";
            case "FORBIDDEN_SCOPE" -> "Forbidden";
            case "RESOURCE_NOT_FOUND" -> "Resource not found";
            case "RATE_LIMITED" -> "Rate limit exceeded";
            case "REQUEST_INVALID", "CURSOR_INVALID" -> "Invalid request";
            default -> "Request failed";
        };
    }
}
