package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.service.AuditLogService;
import com.ses.service.FreeeIntegrationService;
import com.ses.service.accounting.FreeeAccountingConnectService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * freee OAuth認可・callback・接続解除。
 *
 * <p>HFP-01-003: stateはSecureRandom 24byte、発行時刻付きでsessionへ保存し、callbackで
 * 一定時間内（10分）かつ一回だけ受理する。state欠落・不一致・再送・freee側の認可拒否では
 * token交換しない。redirect先へcode/state/provider messageを載せない。</p>
 *
 * <p>HFP-01-007: OAuth応答もno-store。接続/解除はFREEE_CONNECT/FREEE_DISCONNECTで監査する
 * （成否のみ。company ID・token・codeは記録しない）。</p>
 *
 * <p>接続解除（DELETE）はJSON（ApiResult）を返す。302 opaque redirectでは成否をUIが判定できないため。</p>
 *
 * <p>freeeアプリに登録できるredirect URIは1つなので、会計接続（m_integration_connection）の認可も
 * 同じcallbackで受ける。{@code /authorize?target=accounting&connectionId=N} で開始した場合だけ、
 * 認可対象をstateと同じsessionへ保存し、callbackで会計接続へ保存する（FREEE_ACCOUNTING_CONNECTで監査）。
 * 失敗時のredirectには内部のmessage key（{@code error.payroll.*} / {@code error.accounting.*}）だけを
 * reasonとして載せ、画面で翻訳表示する。provider応答本文は載せない。</p>
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('管理者')")
@RequestMapping("/integrations/freee")
public class FreeeOAuthController {

    public static final String SESSION_STATE = "freee_oauth_state";
    public static final String SESSION_STATE_ISSUED = "freee_oauth_state_issued";
    /** 認可対象（会計接続のときだけ "accounting"）。 */
    public static final String SESSION_TARGET = "freee_oauth_target";
    /** 会計接続の認可対象connection ID。 */
    public static final String SESSION_CONNECTION_ID = "freee_oauth_connection_id";
    /** state有効期限（分）。 */
    public static final long STATE_TTL_SECONDS = 10 * 60L;

    public static final String TARGET_ACCOUNTING = "accounting";
    static final String ACCOUNTING_PAGE = "/accounting/integration";
    private static final String PAYROLL_PAGE = "/payroll";

    private static final String CODE_CONNECT = "FREEE_CONNECT";
    private static final String CODE_ACCOUNTING_CONNECT = "FREEE_ACCOUNTING_CONNECT";
    private static final String CODE_DISCONNECT = "FREEE_DISCONNECT";
    private static final String URI_CONNECT = "/integrations/freee";
    private static final String URI_DISCONNECT = "/integrations/freee";
    /** redirectへ載せてよい失敗理由（自システムのmessage keyのみ）。 */
    private static final Pattern REASON_KEY = Pattern.compile("error\\.(payroll|accounting)\\.[A-Za-z]{1,60}");

    private final FreeeIntegrationService service;
    private final FreeeAccountingConnectService accountingConnectService;
    private final AuditLogService auditLogService;

    @GetMapping("/authorize")
    public RedirectView authorize(
            @RequestParam(required = false) String target,
            @RequestParam(required = false) Long connectionId,
            HttpSession session,
            HttpServletResponse response) {
        noStore(response);
        boolean accounting = TARGET_ACCOUNTING.equals(target);
        if (accounting) {
            try {
                accountingConnectService.requireConnectable(connectionId);
            } catch (BusinessException e) {
                return failure(true, "connection", e);
            }
        }
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        session.setAttribute(SESSION_STATE, state);
        session.setAttribute(SESSION_STATE_ISSUED, Instant.now().getEpochSecond());
        if (accounting) {
            session.setAttribute(SESSION_TARGET, TARGET_ACCOUNTING);
            session.setAttribute(SESSION_CONNECTION_ID, connectionId);
        } else {
            session.removeAttribute(SESSION_TARGET);
            session.removeAttribute(SESSION_CONNECTION_ID);
        }
        try {
            return new RedirectView(service.authorizationUrl(state));
        } catch (BusinessException e) {
            return accounting ? failure(true, "config", e) : new RedirectView(PAYROLL_PAGE + "?error=config");
        }
    }

