package com.ses.service;

import com.ses.dto.security.ExternalIdentityProvisionRequest;
import com.ses.entity.UserExternalIdentity;

/** 管理者承認済みの外部identity linkを管理するサービス。 */
public interface ExternalIdentityProvisioningService {

    UserExternalIdentity provision(Long providerId, ExternalIdentityProvisionRequest request);
}
