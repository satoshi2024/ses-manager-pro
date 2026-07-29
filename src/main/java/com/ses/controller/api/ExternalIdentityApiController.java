package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.security.ExternalIdentityProvisionRequest;
import com.ses.entity.UserExternalIdentity;
import com.ses.service.ExternalIdentityProvisioningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理者承認済みのOIDC外部identity link API。更新系のためCSRF/監査対象となる。 */
@RestController
@RequestMapping("/api/identity-providers/{providerId}/external-identities")
@RequiredArgsConstructor
public class ExternalIdentityApiController {

    private final ExternalIdentityProvisioningService provisioningService;

    @PostMapping
    public ApiResult<UserExternalIdentity> provision(@PathVariable Long providerId,
                                                       @Valid @RequestBody ExternalIdentityProvisionRequest request) {
        return ApiResult.success(provisioningService.provision(providerId, request));
    }
}
