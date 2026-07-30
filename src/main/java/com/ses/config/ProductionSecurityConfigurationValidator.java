package com.ses.config;

import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserMfaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.net.URI;
import java.net.URISyntaxException;

/** 本番起動時に、OIDC・break-glass・暗号鍵のfail-closed設定を検証する。 */
@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProductionSecurityConfigurationValidator implements ApplicationRunner {

    private static final String DEFAULT_MFA_KEY = "dev-only-change-this-mfa-key";
    private static final String DEFAULT_SESSION_KEY = "dev-only-change-this-session-key";
    private static final String ADMIN_ROLE = "管理者";

    private final OidcSecurityProperties oidcProperties;
    private final MfaSecurityProperties mfaProperties;
    private final PersistentSessionProperties sessionProperties;
    private final SysUserMapper sysUserMapper;
    private final UserMfaMapper userMfaMapper;
    private final com.ses.mapper.PermissionGroupMapper permissionGroupMapper;

    @Override
    public void run(ApplicationArguments args) {
        validate();
    }

    public void validate() {
        List<String> errors = new ArrayList<>();
        if (!oidcProperties.isEnabled()) {
            errors.add("OIDCを有効化してください");
        }
        validateOidcMetadata(errors);
        if (oidcProperties.isLocalLoginEnabled()) {
            errors.add("通常local loginを無効化してください");
        }
        if (!oidcProperties.isBreakGlassLoginEnabled()) {
            errors.add("break-glass login入口を有効化してください");
        }
        validateBreakGlassUsers(errors);
        validateDefaultPermissionGroups(errors);
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

    private void validateDefaultPermissionGroups(List<String> errors) {
        String tenantId = StringUtils.hasText(oidcProperties.getTenantId()) ? oidcProperties.getTenantId() : "default";
        List<String> requiredGroupKeys = List.of("role-admin", "role-sales", "role-hr", "role-manager", "role-member");
        for (String groupKey : requiredGroupKeys) {
            Long count = permissionGroupMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ses.entity.PermissionGroup>()
                    .eq(com.ses.entity.PermissionGroup::getTenantId, tenantId)
                    .eq(com.ses.entity.PermissionGroup::getGroupKey, groupKey)
                    .eq(com.ses.entity.PermissionGroup::getEnabled, 1));
            if (count == null || count == 0) {
                errors.add("設定tenant(" + tenantId + ")にdefault permission groupが存在しません: " + groupKey);
            }
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
                continue;
            }
            if (userMfaMapper.countEnrolled(oidcProperties.getTenantId(), user.getId()) != 1) {
                errors.add("break-glass usernameはMFA enrollment完了済みでなければなりません: " + username);
            }
        }
    }

    private void validateOidcMetadata(List<String> errors) {
        if (!StringUtils.hasText(oidcProperties.getIssuerUri())
                || !StringUtils.hasText(oidcProperties.getAuthorizationUri())
                || !StringUtils.hasText(oidcProperties.getTokenUri())
                || !StringUtils.hasText(oidcProperties.getJwkSetUri())
                || !StringUtils.hasText(oidcProperties.getUserInfoUri())
                || !StringUtils.hasText(oidcProperties.getClientId())
                || !StringUtils.hasText(oidcProperties.getClientSecret())) {
            errors.add("OIDCの固定provider metadataとclient credentialを設定してください");
        }
        validateHttpsUri(errors, oidcProperties.getIssuerUri(), "issuer-uri");
        validateHttpsUri(errors, oidcProperties.getAuthorizationUri(), "authorization-uri");
        validateHttpsUri(errors, oidcProperties.getTokenUri(), "token-uri");
        validateHttpsUri(errors, oidcProperties.getJwkSetUri(), "jwk-set-uri");
        validateHttpsUri(errors, oidcProperties.getUserInfoUri(), "user-info-uri");
    }

    private void validateHttpsUri(List<String> errors, String value, String name) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getHost())
                    || uri.getUserInfo() != null) {
                errors.add("OIDC " + name + "はuserinfoを含まない有効なHTTPS URLでなければなりません");
            }
        } catch (URISyntaxException e) {
            errors.add("OIDC " + name + "はuserinfoを含まない有効なHTTPS URLでなければなりません");
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
