package com.ses.dto.security;

import java.util.List;

/** MFA登録時だけ返すsecret/URIと、enable成功時だけ返すrecovery code。 */
public record MfaSetupResponse(
        boolean enabled,
        String secret,
        String otpauthUri,
        List<String> recoveryCodes) {
}
