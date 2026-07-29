package com.ses.config;

import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 本番起動時に、OIDC・break-glass・暗号鍵のfail-closed設定を検証する。 */
@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProductionSecurityConfigurationValidator {

    private static final String DEFAULT_MFA_KEY = "dev-only-change-this-mfa-key";
    private static final String DEFAULT_SESSION_KEY = "dev-only-change-this-session-key";
    private static final String ADMIN_ROLE = "管理者";

    private final OidcSecurityProperties oidcProperties;
    private final MfaSecurityProperties mfaProperties;
    private final PersistentSessionProperties sessionProperties;
    private final SysUserMapper sysUserMapper;

    @PostConstruct
    public void validate() {
        List<String> errors = new ArrayList<>();
        if (!oidcProperties.isEnabled()) {
            errors.add("OIDCを有効化してください");
        }
        if (oidcProperties.isLocalLoginEnabled()) {
            errors.add("通常local loginを無効化してください");
        }
        if (!oidcProperties.isBreakGlassLoginEnabled()) {
            errors.add("break-glass login入口を有効化してください");
        }
        validateBreakGlassUsers(errors);
        if (!mfaProperties.isEnabled() || !mfaProperties.isRequiredForBreakGlass()) {
            errors.add("break-glass MFAを必須化してください");
        }
        if (!oidcProperties.isRequireMfaAssurance()) {
            errors.add("OIDCのMFA assurance claim検証を有効化してください");
        }
        if (sessionProperties.isAllowUntrackedSessions()) {
            errors.add("本番では未追跡sessionを許可しないでください");
        }
        validateMfaKeyring(errors);
        validateSecret(errors, sessionProperties.getHashKey(), DEFAULT_SESSION_KEY, "session hash key");
        if (!errors.isEmpty()) {
            throw new IllegalStateException("本番security configurationが安全な既定値を満たしません: "
                    + String.join("; ", errors));
        }
    }

    private void validateMfaKeyring(List<String> errors) {
        String version = StringUtils.hasText(mfaProperties.getCurrentKeyVersion())
                ? mfaProperties.getCurrentKeyVersion() : "v1";
        String currentKey = mfaProperties.getKeyring() == null ? null : mfaProperties.getKeyring().get(version);
        if ("v1".equals(version) && !StringUtils.hasText(currentKey)) {
            currentKey = mfaProperties.getEncryptionKey();
        }
        validateSecret(errors, currentKey, DEFAULT_MFA_KEY, "MFA current encryption key");
        if (mfaProperties.getKeyring() != null) {
            mfaProperties.getKeyring().forEach((keyVersion, key) -> {
                if (!StringUtils.hasText(keyVersion) || !StringUtils.hasText(key) || key.length() < 32) {
                    errors.add("MFA keyringの各versionは32文字以上の鍵が必要です");
                }
            });
        }
    }

    private void validateBreakGlassUsers(List<String> errors) {
        Set<String> usernames = normalizedUsernames(oidcProperties.getBreakGlassUsernames());
        if (usernames.size() != 2) {
            errors.add("有効なbreak-glass管理者usernameをちょうど2件設定してください");
            return;
        }
        for (String username : usernames) {
            SysUser user = sysUserMapper.selectByUsername(username);
            if (user == null || !ADMIN_ROLE.equals(user.getRole()) || !Integer.valueOf(1).equals(user.getStatus())) {
                errors.add("break-glass usernameは有効な管理者ユーザーでなければなりません: " + username);
            }
        }
    }

    private Set<String> normalizedUsernames(Set<String> usernames) {
        Set<String> result = new LinkedHashSet<>();
        if (usernames != null) {
            usernames.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(result::add);
        }
        return result;
    }

    private void validateSecret(List<String> errors, String value, String defaultValue, String name) {
        if (!StringUtils.hasText(value) || defaultValue.equals(value) || value.length() < 32) {
            errors.add(name + "を32文字以上の非開発用値へ変更してください");
        }
    }
}
