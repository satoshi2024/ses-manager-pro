package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Enumeration;
import java.util.UUID;

/** 公開APIのresponse correlation IDを全成功/error/rejectへ付与する。 */
@Component
@RequiredArgsConstructor
public class ExternalApiCorrelationFilter extends OncePerRequestFilter {
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!ExternalApiRouteCatalog.isExternalApiPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            String correlationId = UUID.randomUUID().toString();
            request.setAttribute(ExternalApiErrorWriter.CORRELATION_ATTRIBUTE, correlationId);
            response.setHeader("X-Correlation-ID", correlationId);
            String supplied = singleHeader(request, "X-Correlation-ID");
            if (supplied != null && (supplied.length() < 16 || supplied.length() > 128
                    || !supplied.matches("[A-Za-z0-9._~-]+"))) {
                throw ExternalApiSecurityException.invalid("CORRELATION_ID_INVALID");
            }
            if (supplied != null) {
                correlationId = supplied;
                request.setAttribute(ExternalApiErrorWriter.CORRELATION_ATTRIBUTE, correlationId);
                response.setHeader("X-Correlation-ID", correlationId);
            }
            filterChain.doFilter(request, response);
        } catch (ExternalApiSecurityException e) {
            request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, e.getDecision());
            ExternalApiErrorWriter.writeException(response, objectMapper,
                    correlationId(request), e);
        }
    }

    private String singleHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String value = values.nextElement();
        if (values.hasMoreElements()) {
            throw ExternalApiSecurityException.invalid("CORRELATION_ID_INVALID");
        }
        return value;
    }

    private String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(ExternalApiErrorWriter.CORRELATION_ATTRIBUTE);
        return value instanceof String id ? id : "unavailable";
    }
}
