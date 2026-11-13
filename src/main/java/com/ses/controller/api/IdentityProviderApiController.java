package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.entity.IdentityProvider;
import com.ses.service.IdentityProviderRegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * OIDC Identity Provider（{@code m_identity_provider}）の参照・登録API（管理者専用、SecurityConfigでも硬境界）。
 * 登録は起動設定の固定metadataから行い、request bodyを受け取らない。外部identity linkは
 * {@link ExternalIdentityApiController}（{@code /api/identity-providers/{providerId}/external-identities}）で行う。
 */
@RestController
@RequestMapping("/api/identity-providers")
@RequiredArgsConstructor
@PreAuthorize("hasRole('管理者')")
public class IdentityProviderApiController {

    private final IdentityProviderRegistrationService registrationService;

    @GetMapping
    public ApiResult<List<IdentityProvider>> list() {
        return ApiResult.success(registrationService.list());
    }

    @PostMapping
    public ApiResult<IdentityProvider> registerConfigured() {
        return ApiResult.success(registrationService.registerConfiguredOidcProvider());
    }
}