    @GetMapping("/callback")
    public RedirectView callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpSession session,
            HttpServletResponse response,
            Authentication auth) {

        noStore(response);
        Object expected = session.getAttribute(SESSION_STATE);
        Object issuedObj = session.getAttribute(SESSION_STATE_ISSUED);
        Object targetObj = session.getAttribute(SESSION_TARGET);
        Object connectionObj = session.getAttribute(SESSION_CONNECTION_ID);
        session.removeAttribute(SESSION_STATE);
        session.removeAttribute(SESSION_STATE_ISSUED);
        session.removeAttribute(SESSION_TARGET);
        session.removeAttribute(SESSION_CONNECTION_ID);
        boolean accounting = TARGET_ACCOUNTING.equals(targetObj);

        if (error != null) {
            return failure(accounting, "denied", null);
        }
        if (expected == null || !constantTimeEquals(expected.toString(), state)) {
            return failure(accounting, "state", null);
        }
        if (issuedObj == null || issuedAtExpired(issuedObj)) {
            return failure(accounting, "state", null);
        }
        if (code == null || code.isBlank()) {
            return failure(accounting, "oauth", null);
        }

        Long uid = null;
        if (auth != null && auth.getPrincipal() instanceof com.ses.config.LoginUser u) {
            uid = u.getSysUser().getId();
        }

        String auditCode = accounting ? CODE_ACCOUNTING_CONNECT : CODE_CONNECT;
        try {
            if (accounting) {
                Long connectionId = connectionObj instanceof Long l ? l : null;
                accountingConnectService.completeAuthorization(connectionId, code, uid);
            } else {
                service.handleCallback(code, state, uid);
            }
        } catch (BusinessException e) {
            auditLogService.record(SecurityUtils.currentUsername(), "GET", URI_CONNECT, 400,
                    auditCode, false);
            return failure(accounting, "oauth", e);
        } catch (Exception e) {
            auditLogService.record(SecurityUtils.currentUsername(), "GET", URI_CONNECT, 500,
                    auditCode, false);
            throw e;
        }
        auditLogService.recordRequired(SecurityUtils.currentUsername(), "GET", URI_CONNECT, 302,
                auditCode, true);
        return new RedirectView(accounting ? ACCOUNTING_PAGE + "?freee_connected=1" : PAYROLL_PAGE + "?connected=1");
    }

    /**
     * 接続解除。成功/失敗をJSONで返す（opaque 302を成功扱いしない）。
     */
    @DeleteMapping
    @ResponseBody
    public ResponseEntity<ApiResult<Boolean>> disconnect(HttpServletResponse response) {
        noStore(response);
        try {
            service.disconnect();
            auditLogService.recordRequired(SecurityUtils.currentUsername(), "DELETE", URI_DISCONNECT, 200,
                    CODE_DISCONNECT, true);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header("Pragma", "no-cache")
                    .header("Expires", "0")
                    .body(ApiResult.success(true));
        } catch (BusinessException e) {
            auditLogService.record(SecurityUtils.currentUsername(), "DELETE", URI_DISCONNECT,
                    e.getCode() == 0 ? 400 : e.getCode(), CODE_DISCONNECT, false);
            throw e;
        } catch (Exception e) {
            auditLogService.record(SecurityUtils.currentUsername(), "DELETE", URI_DISCONNECT,
                    500, CODE_DISCONNECT, false);
            throw e;
        }
    }

    /**
     * 失敗redirect。給与は従来どおり {@code /payroll?error=<kind>}、会計は
     * {@code /accounting/integration?freee_error=<kind>}。BusinessExceptionのmessage keyが
     * 自システムのkeyに一致するときだけ {@code &reason=<key>} を付ける。
     */
    private RedirectView failure(boolean accounting, String kind, BusinessException cause) {
        String url = accounting
                ? ACCOUNTING_PAGE + "?freee_error=" + kind
                : PAYROLL_PAGE + "?error=" + kind;
        String reason = cause == null ? null : cause.getMessage();
        if (reason != null && REASON_KEY.matcher(reason).matches()) {
            url += "&reason=" + reason;
        }
        return new RedirectView(url);
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
    }

    private boolean issuedAtExpired(Object issuedObj) {
        try {
            long issued = Long.parseLong(issuedObj.toString());
            return Instant.now().getEpochSecond() - issued > STATE_TTL_SECONDS;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
