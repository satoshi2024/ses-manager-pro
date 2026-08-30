package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.ApiNonceReplay;
import com.ses.mapper.ApiNonceReplayMapper;
import com.ses.service.integrationhub.ApiNonceReplayService;
import com.ses.service.integrationhub.IntegrationHubDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** NF-05 nonce replay ledger implementation。rotationを跨いだ再利用もuniqueで拒否する。 */
@Service
@RequiredArgsConstructor
public class ApiNonceReplayServiceImpl implements ApiNonceReplayService {
    private final ApiNonceReplayMapper mapper;
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean accept(String clientId, int credentialVersion, byte[] rawNonce,
                          LocalDateTime signedTimestamp, LocalDateTime acceptedAt) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 100 || credentialVersion <= 0
                || rawNonce == null || rawNonce.length < 16 || rawNonce.length > 128
                || signedTimestamp == null || acceptedAt == null) {
            throw new IllegalArgumentException("invalid nonce acceptance");
        }
        String nonceHash = IntegrationHubDigest.sha256Hex(rawNonce);
        LocalDateTime expiresAt = (acceptedAt.isAfter(signedTimestamp) ? acceptedAt : signedTimestamp).plusMinutes(5);
        ApiNonceReplay row = ApiNonceReplay.builder()
                .clientId(clientId)
                .credentialVersion(credentialVersion)
                .nonceHash(nonceHash)
                .acceptedAt(acceptedAt)
                .expiresAt(expiresAt)
                .createdAt(acceptedAt)
                .build();
        try {
            mapper.insert(row);
            return true;
        } catch (DuplicateKeyException e) {
            // duplicateは認証失敗へ収束し、raw nonceやkeyをmessage/logへ出さない。
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int purgeExpired(LocalDateTime serverNow, int maxRows) {
        if (serverNow == null || maxRows <= 0) {
            throw new IllegalArgumentException("invalid nonce purge request");
        }
        return mapper.deleteExpiredBatch(serverNow, Math.min(maxRows, 1000));
    }
}
