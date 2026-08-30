package com.ses.service.integrationhub.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.integrationhub.CredentialVersion;
import com.ses.mapper.CredentialVersionMapper;
import com.ses.service.integrationhub.CredentialVersionService;
import com.ses.service.integrationhub.crypto.IntegrationHubSecretCryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** NF-05 credential version persistence implementation。 */
@Service
@RequiredArgsConstructor
public class CredentialVersionServiceImpl extends ServiceImpl<CredentialVersionMapper, CredentialVersion>
        implements CredentialVersionService {
    private final IntegrationHubSecretCryptoService cryptoService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CredentialVersion issue(Long apiClientId, String clientId, int credentialVersion, String keyId,
                                   String plaintextSecret, LocalDateTime now) {
        if (apiClientId == null || clientId == null || clientId.isBlank() || clientId.length() > 100
                || credentialVersion <= 0 || keyId == null || keyId.isBlank() || keyId.length() > 100
                || plaintextSecret == null || plaintextSecret.isBlank() || now == null) {
            throw new IllegalArgumentException("invalid credential issue request");
        }
        // 旧世代は24時間だけOVERLAP。旧secretの値はこのメソッド外へ返さず、新世代のenvelopeのみ保存する。
        List<CredentialVersion> current = baseMapper.selectRotatable(apiClientId);
        for (CredentialVersion old : current) {
            if ("ACTIVE".equals(old.getStatus())) {
                old.setStatus("OVERLAP");
                old.setOverlapUntil(now.plusHours(24));
                old.setUpdatedAt(now);
                updateById(old);
            }
        }
        String envelope = cryptoService.encrypt(clientId, credentialVersion, "credential", plaintextSecret);
        CredentialVersion created = CredentialVersion.builder()
                .apiClientId(apiClientId)
                .credentialVersion(credentialVersion)
                .keyId(keyId)
                .encryptedSecret(envelope)
                .secretHash(cryptoService.sha256Hex(plaintextSecret))
                .cryptoKeyVersion(extractKeyVersion(envelope))
                .cipherFormat("IHG1")
                .status("ACTIVE")
                .issuedAt(now)
                .expiresAt(now.plusDays(90))
                .version(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        baseMapper.insert(created);
        return created;
    }
    @Override
    public CredentialVersion findUsable(Long apiClientId, String keyId, LocalDateTime now) {
        if (apiClientId == null || keyId == null || keyId.isBlank() || now == null) {
            return null;
        }
        return baseMapper.selectUsable(apiClientId, keyId, now);
    }

    @Override
    public CredentialVersion getByClientAndVersion(Long apiClientId, Integer credentialVersion) {
        if (apiClientId == null || credentialVersion == null) {
            return null;
        }
        return baseMapper.selectByClientAndVersion(apiClientId, credentialVersion);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean revoke(Long apiClientId, Integer credentialVersion, LocalDateTime now) {
        if (apiClientId == null || credentialVersion == null || now == null) {
            throw new IllegalArgumentException("invalid credential revoke request");
        }
        CredentialVersion row = baseMapper.selectForUpdate(apiClientId, credentialVersion);
        return row != null && baseMapper.revoke(apiClientId, credentialVersion, row.getVersion(), now) == 1;
    }

    private String extractKeyVersion(String envelope) {
        String[] parts = envelope.split(":", 4);
        if (parts.length != 4 || !"IHG1".equals(parts[0])) {
            throw new IllegalStateException("invalid credential envelope");
        }
        return parts[1];
    }
}
