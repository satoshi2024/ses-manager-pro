package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.integrationhub.ApiClientScope;
import com.ses.service.integrationhub.ApiClientScopeService;
import com.ses.service.integrationhub.ApiUsageBucketService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** client scope × data scope × command permission × quotaを認証後に適用する。 */
@Component
@RequiredArgsConstructor
public class ExternalApiAuthorizationFilter extends OncePerRequestFilter {
    private final ObjectProvider<ApiClientScopeService> scopeServiceProvider;
    private final ObjectProvider<ApiUsageBucketService> usageBucketServiceProvider;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!ExternalApiRouteCatalog.isExternalApiPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            ExternalApiPrincipal principal = principal();
            ExternalApiCanonicalRequest.Parsed parsed = parsedRequest(request);
            ExternalApiRouteCatalog.Route route = ExternalApiRouteCatalog.resolve(request.getMethod(),
                    parsed.canonicalPath());
            if (route == null) {
                throw ExternalApiSecurityException.notFound("UNKNOWN_ROUTE_OR_METHOD");
            }
            request.setAttribute(ExternalApiErrorWriter.ROUTE_ATTRIBUTE, route.template());
            ApiClientScopeService scopeService = required(scopeServiceProvider);
            ApiUsageBucketService usageBucketService = required(usageBucketServiceProvider);
            ApiClientScope scope = scopeService.getActive(principal.clientDatabaseId(), route.scopeCode(),
                    route.operationCode());
            if (scope == null || !StringUtils.hasText(scope.getDataScopeJson())
                    || !StringUtils.hasText(principal.dataScopeJson())) {
                throw ExternalApiSecurityException.forbidden("SCOPE_OR_DATA_SCOPE_DENIED");
            }
            request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, "SCOPE_ALLOWED");
            ApiUsageBucketService.RateDecision rate = usageBucketService.consume(principal.clientId(),
                    route.scopeCode(), principal.tenantId(), route.template());
            if (!rate.allowed()) {
                request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, "RATE_LIMITED");
                ExternalApiErrorWriter.write(response, objectMapper, correlationId(request),
                        HttpStatus.TOO_MANY_REQUESTS.value(), "RATE_LIMITED", true,
                        rate.retryAfterSeconds());
                return;
            }
            request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, "AUTHORIZED");
            filterChain.doFilter(request, response);
        } catch (ExternalApiSecurityException e) {
            request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, e.getDecision());
            ExternalApiErrorWriter.writeException(response, objectMapper, correlationId(request), e);
        } catch (RuntimeException e) {
            ExternalApiSecurityException failure = new ExternalApiSecurityException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "INTERNAL_ERROR");
            request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, failure.getDecision());
            ExternalApiErrorWriter.writeException(response, objectMapper, correlationId(request), failure);
        }
    }

    private ExternalApiPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof ExternalApiAuthenticationToken token)) {
            throw ExternalApiSecurityException.authentication("EXTERNAL_PRINCIPAL_MISSING");
        }
        return token.getPrincipal();
    }

    private ExternalApiCanonicalRequest.Parsed parsedRequest(HttpServletRequest request) {
        Object value = request.getAttribute(ExternalApiCanonicalRequest.class.getName());
        if (!(value instanceof ExternalApiCanonicalRequest.Parsed parsed)) {
            throw ExternalApiSecurityException.invalid("REQUEST_TARGET_INVALID");
        }
        return parsed;
    }

    private String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(ExternalApiErrorWriter.CORRELATION_ATTRIBUTE);
        return value instanceof String id ? id : "unavailable";
    }

    private <T> T required(ObjectProvider<T> provider) {
        T service = provider.getIfAvailable();
        if (service == null) {
            throw new ExternalApiSecurityException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR", "INTERNAL_ERROR");
        }
        return service;
    }
}
