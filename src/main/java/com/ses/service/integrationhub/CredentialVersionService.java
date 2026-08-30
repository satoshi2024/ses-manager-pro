package com.ses.service.integrationhub;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.integrationhub.CredentialVersion;

import java.time.LocalDateTime;

/** NF-05 credential version persistence service。平文secretの返却APIを持たない。 */
public interface CredentialVersionService extends IService<CredentialVersion> {
    CredentialVersion issue(Long apiClientId, String clientId, int credentialVersion, String keyId,
                             String plaintextSecret, LocalDateTime now);

    CredentialVersion findUsable(Long apiClientId, String keyId, LocalDateTime now);

    CredentialVersion getByClientAndVersion(Long apiClientId, Integer credentialVersion);

    boolean revoke(Long apiClientId, Integer credentialVersion, LocalDateTime now);
}
