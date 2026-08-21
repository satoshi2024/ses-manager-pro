package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.service.AuditLogService;
import com.ses.service.FreeeIntegrationService;
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
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('管理者')")
@RequestMapping("/integrations/freee")
public class FreeeOAuthController {

    public static final String SESSION_STATE = "freee_oauth_state";
    public static final String SESSION_STATE_ISSUED = "freee_oauth_state_issued";
    /** state有効期限（分）。 */
    public static final long STATE_TTL_SECONDS = 10 * 60L;

    private static final String CODE_CONNECT = "FREEE_CONNECT";
    private static final String CODE_DISCONNECT = "FREEE_DISCONNECT";
    private static final String URI_CONNECT = "/integrations/freee";
    private static final String URI_DISCONNECT = "/integrations/freee";

    private final FreeeIntegrationService service;
    private final AuditLogService auditLogService;

    @GetMapping("/authorize")
    public RedirectView authorize(HttpSession session, HttpServletResponse response) {
        noStore(response);
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        session.setAttribute(SESSION_STATE, state);
        session.setAttribute(SESSION_STATE_ISSUED, Instant.now().getEpochSecond());
        try {
            return new RedirectView(service.authorizationUrl(state));
        } catch (BusinessException e) {
            return new RedirectView("/payroll?error=config");
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
        session.removeAttribute(SESSION_STATE);
        session.removeAttribute(SESSION_STATE_ISSUED);

        if (error != null) {
            return new RedirectView("/payroll?error=denied");
        }
        if (expected == null || !constantTimeEquals(expected.toString(), state)) {
            return new RedirectView("/payroll?error=state");
        }
        if (issuedObj == null || issuedAtExpired(issuedObj)) {
            return new RedirectView("/payroll?error=state");
        }
        if (code == null || code.isBlank()) {
            return new RedirectView("/payroll?error=oauth");
        }

        Long uid = null;
        if (auth != null && auth.getPrincipal() instanceof com.ses.config.LoginUser u) {
            uid = u.getSysUser().getId();
        }

        try {
            service.handleCallback(code, state, uid);
        } catch (BusinessException e) {
            auditLogService.record(SecurityUtils.currentUsername(), "GET", URI_CONNECT, 400,
                    CODE_CONNECT, false);
            return new RedirectView("/payroll?error=oauth");
        } catch (Exception e) {
            auditLogService.record(SecurityUtils.currentUsername(), "GET", URI_CONNECT, 500,
                    CODE_CONNECT, false);
            throw e;
        }
        auditLogService.recordRequired(SecurityUtils.currentUsername(), "GET", URI_CONNECT, 302,
                CODE_CONNECT, true);
        return new RedirectView("/payroll?connected=1");
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
