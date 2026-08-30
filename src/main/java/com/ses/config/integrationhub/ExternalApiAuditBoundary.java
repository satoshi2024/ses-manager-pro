package com.ses.config.integrationhub;

import com.ses.service.AuditLogService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** GETを含む公開API全判断を一request一recordへ収束させる専用監査境界。 */
@Component
@RequiredArgsConstructor
public class ExternalApiAuditBoundary extends OncePerRequestFilter {
    private final ObjectProvider<AuditLogService> auditLogServiceProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!ExternalApiRouteCatalog.isExternalApiPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            filterChain.doFilter(request, response);
        } catch (ExternalApiSecurityException e) {
            request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, e.getDecision());
            throw e;
        } catch (RuntimeException e) {
            request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, "INTERNAL_ERROR");
            throw e;
        } finally {
            record(request, response);
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response) {
        String principal = "UNAUTHENTICATED";
        Object principalAttribute = request.getAttribute(ExternalApiErrorWriter.PRINCIPAL_ATTRIBUTE);
        if (principalAttribute instanceof ExternalApiPrincipal externalPrincipal) {
            principal = externalPrincipal.clientId();
        }
        String route = valueOr(request.getAttribute(ExternalApiErrorWriter.ROUTE_ATTRIBUTE),
                "EXTERNAL_UNKNOWN_ROUTE");
        String decision = valueOr(request.getAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE),
                response.getStatus() >= 200 && response.getStatus() < 400 ? "SUCCESS" : "HTTP_REJECTED");
        String applicationCode = "NF05_EXT_" + boundedCode(decision);
        AuditLogService auditLogService = auditLogServiceProvider.getIfAvailable();
        if (auditLogService != null) {
            auditLogService.record(principal, safeMethod(request.getMethod()), route, response.getStatus(),
                    applicationCode, response.getStatus() >= 200 && response.getStatus() < 400);
        }
    }

    private String valueOr(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private String safeMethod(String method) {
        return method != null && method.matches("[A-Za-z]{1,16}") ? method.toUpperCase() : "UNKNOWN";
    }

    private String boundedCode(String decision) {
        String safe = decision == null ? "UNKNOWN" : decision.replaceAll("[^A-Za-z0-9_~-]", "_");
        if (safe.isBlank()) {
            return "UNKNOWN";
        }
        return safe.length() <= 50 ? safe : safe.substring(0, 50);
    }
}
