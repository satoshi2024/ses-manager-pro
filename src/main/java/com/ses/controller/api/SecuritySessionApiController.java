package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.config.MfaEnforcementFilter;
import com.ses.dto.security.MfaCodeRequest;
import com.ses.dto.security.MfaSetupResponse;
import com.ses.dto.security.MfaStatusResponse;
import com.ses.dto.security.SessionSummary;
import com.ses.service.security.MfaService;
import com.ses.service.security.PersistentSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** MFA登録/検証と本人の永続session管理API。全更新系はCSRF・監査対象。 */
@RestController
@RequestMapping("/api/security")
@RequiredArgsConstructor
public class SecuritySessionApiController {

    private final MfaService mfaService;
    private final PersistentSessionService persistentSessionService;
    private final com.ses.service.security.MfaAttemptService mfaAttemptService;
    private final com.ses.service.AuditLogService auditLogService;

    @GetMapping("/mfa/status")
    public ApiResult<MfaStatusResponse> mfaStatus(Authentication authentication, HttpServletRequest request) {
        Long userId = currentUserId();
        boolean required = mfaService.isRequired(authentication);
        HttpSession session = request.getSession(false);
        boolean pending = session != null && Boolean.TRUE.equals(
                session.getAttribute(MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE));
        boolean verified = session != null && Boolean.TRUE.equals(
                session.getAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE));
        return ApiResult.success(new MfaStatusResponse(required, mfaService.isConfigured(userId), pending, verified));
    }

    @PostMapping("/mfa/setup")
    public ApiResult<MfaSetupResponse> setup(Authentication authentication, HttpServletRequest httpRequest) {
        requireEnrollmentAccess(authentication, httpRequest);
        return ApiResult.success(mfaService.setup(currentUserId()));
    }

    @PostMapping("/mfa/enable")
    public ApiResult<MfaSetupResponse> enable(@Valid @RequestBody MfaCodeRequest request,
                                               Authentication authentication,
                                               HttpServletRequest httpRequest) {
        requireEnrollmentAccess(authentication, httpRequest);
        Long userId = currentUserId();
        mfaAttemptService.assertAllowed(userId, httpRequest);
        MfaSetupResponse response;
        try {
            response = mfaService.enable(userId, request.code());
        } catch (BusinessException e) {
            mfaAttemptService.recordFailure(userId, httpRequest);
            throw e;
        }
        auditLogService.recordRequired(authentication.getName(), "AUTH", "/api/security/mfa/enable", 200,
                "BREAK_GLASS_MFA_ENABLED", true);
        mfaAttemptService.recordSuccess(userId, httpRequest);
        markVerified(httpRequest);
        return ApiResult.success(response);
    }

    @PostMapping("/mfa/verify")
    public ApiResult<Boolean> verify(@Valid @RequestBody MfaCodeRequest request,
                                     Authentication authentication,
                                     HttpServletRequest httpRequest) {
        requireMfaUser(authentication);
        Long userId = currentUserId();
        mfaAttemptService.assertAllowed(userId, httpRequest);
        if (!mfaService.verify(userId, request.code())) {
            mfaAttemptService.recordFailure(userId, httpRequest);
            throw BusinessException.of(401, "error.mfa.invalidCode");
        }
        auditLogService.recordRequired(authentication.getName(), "AUTH", "/api/security/mfa/verify", 200,
                "BREAK_GLASS_MFA_VERIFIED", true);
        mfaAttemptService.recordSuccess(userId, httpRequest);
        markVerified(httpRequest);
        return ApiResult.success(true);
    }

    /** 管理者がMFAをresetした場合は対象ユーザーの全sessionも同時失効する。 */
    @PostMapping("/mfa/{userId}/reset")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<Boolean> reset(@PathVariable Long userId, HttpServletRequest request) {
        Long currentUserId = currentUserId();
        if (currentUserId.equals(userId) && !isMfaVerified(request)) {
            throw BusinessException.of(403, "error.mfa.reauthenticationRequired");
        }
        mfaService.reset(userId, "MFA_RESET_BY_ADMIN");
        return ApiResult.success(true);
    }

    @GetMapping("/sessions")
    public ApiResult<List<SessionSummary>> sessions(Authentication authentication, HttpServletRequest request) {
        return ApiResult.success(persistentSessionService.list(authentication, request));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResult<Boolean> revoke(@PathVariable Long sessionId, Authentication authentication,
                                     HttpServletRequest request) {
        persistentSessionService.revoke(sessionId, currentUserId(), currentHash(request), "USER_REVOKED");
        return ApiResult.success(true);
    }

    @PostMapping("/sessions/revoke-others")
    public ApiResult<Boolean> revokeOthers(Authentication authentication, HttpServletRequest request) {
        persistentSessionService.revokeOthers(currentUserId(), currentHash(request), "USER_REVOKED_OTHERS");
        return ApiResult.success(true);
    }

    @PostMapping("/sessions/revoke-all")
    public ApiResult<Boolean> revokeAll(Authentication authentication, HttpServletRequest request) {
        persistentSessionService.revokeAllForUser(currentUserId(), "USER_REVOKED_ALL");
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ApiResult.success(true);
    }

    private void requireMfaUser(Authentication authentication) {
        if (!mfaService.isRequired(authentication)) {
            throw BusinessException.of(403, "error.mfa.notRequired");
        }
    }

    private void requireEnrollmentAccess(Authentication authentication, HttpServletRequest request) {
        requireMfaUser(authentication);
        if (mfaService.isConfigured(currentUserId()) && !isMfaVerified(request)) {
            throw BusinessException.of(403, "error.mfa.reauthenticationRequired");
        }
    }

    private boolean isMfaVerified(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && Boolean.TRUE.equals(
                session.getAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE));
    }

    private Long currentUserId() {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException("認証ユーザーを解決できません");
        }
        return userId;
    }

    private void markVerified(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        session.setAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE, Boolean.TRUE);
        session.removeAttribute(MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE);
    }

    private String currentHash(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (String) session.getAttribute(PersistentSessionService.SESSION_HASH_ATTRIBUTE);
    }
}
