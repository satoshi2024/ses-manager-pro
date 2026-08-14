package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.service.AuditLogService;
import com.ses.service.FreeeIntegrationService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
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
            // 設定不足は接続画面へ固定error codeで戻す（client_id等は載せない）
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
        // sessionのstateは先に除去し、再送・二重callbackでは受理しない
        Object expected = session.getAttribute(SESSION_STATE);
        Object issuedObj = session.getAttribute(SESSION_STATE_ISSUED);
        session.removeAttribute(SESSION_STATE);
        session.removeAttribute(SESSION_STATE_ISSUED);

        if (error != null) {
            // freee側の認可拒否（access_denied等）。token交換しない。
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
            // token/company検証失敗。provider messageやcodeはredirectへ載せない。
            // 失敗もFREEE_CONNECTとして1 row監査する（REV-003 / R09-4）。
            auditLogService.record(SecurityUtils.currentUsername(), "GET", URI_CONNECT, 400,
                    CODE_CONNECT, false);
            return new RedirectView("/payroll?error=oauth");
        }
        auditLogService.recordRequired(SecurityUtils.currentUsername(), "GET", URI_CONNECT, 302,
                CODE_CONNECT, true);
        return new RedirectView("/payroll?connected=1");
    }

    @DeleteMapping
    public RedirectView disconnect(HttpServletResponse response) {
        noStore(response);
        try {
            service.disconnect();
            auditLogService.recordRequired(SecurityUtils.currentUsername(), "DELETE", URI_DISCONNECT, 302,
                    CODE_DISCONNECT, true);
            return new RedirectView("/payroll");
        } catch (BusinessException e) {
            // 一時障害ではlocal rowを保持し、「解除済み」と表示しない。
            // 失敗もFREEE_DISCONNECTとして1 row監査する（REV-003 / R09-4）。
            auditLogService.record(SecurityUtils.currentUsername(), "DELETE", URI_DISCONNECT,
                    e.getCode() == 0 ? 400 : e.getCode(), CODE_DISCONNECT, false);
            return new RedirectView("/payroll?error=disconnect");
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
