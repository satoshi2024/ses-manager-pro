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

/**
 * GETを含む公開API全判断を一request一recordへ収束させる専用監査境界。
 *
 * <p>永続化の範囲（N5-P2-1）:
 * <ul>
 *   <li>public-api無効時は監査tableへ一切書かず、metric（requests_totalのDISABLEDと
 *       audit_suppressed_totalのPUBLIC_API_DISABLED）だけを残す。保存期限の無いtableを外部から増やさない。</li>
 *   <li>有効時の認証済みrequestと5xxは従来どおり一request一rowで、永続化失敗はfail-closed 500。</li>
 *   <li>有効時の未認証reject（principal無し・5xx以外）は {@link ExternalApiUnauthenticatedAuditBudget} の
 *       上限内だけ永続化し、超過分はaudit_suppressed_totalのUNAUTHENTICATED_BUDGETへ集約する。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExternalApiAuditBoundary extends OncePerRequestFilter {
    private final ObjectProvider<ExternalApiAuditService> auditServiceProvider;
    private final ObjectProvider<ExternalApiMetricsRecorder> metricsProvider;
    private final ObjectProvider<IntegrationHubExternalApiProperties> propertiesProvider;
    private final ObjectProvider<ExternalApiUnauthenticatedAuditBudget> unauthenticatedBudgetProvider;
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
        ExternalApiMetricsRecorder metrics = metricsProvider.getIfAvailable();
        if (!publicApiEnabled()) {
            // 無効時はDBへ書かない。D-1（LB遮断）が効いていればこのcounterは増えない。
            recordWithoutPersistence(metrics, ExternalApiMetricsRecorder.AuditSuppressionReason.PUBLIC_API_DISABLED,
                    trail, request, status, decision, principal);
            return true;
        }
        if (principal == null && status < 500 && !unauthenticatedBudgetAvailable()) {
            recordWithoutPersistence(metrics, ExternalApiMetricsRecorder.AuditSuppressionReason.UNAUTHENTICATED_BUDGET,
                    trail, request, status, decision, null);
            return true;
        }
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
        if (metrics != null) {
            metrics.record(trail.routeTemplate(), request.getMethod(), persisted ? status : 500,
                    persisted ? decision : "INTERNAL_ERROR", principal == null ? null : principal.clientTier());
        }
        return persisted;
    }

    private void recordWithoutPersistence(ExternalApiMetricsRecorder metrics,
                                          ExternalApiMetricsRecorder.AuditSuppressionReason reason,
                                          ExternalApiAuditTrail trail, HttpServletRequest request, int status,
                                          String decision, ExternalApiPrincipal principal) {
        if (metrics == null) {
            return;
        }
        metrics.recordAuditSuppressed(reason);
        metrics.record(trail.routeTemplate(), request.getMethod(), status, decision,
                principal == null ? null : principal.clientTier());
    }

    /** properties未取得は無効扱い。ExternalApiDisabledFilterと同じ判定でdeny-only chainと揃える。 */
    private boolean publicApiEnabled() {
        IntegrationHubExternalApiProperties properties = propertiesProvider.getIfAvailable();
        return properties != null && properties.getPublicApi() != null
                && Boolean.TRUE.equals(properties.getPublicApi().getEnabled());
    }

    /** budget beanが無いslice等では従来どおり永続化する（監査の欠落側へ倒さない）。 */
    private boolean unauthenticatedBudgetAvailable() {
        ExternalApiUnauthenticatedAuditBudget budget = unauthenticatedBudgetProvider.getIfAvailable();
        return budget == null || budget.tryAcquire();
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
