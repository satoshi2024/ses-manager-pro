package com.ses.config;

import com.ses.common.util.SecurityInfrastructureUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.common.util.CorrelationContext;
import com.ses.service.AuditLogService;
import com.ses.service.security.BreakGlassService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * API操作ログフィルター
 * /api/** への更新系リクエスト（POST/PUT/DELETE）について、
 * 実行者・HTTPメソッド・URI・レスポンスステータスをアプリケーションログに記録し、
 * t_audit_log にも永続化する（アプリケーションログはローテーションで消えるため）。
 * 参照系（GET）は原則対象外。ただしファイルdownloadは成功・拒否とも記録する。
 * 本番初回bootstrap session（{@link ProductionBootstrapMode}）の書込操作は、break-glassと同様に
 * 実行前の必須監査（BOOTSTRAP_WRITE_ATTEMPT）と結果（BOOTSTRAP_WRITE）を残し、参照（GET/HEAD）も結果
 * （BOOTSTRAP_READ。best-effort）を残す。
 *
 * AuditLogServiceはObjectProviderで任意依存にし、@WebMvcTest等の薄いテスト
 * コンテキストでBeanが無い場合でもフィルター自体は動作するようにする
 * （MenuPermissionFilter等の既存フィルターと同じ防御的な取得パターン）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiAuditFilter extends OncePerRequestFilter {

    private final ObjectProvider<AuditLogService> auditLogServiceProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        AuditLogService auditLogService = auditLogServiceProvider.getIfAvailable();
        boolean breakGlassRequest = isBreakGlassRequest(request);
        if (breakGlassRequest) {
            if (auditLogService == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
            try {
                auditLogService.recordRequired(SecurityUtils.currentUsername(), request.getMethod(),
                        request.getRequestURI(), 102, "BREAK_GLASS_ACCESS_ATTEMPT", false);
            } catch (RuntimeException e) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
        }
        // 本番初回bootstrap sessionの書込操作は、実行前に必須監査を残す（永続化できなければ実行しない）。
        boolean bootstrapWrite = !breakGlassRequest && isBootstrapWrite(request);
        if (bootstrapWrite) {
            if (auditLogService == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
            try {
                auditLogService.recordRequired(SecurityUtils.currentUsername(), request.getMethod(),
                        request.getRequestURI(), 102, "BOOTSTRAP_WRITE_ATTEMPT", false);
            } catch (RuntimeException e) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
        }
        // bootstrap sessionの参照（GET/HEAD）も、break-glassと同様に結果を残す（review P3-f。best-effort）。
        boolean bootstrapRead = !breakGlassRequest && !bootstrapWrite && isBootstrapRead(request);
        int observedStatus = HttpServletResponse.SC_OK;
        try {
            filterChain.doFilter(request, response);
            observedStatus = response.getStatus();
        } catch (AccessDeniedException e) {
            observedStatus = HttpServletResponse.SC_FORBIDDEN;
            throw e;
        } catch (AuthenticationException e) {
            observedStatus = HttpServletResponse.SC_UNAUTHORIZED;
            throw e;
        } finally {
            // bootstrapの要求は実行中にsessionが失効・破棄されても結果を残すため、開始時の判定を使う。
            if (bootstrapWrite || bootstrapRead || isAuditTarget(request)) {
                if (!Boolean.TRUE.equals(request.getAttribute(
                        com.ses.service.pwa.PwaClientMutationLedgerService.REPLAY_REQUEST_ATTRIBUTE))) {
                    String username = SecurityUtils.currentUsername();
                    String method = request.getMethod();
                    String uri = request.getRequestURI();
                    int status = response.getStatus() >= 400 ? response.getStatus() : observedStatus;
                    log.info("API操作監査: user={} method={} uri={} status={} correlationId={} invoiceId={} digitalInvoiceId={} jobId={} providerOperationId={} errorCode={} errorCategory={}",
                            username != null ? username : "-", method, uri, status,
                            context(CorrelationContext.CORRELATION_ID), context(CorrelationContext.INVOICE_ID),
                            context(CorrelationContext.DIGITAL_INVOICE_ID), context(CorrelationContext.JOB_ID),
                            context(CorrelationContext.PROVIDER_OPERATION_ID), context(CorrelationContext.ERROR_CODE),
                            context(CorrelationContext.ERROR_CATEGORY));
                    if (auditLogService != null) {
                        String applicationCode = breakGlassRequest ? "BREAK_GLASS_ACCESS"
                                : bootstrapWrite ? "BOOTSTRAP_WRITE"
                                : bootstrapRead ? "BOOTSTRAP_READ"
                                : "GET".equals(method) && isDownloadUri(uri)
                                ? (status >= 400 ? "FILE_DOWNLOAD_REJECTED" : "FILE_DOWNLOAD")
                                : "ses-manager";
                        auditLogService.record(username, method, uri, status, applicationCode,
                                status >= 200 && status < 400);
                    }
                }
            }
        }
    }

    private static boolean isDownloadUri(String uri) {
        if (uri == null) return false;
        return uri.startsWith("/api/files/")
                || uri.endsWith("/download") || uri.contains("/download/")
                || uri.matches("/api/contract-documents/\\d+/artifacts/\\w+")
                || uri.matches("/api/sales-orders/\\d+/documents/\\d+/download")
                || uri.matches("/api/sales-orders/\\d+/acknowledgement-pdf/download")
                || uri.matches("/api/acceptances/\\d+/document/download")
                || uri.matches("/api/my/change-requests/\\d+/attachment")
                || uri.matches("/api/engineer-change-requests/\\d+/attachment");
    }

    private static String context(String key) {
        String value = CorrelationContext.get(key);
        return value == null ? "-" : value;
    }

    /**
     * 記録対象か判定する（/api/** かつ 更新系メソッド）。
     * /api/payroll/** はHFP-01専用監査（PAYROLL_* / FREEE_* fixed code）がcontrollerで記録するため除外し、
     * 1 request = 1 audit rowを保つ（design §12.3）。
     */
    private boolean isAuditTarget(HttpServletRequest request) {
        if (isBreakGlassRequest(request) || isBootstrapWrite(request)) {
            return true;
        }
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/api/")) {
            return false;
        }
        if (uri.startsWith("/api/payroll")) {
            return false;
        }
        String method = request.getMethod();
        return "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method)
                || ("GET".equals(method) && isDownloadUri(uri));
    }

    /** bootstrap session（{@link ProductionBootstrapMode}）の書込操作（画面・APIとも。静的資源とERRORは除く）か。 */
    private boolean isBootstrapWrite(HttpServletRequest request) {
        if (!ProductionBootstrapMode.isBootstrapRequest(request)) {
            return false;
        }
        String method = request.getMethod();
        boolean write = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
        return write && !SecurityInfrastructureUtils.isStaticResource(request)
                && !SecurityInfrastructureUtils.isErrorDispatch(request);
    }

    /** bootstrap sessionの参照要求（GET/HEAD。画面・APIとも。静的資源とERRORは除く）か。 */
    private boolean isBootstrapRead(HttpServletRequest request) {
        if (!ProductionBootstrapMode.isBootstrapRequest(request)) {
            return false;
        }
        String method = request.getMethod();
        boolean read = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        return read && !SecurityInfrastructureUtils.isStaticResource(request)
                && !SecurityInfrastructureUtils.isErrorDispatch(request);
    }

    private boolean isBreakGlassRequest(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE) == null) {
            return false;
        }
        return !SecurityInfrastructureUtils.isStaticResource(request)
                && !SecurityInfrastructureUtils.isErrorDispatch(request);
    }
}
