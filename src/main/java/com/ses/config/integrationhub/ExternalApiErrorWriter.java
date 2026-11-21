package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 公開APIのallow-list error body。内部ApiResult、stack、SQLを再利用しない。 */
public final class ExternalApiErrorWriter {
    public static final String CORRELATION_ATTRIBUTE = "external.correlation-id";
    public static final String DECISION_ATTRIBUTE = "external.decision";
    public static final String ROUTE_ATTRIBUTE = "external.route-template";
    public static final String PRINCIPAL_ATTRIBUTE = "external.principal";

    private ExternalApiErrorWriter() {
    }

    /**
     * 公開APIのcorrelation IDを新しく作り、request属性 {@link #CORRELATION_ATTRIBUTE} と応答header {@code X-Correlation-ID} へ
     * 付ける。{@link ExternalApiCorrelationFilter} と、それより前で503を返すbackupの書込み静止のfilter（RB-QUIESCE D-P3-8）が
     * 同じ作り方を使う。
     */
    public static String assignCorrelationId(HttpServletRequest request, HttpServletResponse response) {
        String correlationId = UUID.randomUUID().toString();
        request.setAttribute(CORRELATION_ATTRIBUTE, correlationId);
        response.setHeader("X-Correlation-ID", correlationId);
        return correlationId;
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
            case 409 -> "INBOUND_PAYLOAD_CONFLICT";
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
            case "INBOUND_PAYLOAD_CONFLICT" -> "Inbound payload conflict";
            case "REQUEST_INVALID", "CURSOR_INVALID" -> "Invalid request";
            case "BACKUP_QUIESCED" -> "Temporarily unavailable during backup";
            default -> "Request failed";
        };
    }
}
