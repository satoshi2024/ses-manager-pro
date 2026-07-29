package com.ses.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityHashUtil;
import com.ses.common.util.TotpUtil;
import com.ses.config.MfaSecurityProperties;
import com.ses.config.OidcSecurityProperties;
import com.ses.dto.security.MfaSetupResponse;
import com.ses.entity.MfaRecoveryCode;
import com.ses.entity.SysUser;
import com.ses.entity.UserMfa;
import com.ses.mapper.MfaRecoveryCodeMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserMfaMapper;
import com.ses.service.security.MfaService;
import com.ses.service.security.PersistentSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** ローカルbreak-glassのTOTPとrecovery codeを管理する。 */
@Service
@RequiredArgsConstructor
public class MfaServiceImpl implements MfaService {

    private static final String KEY_VERSION = "v1";
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private final UserMfaMapper userMfaMapper;
    private final MfaRecoveryCodeMapper recoveryCodeMapper;
    private final SysUserMapper sysUserMapper;
    private final MfaSecurityProperties properties;
    private final OidcSecurityProperties oidcProperties;
    private final PersistentSessionService persistentSessionService;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public boolean isRequired(Authentication authentication) {
        return properties.isEnabled() && properties.isRequiredForBreakGlass()
                && authentication != null && oidcProperties.isBreakGlassUsername(authentication.getName());
    }

    @Override
    public boolean isConfigured(Long userId) {
        UserMfa mfa = findMfa(userId);
        return mfa != null && mfa.getEnabledAt() != null && StringUtils.hasText(mfa.getEncryptedTotpSecret());
    }

    @Override
    @Transactional
    public MfaSetupResponse setup(Long userId) {
        requireActiveUser(userId);
        String secret = TotpUtil.generateSecret();
        UserMfa mfa = findMfa(userId);
        String encrypted = encrypt(secret);
        if (mfa == null) {
            mfa = new UserMfa();
            mfa.setTenantId(tenantId());
            mfa.setUserId(userId);
            mfa.setEncryptedTotpSecret(encrypted);
            mfa.setSecretKeyVersion(KEY_VERSION);
            userMfaMapper.insert(mfa);
        } else {
            userMfaMapper.prepareSetup(mfa.getId(), encrypted, KEY_VERSION);
        }
        recoveryCodeMapper.revokeAvailable(tenantId(), userId, LocalDateTime.now(clock));
        SysUser user = sysUserMapper.selectById(userId);
        String uri = otpauthUri(user.getUsername(), secret);
        return new MfaSetupResponse(false, secret, uri, List.of());
    }

    @Override
    @Transactional
    public MfaSetupResponse enable(Long userId, String code) {
        UserMfa mfa = findMfa(userId);
        if (mfa == null || !StringUtils.hasText(mfa.getEncryptedTotpSecret())) {
            throw BusinessException.of("error.mfa.setupRequired");
        }
        String secret = decrypt(mfa.getEncryptedTotpSecret());
        long currentStep = currentStep();
        long acceptedStep = matchingStep(secret, code, currentStep);
        if (acceptedStep < 0 || userMfaMapper.advanceLastUsedStep(mfa.getId(), currentStep) != 1) {
            throw BusinessException.of("error.mfa.invalidCode");
        }
        userMfaMapper.enable(mfa.getId(), LocalDateTime.now(clock));
        List<String> recoveryCodes = issueRecoveryCodes(userId);
        return new MfaSetupResponse(true, null, null, recoveryCodes);
    }

    @Override
    @Transactional
    public boolean verify(Long userId, String code) {
        UserMfa mfa = findMfa(userId);
        if (mfa == null || mfa.getEnabledAt() == null) {
            return false;
        }
        String normalizedCode = normalizeCode(code);
        String secret = decrypt(mfa.getEncryptedTotpSecret());
        long currentStep = currentStep();
        long acceptedStep = matchingStep(secret, normalizedCode, currentStep);
        if (acceptedStep >= 0) {
            return userMfaMapper.advanceLastUsedStep(mfa.getId(), currentStep) == 1;
        }
        if (!StringUtils.hasText(normalizedCode)) {
            return false;
        }
        MfaRecoveryCode recoveryCode = recoveryCodeMapper.selectAvailable(
                tenantId(), userId, SecurityHashUtil.sha256(normalizedCode));
        return recoveryCode != null
                && recoveryCodeMapper.markUsed(recoveryCode.getId(), LocalDateTime.now(clock)) == 1;
    }

    @Override
    @Transactional
    public void reset(Long userId, String reason) {
        UserMfa mfa = findMfa(userId);
        if (mfa != null) {
            userMfaMapper.clear(mfa.getId());
        }
        recoveryCodeMapper.revokeAvailable(tenantId(), userId, LocalDateTime.now(clock));
        persistentSessionService.revokeAllForUser(userId, reason == null ? "MFA_RESET" : reason);
    }

    private List<String> issueRecoveryCodes(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        recoveryCodeMapper.revokeAvailable(tenantId(), userId, now);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < properties.getRecoveryCodeCount(); i++) {
            String code = TotpUtil.randomRecoveryCode();
            MfaRecoveryCode entity = new MfaRecoveryCode();
            entity.setTenantId(tenantId());
            entity.setUserId(userId);
            entity.setCodeHash(SecurityHashUtil.sha256(normalizeCode(code)));
            entity.setHashAlgorithm("SHA-256");
            recoveryCodeMapper.insert(entity);
            codes.add(code);
        }
        return codes;
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

    private UserMfa findMfa(Long userId) {
        return userMfaMapper.selectOne(new LambdaQueryWrapper<UserMfa>()
                .eq(UserMfa::getTenantId, tenantId())
                .eq(UserMfa::getUserId, userId));
    }

    private SysUser requireActiveUser(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw BusinessException.of(404, "error.user.notFound");
        }
        return user;
    }

    private String tenantId() {
        return StringUtils.hasText(oidcProperties.getTenantId()) ? oidcProperties.getTenantId() : "default";
    }

    private long currentStep() {
        return java.time.Instant.now(clock).getEpochSecond() / properties.getPeriodSeconds();
    }

    private String normalizeCode(String code) {
        return code == null ? null : code.trim().replace(" ", "").toUpperCase(java.util.Locale.ROOT);
    }

    private String encrypt(String secret) {
        try {
            byte[] iv = new byte[12];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return KEY_VERSION + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
                    + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("MFA secretの暗号化に失敗しました", e);
        }
    }

    private String decrypt(String value) {
        try {
            String[] parts = value.split(":", 3);
            if (parts.length != 3 || !KEY_VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("MFA secretのkey versionが不正です");
            }
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS,
                    Base64.getUrlDecoder().decode(parts[1])));
            return new String(cipher.doFinal(Base64.getUrlDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("MFA secretの復号に失敗しました", e);
        }
    }

    private SecretKeySpec key() {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(properties.getEncryptionKey().getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(bytes, "AES");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }

    private String otpauthUri(String username, String secret) {
        String issuer = properties.getIssuer();
        return "otpauth://totp/" + encode(issuer + ":" + username)
                + "?secret=" + secret + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + properties.getDigits()
                + "&period=" + properties.getPeriodSeconds();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
