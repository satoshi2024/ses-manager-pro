package com.ses.service.integration.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.IntegrationTokensDto;
import com.ses.entity.IntegrationConnection;
import com.ses.mapper.IntegrationConnectionMapper;
import com.ses.service.integration.IntegrationConnectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationConnectionServiceImpl
        extends ServiceImpl<IntegrationConnectionMapper, IntegrationConnection>
        implements IntegrationConnectionService {

    private final ObjectMapper objectMapper;
    private final Map<Long, ReentrantLock> connectionLocks = new ConcurrentHashMap<>();

    @Value("${freee.token.encryption-key:ses-manager-pro-default-32byte-key!!}")
    private String encryptionKey;

    @Override
    public IntegrationConnection getConnection(String tenantId, Long legalEntityId, String provider, String product) {
        LambdaQueryWrapper<IntegrationConnection> wrapper = new LambdaQueryWrapper<IntegrationConnection>()
                .eq(IntegrationConnection::getTenantId, tenantId != null ? tenantId : "default")
                .eq(IntegrationConnection::getProvider, provider)
                .eq(IntegrationConnection::getProduct, product);

        if (legalEntityId != null) {
            wrapper.eq(IntegrationConnection::getLegalEntityId, legalEntityId);
        } else {
            wrapper.isNull(IntegrationConnection::getLegalEntityId);
        }

        return getOne(wrapper);
    }

    @Override
    @Transactional
    public IntegrationConnection getOrCreateConnection(String tenantId, Long legalEntityId, String provider, String product) {
        IntegrationConnection existing = getConnection(tenantId, legalEntityId, provider, product);
        if (existing != null) {
            return existing;
        }

        IntegrationConnection conn = IntegrationConnection.builder()
                .tenantId(tenantId != null ? tenantId : "default")
                .legalEntityId(legalEntityId)
                .provider(provider)
                .product(product)
                .status("DISCONNECTED")
                .version(0)
                .build();
        save(conn);
        return conn;
    }

    @Override
    @Transactional
    public void saveTokens(Long connectionId, IntegrationTokensDto tokens, Long companyId, String companyName, Long connectedBy) {
        IntegrationConnection conn = getById(connectionId);
        if (conn == null) {
            throw new BusinessException(400, "接続情報が存在しません (id=" + connectionId + ")");
        }

        String encrypted;
        try {
            String json = objectMapper.writeValueAsString(tokens);
            encrypted = encrypt(json);
        } catch (Exception e) {
            throw new RuntimeException("Tokens JSON serialization / encryption failed", e);
        }

        LocalDateTime expiresAt = null;
        if (tokens.getExpiresIn() != null) {
            expiresAt = LocalDateTime.now().plusSeconds(tokens.getExpiresIn());
        }

        conn.setEncryptedTokens(encrypted);
        conn.setExpiresAt(expiresAt);
        conn.setLastRefreshedAt(LocalDateTime.now());
        if (companyId != null) {
            conn.setExternalCompanyId(companyId);
        }
        if (companyName != null && !companyName.isBlank()) {
            conn.setCompanyName(companyName);
        }
        conn.setStatus("CONNECTED");
        conn.setConnectedBy(connectedBy);
        conn.setConnectedAt(LocalDateTime.now());
        updateById(conn);
    }

    @Override
    public IntegrationTokensDto getDecryptedTokens(Long connectionId) {
        IntegrationConnection conn = getById(connectionId);
        if (conn == null || conn.getEncryptedTokens() == null || conn.getEncryptedTokens().isBlank()) {
            return null;
        }
        String encrypted = conn.getEncryptedTokens().trim();

        // 1. JSON形式の互換判定（V106で移行された旧フォーマット: {"accessToken": "iv:cipher", ...} または平文JSON）
        if (encrypted.startsWith("{") && encrypted.endsWith("}")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = objectMapper.readValue(encrypted, Map.class);
                String accessEnc = map.get("accessToken") != null ? map.get("accessToken").toString() : null;
                String refreshEnc = map.get("refreshToken") != null ? map.get("refreshToken").toString() : null;

                String accessToken = decryptLegacyOrPlain(connectionId, accessEnc);
                String refreshToken = decryptLegacyOrPlain(connectionId, refreshEnc);

                return IntegrationTokensDto.builder()
                        .accessToken(accessToken)
                        .refreshToken(refreshToken)
                        .build();
            } catch (Exception e) {
                log.warn("Failed to parse/decrypt legacy JSON tokens for connectionId={}: {}", connectionId, e.getMessage());
                markReauthRequired(connectionId);
                throw new BusinessException(401, "認証情報の復号に失敗しました。再接続を行ってください。");
            }
        }

        // 2. 新フォーマット (Base64URL(12-byte IV + GCM ciphertext) of IntegrationTokensDto JSON)
        try {
            String decryptedJson = decrypt(encrypted);
            return objectMapper.readValue(decryptedJson, IntegrationTokensDto.class);
        } catch (Exception e) {
            log.error("Failed to decrypt new-format tokens for connectionId={}", connectionId, e);
            markReauthRequired(connectionId);
            throw new BusinessException(401, "認証情報の復号に失敗しました。再接続を行ってください。");
        }
    }

    private String decryptLegacyOrPlain(Long connectionId, String cipherOrPlain) {
        if (cipherOrPlain == null || cipherOrPlain.isBlank()) return null;
        if (!cipherOrPlain.contains(":")) {
            return cipherOrPlain; // plain text
        }
        try {
            String[] p = cipherOrPlain.split(":");
            if (p.length == 2) {
                byte[] iv = Base64.getDecoder().decode(p[0]);
                byte[] cipherText = Base64.getDecoder().decode(p[1]);
                byte[] keyBytes = Arrays.copyOf(encryptionKey.getBytes(StandardCharsets.UTF_8), 32);
                SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
                byte[] plain = cipher.doFinal(cipherText);
                return new String(plain, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.error("Legacy decryption failed for connectionId={}: {}", connectionId, e.getMessage());
            markReauthRequired(connectionId);
            throw new BusinessException(401, "旧トークンの復号に失敗しました。再接続が必要です。");
        }
        throw new BusinessException(401, "不正なトークン形式です。再接続を行ってください。");
    }

    private void markReauthRequired(Long connectionId) {
        try {
            IntegrationConnection conn = getById(connectionId);
            if (conn != null && !"REAUTH_REQUIRED".equals(conn.getStatus())) {
                conn.setStatus("REAUTH_REQUIRED");
                updateById(conn);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public IntegrationTokensDto rotateTokens(Long connectionId, Function<IntegrationTokensDto, IntegrationTokensDto> refreshFn) {
        return doRotateTokens(connectionId, refreshFn, false);
    }

    @Override
    public IntegrationTokensDto forceRefreshToken(Long connectionId, Function<IntegrationTokensDto, IntegrationTokensDto> refreshFn) {
        return doRotateTokens(connectionId, refreshFn, true);
    }

    private IntegrationTokensDto doRotateTokens(Long connectionId,
                                                Function<IntegrationTokensDto, IntegrationTokensDto> refreshFn,
                                                boolean force) {
        ReentrantLock lock = connectionLocks.computeIfAbsent(connectionId, k -> new ReentrantLock());
        lock.lock();
        try {
            // 1. 現在行の読み込み (Non-locking)
            IntegrationConnection conn = baseMapper.selectCurrentState(connectionId);
            if (conn == null) {
                throw new BusinessException(400, "接続情報が存在しません (id=" + connectionId + ")");
            }

            // force == false の場合、既に他ノード/スレッドによって更新済みなら再リフレッシュをスキップ
            if (!force && conn.getExpiresAt() != null && conn.getExpiresAt().isAfter(LocalDateTime.now().plusSeconds(30))) {
                log.info("Token for connectionId={} was already refreshed, skipping refreshFn", connectionId);
                return getDecryptedTokens(connectionId);
            }

            int observedTokenVersion = conn.getTokenVersion() != null ? conn.getTokenVersion() : 1;
            String workerUuid = java.util.UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime leaseExpiresAt = now.plusSeconds(45);

            // Step 1: 排他リース獲得 CAS (短期 DB 操作、HTTP 前に完了)
            int leaseClaimed = baseMapper.claimRefreshLeaseCas(connectionId, observedTokenVersion, workerUuid, leaseExpiresAt, now);
            if (leaseClaimed == 0) {
                // 敗者ノード: 他ノードがリース保有中または既に更新完了
                // バックオフ待機: 500ms, 1000ms, 2000ms (計3回、合計最大 3.5秒)
                long[] backoffs = {500, 1000, 2000};
                for (long sleepMs : backoffs) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new com.ses.common.exception.TokenRefreshInProgressException(connectionId);
                    }

                    IntegrationConnection state = baseMapper.selectCurrentState(connectionId);
                    if (state != null) {
                        int currentVersion = state.getTokenVersion() != null ? state.getTokenVersion() : 1;
                        if (currentVersion > observedTokenVersion) {
                            // 他ノードが更新完了 -> 確定トークンを復号して返却
                            log.info("Node won by another worker, token_version advanced from {} to {}. Using new tokens.",
                                    observedTokenVersion, currentVersion);
                            return getDecryptedTokens(connectionId);
                        }
                        if (state.getRefreshLeaseExpiresAt() == null || state.getRefreshLeaseExpiresAt().isBefore(LocalDateTime.now())) {
                            // リースが失効した -> 自ノードで再試行
                            observedTokenVersion = currentVersion;
                            now = LocalDateTime.now();
                            leaseExpiresAt = now.plusSeconds(45);
                            leaseClaimed = baseMapper.claimRefreshLeaseCas(connectionId, observedTokenVersion, workerUuid, leaseExpiresAt, now);
                            if (leaseClaimed == 1) {
                                break;
                            }
                        }
                    }
                }

                if (leaseClaimed == 0) {
                    log.warn("Token refresh still in progress by another node after 3 backoffs (connectionId={}). Throwing TokenRefreshInProgressException", connectionId);
                    throw new com.ses.common.exception.TokenRefreshInProgressException(connectionId);
                }
            }

            // Step 2: 外部 OAuth トークン更新 (DB トランザクション外、10s timeout)
            IntegrationTokensDto current = getDecryptedTokens(connectionId);
            IntegrationTokensDto refreshed;
            try {
                refreshed = refreshFn.apply(current);
            } catch (Exception e) {
                log.error("Token refresh HTTP call failed for connectionId={}: {}", connectionId, e.getMessage());
                if (e.getMessage() != null && e.getMessage().contains("invalid_grant")) {
                    baseMapper.markReauthRequired(connectionId, LocalDateTime.now());
                }
                throw e;
            }

            if (refreshed == null) {
                return current;
            }

            // Step 3: 新トークン確定 Fencing CAS (短期 DB 操作)
            String encrypted;
            try {
                String json = objectMapper.writeValueAsString(refreshed);
                encrypted = encrypt(json);
            } catch (Exception e) {
                throw new RuntimeException("Tokens JSON serialization / encryption failed", e);
            }

            LocalDateTime expiresAt = null;
            if (refreshed.getExpiresIn() != null) {
                expiresAt = LocalDateTime.now().plusSeconds(refreshed.getExpiresIn());
            }

            int committed = baseMapper.commitRefreshTokenCas(
                    connectionId, observedTokenVersion, workerUuid, encrypted, expiresAt, LocalDateTime.now());

            if (committed == 1) {
                log.info("Token refresh succeeded and committed via CAS for connectionId={}, new token_version={}",
                        connectionId, observedTokenVersion + 1);
                return refreshed;
            } else {
                log.warn("Token refresh fencing CAS failed for connectionId={} (lease expired or stolen). Discarding new token and reloading state.", connectionId);
                return getDecryptedTokens(connectionId);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    @Transactional
    public void updateStatus(Long connectionId, String status) {
        IntegrationConnection conn = getById(connectionId);
        if (conn != null) {
            conn.setStatus(status);
            updateById(conn);
        }
    }

    @Override
    public List<IntegrationConnection> listConnections(String tenantId) {
        List<IntegrationConnection> list = list(new LambdaQueryWrapper<IntegrationConnection>()
                .eq(IntegrationConnection::getTenantId, tenantId != null ? tenantId : "default")
                .orderByAsc(IntegrationConnection::getId));

        for (IntegrationConnection c : list) {
            c.setEncryptedTokens(null); // セキュリティのためマスク
        }
        return list;
    }

    // === AES-256 GCM 暗号化 / 復号 ===

    private String encrypt(String plainText) throws Exception {
        byte[] keyBytes = Arrays.copyOf(encryptionKey.getBytes(StandardCharsets.UTF_8), 32);
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
        byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
    }

    private String decrypt(String cipherTextBase64) throws Exception {
        byte[] combined = Base64.getUrlDecoder().decode(cipherTextBase64);
        if (combined.length < 12) {
            throw new IllegalArgumentException("Invalid ciphertext length");
        }

        byte[] iv = Arrays.copyOfRange(combined, 0, 12);
        byte[] cipherText = Arrays.copyOfRange(combined, 12, combined.length);

        byte[] keyBytes = Arrays.copyOf(encryptionKey.getBytes(StandardCharsets.UTF_8), 32);
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
        byte[] plain = cipher.doFinal(cipherText);

        return new String(plain, StandardCharsets.UTF_8);
    }
}
