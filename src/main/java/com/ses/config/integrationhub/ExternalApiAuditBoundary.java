package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.service.integrationhub.ExternalApiAuditRecord;
import com.ses.service.integrationhub.ExternalApiAuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

/** GETを含む公開API全判断を一request一recordへ収束させる専用監査境界。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExternalApiAuditBoundary extends OncePerRequestFilter {
    private final ObjectProvider<ExternalApiAuditService> auditServiceProvider;
    private final ObjectProvider<ExternalApiMetricsRecorder> metricsProvider;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!ExternalApiRouteCatalog.isExternalApiPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        ExternalApiAuditTrail.start(request);
        ExternalApiAuditTrail trail = ExternalApiAuditTrail.get(request);
        ContentCachingResponseWrapper bufferedResponse = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(request, bufferedResponse);
        } catch (ExternalApiSecurityException e) {
            request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, e.getDecision());
            ExternalApiAuditTrail.mark(request, "authentication", e.getDecision());
            throw e;
        } catch (RuntimeException e) {
            request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, "INTERNAL_ERROR");
            ExternalApiAuditTrail.mark(request, "authentication", "INTERNAL_ERROR");
            throw e;
        } finally {
            boolean persisted = recordRequired(request, bufferedResponse);
            if (persisted) {
                bufferedResponse.copyBodyToResponse();
            } else if (!bufferedResponse.isCommitted()) {
                bufferedResponse.resetBuffer();
                try {
                    ExternalApiErrorWriter.write(bufferedResponse, objectMapper, correlationId(request, trail), 500,
                            "INTERNAL_ERROR", false, 0);
                } catch (IOException ignored) {
                    bufferedResponse.setStatus(500);
                }
                bufferedResponse.copyBodyToResponse();
            }
        }
    }

    private boolean recordRequired(HttpServletRequest request, HttpServletResponse response) {
        ExternalApiAuditTrail trail = ExternalApiAuditTrail.get(request);
        if (trail == null) {
            return true;
        }
        Object principalAttribute = request.getAttribute(ExternalApiErrorWriter.PRINCIPAL_ATTRIBUTE);
        ExternalApiPrincipal principal = principalAttribute instanceof ExternalApiPrincipal value ? value : null;
        String route = valueOr(request.getAttribute(ExternalApiErrorWriter.ROUTE_ATTRIBUTE), trail.routeTemplate());
        ExternalApiAuditTrail.route(request, route);
        String decision = valueOr(request.getAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE),
                response.getStatus() >= 200 && response.getStatus() < 400 ? "SUCCESS" : "HTTP_REJECTED");
        int status = response.getStatus();
        String resultCode = ExternalApiErrorWriter.codeForStatus(status);
        boolean success = status >= 200 && status < 400;
        if (success) {
            resultCode = "SUCCESS";
        }
        ExternalApiAuditRecord record = new ExternalApiAuditRecord(
                "UNAUTHENTICATED",
                trail.postAuthPrincipal(),
                principal == null ? null : principal.clientId(),
                trail.credentialVersion(),
                trail.keyId(),
                correlationId(request, trail),
                safeMethod(request.getMethod()),
                trail.routeTemplate(),
                trail.decision("authentication"),
                trail.decision("scope"),
                trail.decision("dataScope"),
                trail.decision("command"),
                trail.decision("rate"),
                status,
                resultCode,
                success);
        boolean persisted = false;
        ExternalApiAuditService auditService = auditServiceProvider.getIfAvailable();
        if (auditService != null) {
            try {
                auditService.recordRequired(record);
                persisted = true;
            } catch (RuntimeException ignored) {
                log.error("公開API監査の永続化に失敗しました。公開requestをfail-closedにします");
            }
        } else {
            log.error("公開API監査serviceが存在しません。公開requestをfail-closedにします");
        }
        ExternalApiMetricsRecorder metrics = metricsProvider.getIfAvailable();
        if (metrics != null) {
            metrics.record(trail.routeTemplate(), request.getMethod(), persisted ? status : 500,
                    persisted ? decision : "INTERNAL_ERROR", principal == null ? null : principal.clientTier());
        }
        return persisted;
    }

    private String valueOr(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private String safeMethod(String method) {
        return method != null && method.matches("[A-Za-z]{1,16}") ? method.toUpperCase() : "UNKNOWN";
    }

    private String correlationId(HttpServletRequest request, ExternalApiAuditTrail trail) {
        Object value = request.getAttribute(ExternalApiErrorWriter.CORRELATION_ATTRIBUTE);
        return value instanceof String id ? id : trail.correlationId();
    }
}
