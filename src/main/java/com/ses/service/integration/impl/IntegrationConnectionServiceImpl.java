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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationConnectionServiceImpl extends ServiceImpl<IntegrationConnectionMapper, IntegrationConnection>
        implements IntegrationConnectionService {

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Long, ReentrantLock> connectionLocks = new ConcurrentHashMap<>();

    @Value("${freee.token-encryption-key:change-me-change-me-change-me-1234}")
    private String encryptionKey;

    @Override
    public IntegrationConnection getConnection(String tenantId, Long legalEntityId, String provider, String product) {
        String effectiveTenant = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
        LambdaQueryWrapper<IntegrationConnection> wrapper = new LambdaQueryWrapper<IntegrationConnection>()
                .eq(IntegrationConnection::getTenantId, effectiveTenant)
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
        String effectiveTenant = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
        IntegrationConnection newConn = IntegrationConnection.builder()
                .tenantId(effectiveTenant)
                .legalEntityId(legalEntityId)
                .provider(provider)
                .product(product)
                .status("DISCONNECTED")
                .version(0)
                .build();
        save(newConn);
        return newConn;
    }

    @Override
    @Transactional
    public void saveTokens(Long connectionId, IntegrationTokensDto tokens, Long companyId, String companyName, Long connectedBy) {
        IntegrationConnection conn = getById(connectionId);
        if (conn == null) {
            throw new BusinessException(404, "接続マスタが見つかりません (id=" + connectionId + ")");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(tokens);
        } catch (Exception e) {
            throw new BusinessException(500, "トークン情報のシリアライズに失敗しました");
        }
        String encrypted = encrypt(json);
        LocalDateTime expiresAt = null;
        if (tokens.getExpiresIn() != null && tokens.getExpiresIn() > 0) {
            expiresAt = LocalDateTime.now().plusSeconds(tokens.getExpiresIn());
        }

        conn.setEncryptedTokens(encrypted);
        conn.setExpiresAt(expiresAt);
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
                java.util.Map<String, Object> map = objectMapper.readValue(encrypted, java.util.Map.class);
                String accessEnc = map.get("accessToken") != null ? map.get("accessToken").toString() : null;
                String refreshEnc = map.get("refreshToken") != null ? map.get("refreshToken").toString() : null;

                String accessToken = decryptLegacyOrPlain(accessEnc);
                String refreshToken = decryptLegacyOrPlain(refreshEnc);

                return IntegrationTokensDto.builder()
                        .accessToken(accessToken)
                        .refreshToken(refreshToken)
                        .build();
            } catch (Exception e) {
                log.warn("Failed to parse/decrypt legacy JSON tokens for connectionId={}: {}", connectionId, e.getMessage());
            }
        }

        // 2. 新フォーマット (Base64URL(12-byte IV + GCM ciphertext) of IntegrationTokensDto JSON)
        try {
            String decryptedJson = decrypt(encrypted);
            return objectMapper.readValue(decryptedJson, IntegrationTokensDto.class);
        } catch (Exception e) {
            log.error("Failed to decrypt new-format tokens for connectionId={}", connectionId, e);
            throw new BusinessException(500, "トークン情報の復号・デシリアライズに失敗しました");
        }
    }

    private String decryptLegacyOrPlain(String cipherOrPlain) {
        if (cipherOrPlain == null || cipherOrPlain.isBlank()) return null;
        if (!cipherOrPlain.contains(":")) {
            return cipherOrPlain; // plain text or already decrypted
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
            log.warn("Legacy decryption failed, returning raw string: {}", e.getMessage());
        }
        return cipherOrPlain;
    }

    @Override
    public IntegrationTokensDto rotateTokens(Long connectionId, Function<IntegrationTokensDto, IntegrationTokensDto> refreshFn) {
        ReentrantLock lock = connectionLocks.computeIfAbsent(connectionId, k -> new ReentrantLock());
        lock.lock();
        try {
            // ロック取得後に最新の接続情報をDBから再読込 (P1-03: race condition防止)
            IntegrationConnection conn = getById(connectionId);
            if (conn == null) {
                throw new BusinessException(400, "接続情報が存在しません (id=" + connectionId + ")");
            }

            // 待機中に別スレッドがリフレッシュ成功していた場合 (expiresAtが現在+30秒より未来) は再リフレッシュをスキップ
            if (conn.getExpiresAt() != null && conn.getExpiresAt().isAfter(LocalDateTime.now().plusSeconds(30))) {
                log.info("Token for connectionId={} was already refreshed by another thread, skipping refreshFn", connectionId);
                return getDecryptedTokens(connectionId);
            }

            IntegrationTokensDto current = getDecryptedTokens(connectionId);
            if (current == null) {
                throw new BusinessException(400, "トークン情報が存在しません (id=" + connectionId + ")");
            }

            // リフレッシュ実行 (1回のみ)
            IntegrationTokensDto refreshed = refreshFn.apply(current);
            if (refreshed != null) {
                saveTokens(connectionId, refreshed, conn.getExternalCompanyId(), conn.getCompanyName(), conn.getConnectedBy());
                return refreshed;
            }
            return current;
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
        String effectiveTenant = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
        List<IntegrationConnection> list = list(new LambdaQueryWrapper<IntegrationConnection>()
                .eq(IntegrationConnection::getTenantId, effectiveTenant)
                .orderByAsc(IntegrationConnection::getProvider)
                .orderByAsc(IntegrationConnection::getProduct));
        // 秘密情報を安全にマスク
        for (IntegrationConnection c : list) {
            c.setEncryptedTokens(null);
        }
        return list;
    }

    // === 暗号化 / 復号 内部メソッド (AES-GCM) ===

    private String encrypt(String plainText) {
        if (plainText == null) return null;
        try {
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
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    private String decrypt(String cipherTextBase64) {
        if (cipherTextBase64 == null) return null;
        try {
            byte[] combined = Base64.getUrlDecoder().decode(cipherTextBase64);
            byte[] iv = Arrays.copyOfRange(combined, 0, 12);
            byte[] cipherText = Arrays.copyOfRange(combined, 12, combined.length);
            byte[] keyBytes = Arrays.copyOf(encryptionKey.getBytes(StandardCharsets.UTF_8), 32);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
            byte[] plainBytes = cipher.doFinal(cipherText);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
