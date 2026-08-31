package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** disabled時も専用chainを残し、internal/portal chainへfall-throughさせない。 */
@Component
@RequiredArgsConstructor
public class ExternalApiDisabledFilter extends OncePerRequestFilter {
    private final ObjectProvider<IntegrationHubExternalApiProperties> propertiesProvider;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!ExternalApiRouteCatalog.isExternalApiPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        IntegrationHubExternalApiProperties properties = propertiesProvider.getIfAvailable();
        if (properties != null && properties.getPublicApi().getEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }
        request.setAttribute(ExternalApiErrorWriter.ROUTE_ATTRIBUTE, "EXTERNAL_UNKNOWN_ROUTE");
        request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, "DISABLED_DENY");
        ExternalApiAuditTrail.mark(request, "authentication", "DISABLED_DENY");
        ExternalApiErrorWriter.write(response, objectMapper, correlationId(request), 404,
                "RESOURCE_NOT_FOUND", false, 0);
    }

    private String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(ExternalApiErrorWriter.CORRELATION_ATTRIBUTE);
        return value instanceof String id ? id : "unavailable";
    }
}
