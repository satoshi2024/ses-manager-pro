package com.ses.service.security;

import com.ses.dto.security.MfaSetupResponse;
import org.springframework.security.core.Authentication;

/** ローカルbreak-glass用TOTP/recovery codeサービス。 */
public interface MfaService {

    boolean isRequired(Authentication authentication);

    boolean isConfigured(Long userId);

    MfaSetupResponse setup(Long userId);

    MfaSetupResponse enable(Long userId, String code);

    boolean verify(Long userId, String code);

    void reset(Long userId, String reason);
}
