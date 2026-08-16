package com.ses.controller.api.portal;

import com.ses.common.result.ApiResult;
import com.ses.dto.portal.PortalAcceptInvitationRequest;
import com.ses.dto.portal.PortalConsentRequest;
import com.ses.dto.portal.PortalLoginRequest;
import com.ses.dto.portal.PortalLoginResponse;
import com.ses.dto.portal.PortalMeDto;
import com.ses.dto.portal.PortalMfaCompleteDto;
import com.ses.portal.PortalLoginUser;
import com.ses.service.portal.PortalAuthService;
import com.ses.service.portal.PortalAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * portal認証API（/api/portal/auth/**）。portal専用chainで処理され、内部/LoginUserとは独立（G3）。
 */
@RestController
@RequestMapping("/api/portal/auth")
@RequiredArgsConstructor
public class PortalAuthApiController {

    private final PortalAuthService authService;
    private final PortalAuthorizationService authorizationService;

    @PostMapping("/login")
    public ApiResult<PortalLoginResponse> login(@Valid @RequestBody PortalLoginRequest request,
                                                HttpServletRequest httpRequest,
                                                HttpServletResponse httpResponse) {
        return ApiResult.success(authService.login(request, httpRequest, httpResponse));
    }

    /** MFA有効化（loginでMFA_SETUPを受けた後の続き）。成功時にsession cookieを発行する。 */
    @PostMapping("/mfa/complete")
    public ApiResult<PortalMfaCompleteDto> completeMfa(@RequestParam("email") String email,
                                                       @RequestParam("code") String code,
                                                       HttpServletRequest httpRequest,
                                                       HttpServletResponse httpResponse) {
        return ApiResult.success(authService.completeMfa(email, code, httpRequest, httpResponse));
    }

    @PostMapping("/accept-invitation")
    public ApiResult<Void> acceptInvitation(@Valid @RequestBody PortalAcceptInvitationRequest request,
                                            HttpServletRequest httpRequest) {
        authService.acceptInvitation(request, httpRequest);
        return ApiResult.success(null);
    }

    @PostMapping("/consent")
    public ApiResult<Void> consent(@Valid @RequestBody PortalConsentRequest request,
                                   HttpServletRequest httpRequest) {
        authService.consentTerms(authorizationService.requireUser().getPortalUserId(),
                request.getTermsVersion(), httpRequest);
        return ApiResult.success(null);
    }

    @GetMapping("/me")
    public ApiResult<PortalMeDto> me() {
        PortalLoginUser user = authorizationService.requireUser();
        return ApiResult.success(PortalMeDto.builder()
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .orgType(user.getOrgType())
                .orgAdmin(user.isOrgAdmin())
                .termsPending(user.isTermsPending())
                .permissions(user.getPermissions())
                .build());
    }

    @PostMapping("/logout")
    public ApiResult<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        authService.logout(httpRequest, httpResponse);
        return ApiResult.success(null);
    }
}
