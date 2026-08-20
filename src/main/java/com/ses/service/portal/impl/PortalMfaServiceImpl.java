package com.ses.service.portal.impl;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityHashUtil;
import com.ses.common.util.TotpUtil;
import com.ses.config.MfaSecurityProperties;
import com.ses.dto.portal.PortalMfaCompleteDto;
import com.ses.dto.portal.PortalMfaSetupDto;
import com.ses.entity.PortalUser;
import com.ses.mapper.PortalUserMapper;
import com.ses.service.portal.PortalMfaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * portal TOTP MFA実装。secretはAES-GCMで暗号化して保存し（内部MfaServiceImplと同形式）、
 * 同一stepのコード再使用は last_used_step のCASで拒否する。
 */
@Service
@RequiredArgsConstructor
public class PortalMfaServiceImpl implements PortalMfaService {

    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private final PortalUserMapper userMapper;
    private final MfaSecurityProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    @Transactional
    public PortalMfaSetupDto setup(Long portalUserId) {
        PortalUser user = requireUser(portalUserId);
        if (user.getMfaEnabledAt() != null) {
            throw BusinessException.of(409, "error.portal.mfa.alreadyEnabled");
        }
        String secret = TotpUtil.generateSecret();
        String encrypted = encrypt(secret);
        // login毎に準備値を上書きする（enable前に再loginされても最新secretで検証される）
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalUser>()
                .eq("id", portalUserId)
                .set("totp_secret_encrypted", encrypted)
                .set("totp_secret_key_version", currentKeyVersion())
                .set("last_used_step", null));
        String uri = otpauthUri(user.getEmail(), secret);
        return new PortalMfaSetupDto(secret, uri);
    }

    @Override
    @Transactional
    public PortalMfaCompleteDto enable(Long portalUserId, String code) {
        PortalUser user = requireUser(portalUserId);
        if (user.getMfaEnabledAt() != null) {
            throw BusinessException.of(409, "error.portal.mfa.alreadyEnabled");
        }
        if (!StringUtils.hasText(user.getTotpSecretEncrypted())) {
            throw BusinessException.of(400, "error.portal.mfa.setupRequired");
        }
        String secret = decrypt(user.getTotpSecretEncrypted());
        long currentStep = currentStep();
        long acceptedStep = matchingStep(secret, code, currentStep);
        if (acceptedStep < 0) {
            throw BusinessException.of(401, "error.portal.mfa.invalidCode");
        }
        // 同一stepのコード再使用をCASで拒否する
        int updated = userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalUser>()
                .eq("id", portalUserId)
                .and(w -> w.isNull("last_used_step").or().lt("last_used_step", acceptedStep))
                .set("last_used_step", acceptedStep)
                .set("mfa_enabled_at", LocalDateTime.now(clock)));
        if (updated == 0) {
            throw BusinessException.of(401, "error.portal.mfa.invalidCode");
        }
        String recoveryCode = TotpUtil.randomRecoveryCode();
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalUser>()
                .eq("id", portalUserId)
                .set("recovery_code_hash", SecurityHashUtil.sha256(normalizeCode(recoveryCode)))
                .set("recovery_code_used_at", null));
        return new PortalMfaCompleteDto(recoveryCode);
    }

    @Override
    @Transactional
    public boolean verify(Long portalUserId, String code) {
        PortalUser user = userMapper.selectById(portalUserId);
        if (user == null || user.getMfaEnabledAt() == null) {
            return false;
        }
        if (!StringUtils.hasText(user.getTotpSecretEncrypted())) {
            return false;
        }
        String normalizedCode = normalizeCode(code);
        String secret = decrypt(user.getTotpSecretEncrypted());
        long currentStep = currentStep();
        long acceptedStep = matchingStep(secret, normalizedCode, currentStep);
        if (acceptedStep >= 0) {
            int updated = userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalUser>()
                    .eq("id", portalUserId)
                    .and(w -> w.isNull("last_used_step").or().lt("last_used_step", acceptedStep))
                    .set("last_used_step", acceptedStep));
            return updated == 1;
        }
        // recovery code（1回限り。hash照合は恒時比較 + 使用済みCAS。S13-P2-01）
        if (!StringUtils.hasText(normalizedCode)) {
            return false;
        }
        PortalUser fresh = userMapper.selectById(portalUserId);
        String expectedHash = fresh == null ? null : fresh.getRecoveryCodeHash();
        String actualHash = SecurityHashUtil.sha256(normalizedCode);
        if (fresh == null || expectedHash == null || actualHash == null
                || !java.security.MessageDigest.isEqual(
                        expectedHash.getBytes(StandardCharsets.UTF_8),
                        actualHash.getBytes(StandardCharsets.UTF_8))
                || fresh.getRecoveryCodeUsedAt() != null) {
            return false;
        }
        int updated = userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalUser>()
                .eq("id", portalUserId)
                .isNull("recovery_code_used_at")
                .set("recovery_code_used_at", LocalDateTime.now(clock)));
        return updated == 1;
    }

    private PortalUser requireUser(Long portalUserId) {
        PortalUser user = portalUserId == null ? null : userMapper.selectById(portalUserId);
        if (user == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return user;
    }

    private long matchingStep(String secret, String code, long currentStep) {
        if (!StringUtils.hasText(code)) {
            return -1;
        }
        for (long offset = -properties.getClockSkewSteps(); offset <= properties.getClockSkewSteps(); offset++) {
            long step = currentStep + offset;
            if (step >= 0 && TotpUtil.matches(secret, code, step, properties.getDigits())) {
                return step;
            }
        }
        return -1;
    }

    private long currentStep() {
        return java.time.Instant.now(clock).getEpochSecond() / properties.getPeriodSeconds();
    }

    private String normalizeCode(String code) {
        return code == null ? null : code.trim().replace(" ", "").toUpperCase(java.util.Locale.ROOT);
    }

    private String otpauthUri(String email, String secret) {
        String issuer = properties.getIssuer();
        return "otpauth://totp/" + encode(issuer + ":" + email)
                + "?secret=" + secret + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + properties.getDigits()
                + "&period=" + properties.getPeriodSeconds();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String encrypt(String secret) {
        try {
            String keyVersion = currentKeyVersion();
            byte[] iv = new byte[12];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(keyVersion), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return keyVersion + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
                    + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("portal MFA secretの暗号化に失敗しました", e);
        }
    }

    private String decrypt(String value) {
        try {
            String[] parts = value.split(":", 3);
            if (parts.length != 3 || !StringUtils.hasText(parts[0])) {
                throw new IllegalArgumentException("portal MFA secretのkey versionが不正です");
            }
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(parts[0]), new GCMParameterSpec(GCM_TAG_BITS,
                    Base64.getUrlDecoder().decode(parts[1])));
            return new String(cipher.doFinal(Base64.getUrlDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("portal MFA secretの復号に失敗しました", e);
        }
    }

    private SecretKeySpec key(String version) {
        try {
            String configured = properties.getKeyring() == null ? null : properties.getKeyring().get(version);
            if (!StringUtils.hasText(configured) && "v1".equals(version)) {
                configured = properties.getEncryptionKey();
            }
            if (!StringUtils.hasText(configured)) {
                throw new IllegalArgumentException("portal MFA secretのkey versionが設定されていません");
            }
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(configured.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(bytes, "AES");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }

    private String currentKeyVersion() {
        return StringUtils.hasText(properties.getCurrentKeyVersion()) ? properties.getCurrentKeyVersion() : "v1";
    }
}
