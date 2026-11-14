package com.ses.config;

import com.ses.common.security.OutboundUrlException;
import com.ses.common.security.OutboundUrlGuard;
import com.ses.entity.SysUser;
import com.ses.mapper.IdentityProviderMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserExternalIdentityMapper;
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

/**
 * 本番起動時に、OIDC・break-glass・暗号鍵のfail-closed設定を検証する。
 *
 * <p>本番初回の期限付きbootstrap mode（{@link ProductionBootstrapMode}）の有効期間中だけ、break-glass管理者の
 * 登録検査（ちょうど2件・有効な管理者・MFA登録済み）と、OIDCでログインできる管理者の人数検査を省略し、
 * 代わりにbootstrapの前提を検査する。通常local loginの無効化を含むその他の検査は常に行い、
 * bootstrapを無効にすれば完全な検査へ戻る。
 *
 * <p>bootstrapを無効にした起動で完全な検査が通ったら、初回bootstrapの完了marker
 * （{@link ProductionBootstrapCompletionLatch}）を書く（冪等。review P2-A）。書けなければ起動を拒否する（fail-closed）。
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProductionSecurityConfigurationValidator implements ApplicationRunner {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ProductionSecurityConfigurationValidator.class);

    private static final String DEFAULT_MFA_KEY = "dev-only-change-this-mfa-key";
    private static final String DEFAULT_SESSION_KEY = "dev-only-change-this-session-key";
    private static final String ADMIN_ROLE = "管理者";
    private static final String OIDC_PROVIDER_TYPE = "OIDC";

    /**
     * break-glass incidentの起票（1名）と、起票者以外の互いに異なる承認（2名）に要る管理者の人数
     * （{@code BreakGlassServiceImpl}）。break-glass usernameのOIDC sessionは失効させられるため、
     * break-glass username以外でOIDCでログインできる管理者がこの人数以上必要。
     */
    static final int MIN_OIDC_ADMINISTRATORS = 3;

    /** Gemini APIの許可ホスト（GeminiTextServiceImplと同一のallowlist） */
    private static final Set<String> ALLOWED_AI_HOSTS = Set.of("generativelanguage.googleapis.com");

    private final OidcSecurityProperties oidcProperties;
    private final MfaSecurityProperties mfaProperties;
    private final PersistentSessionProperties sessionProperties;
    private final SysUserMapper sysUserMapper;
    private final UserMfaMapper userMfaMapper;
    private final com.ses.mapper.PermissionGroupMapper permissionGroupMapper;
    private final AiConfig aiConfig;
    private final OutboundUrlGuard outboundUrlGuard;

    /**
     * 本番初回の期限付きbootstrap mode。有効期間中だけbreak-glass登録の検査を省略する。
     * 不在・無効・期限切れなら完全な検査を行う（fail-closed）。
     */
    private ProductionBootstrapMode productionBootstrapMode;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProductionBootstrapMode(ProductionBootstrapMode productionBootstrapMode) {
        this.productionBootstrapMode = productionBootstrapMode;
    }

    /** 初回bootstrapの完了marker。不在なら完全な検査が通っても起動を拒否する（fail-closed）。 */
    private ProductionBootstrapCompletionLatch bootstrapCompletionLatch;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBootstrapCompletionLatch(ProductionBootstrapCompletionLatch bootstrapCompletionLatch) {
        this.bootstrapCompletionLatch = bootstrapCompletionLatch;
    }

    /** OIDCでログインできる管理者の人数検査に使う。不在なら完全な検査で拒否する（fail-closed）。 */
    private IdentityProviderMapper identityProviderMapper;
    private UserExternalIdentityMapper userExternalIdentityMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setIdentityProviderMapper(IdentityProviderMapper identityProviderMapper) {
        this.identityProviderMapper = identityProviderMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUserExternalIdentityMapper(UserExternalIdentityMapper userExternalIdentityMapper) {
        this.userExternalIdentityMapper = userExternalIdentityMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        validate();
    }

    public void validate() {
        boolean bootstrap = productionBootstrapMode != null && productionBootstrapMode.isActive();
        List<String> errors = new ArrayList<>();
        if (!oidcProperties.isEnabled()) {
            errors.add("OIDCを有効化してください");
        }
        // OIDC login（OidcLoginUserService）・外部identityの登録と承認・MFAとsessionのtenantは、全てこの値で照合する。
        // 未設定でも従来は "default" と見なして検査が通ったが、実際のOIDC loginはproviderを解決できず全員が
        // ログインできなくなる（break-glassの起票もできない）。初回だけでなく常に必須（review P2-B）。
        if (!StringUtils.hasText(oidcProperties.getTenantId())) {
            errors.add("OIDCのtenant-id（OIDC_TENANT_ID）を設定してください（OIDC login・外部identity・MFAの照合に常に必要）");
        }
        validateOidcMetadata(errors);
        // bootstrap中も通常local loginの無効化は必須。初期管理者adminのlocal loginだけは
        // ProductionBootstrapModeが期限付きで開ける（他のユーザーのlocal loginは開かない）。
        if (oidcProperties.isLocalLoginEnabled()) {
            errors.add("通常local loginを無効化してください");
        }
        if (!oidcProperties.isBreakGlassLoginEnabled()) {
            errors.add("break-glass login入口を有効化してください");
        }
        if (bootstrap) {
            validateBootstrap(errors);
        } else {
            validateBreakGlassUsers(errors);
            validateOidcAdministrators(errors);
        }
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
        validateAiApiUrl(errors);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("本番security configurationが安全な既定値を満たしません: "
                    + String.join("; ", errors));
        }
        if (productionBootstrapMode == null || !productionBootstrapMode.isRequested()) {
            markBootstrapCompleted();
        }
    }

    /**
     * bootstrapを無効にした起動で完全な検査が通った時点を、初回bootstrapの完了として記録する（冪等）。
     * 記録できなければ起動を拒否する。理由: 記録が無いままだと、本番稼働後のbootstrap再開（承認不要のbreak-glassに等しい）が
     * 承認ticketも監査も無く行えてしまい、しかも誰も気づけない。書込は初回だけで、以後の起動は1回の参照で済む。
     */
    private void markBootstrapCompleted() {
        if (bootstrapCompletionLatch == null) {
            throw new IllegalStateException("[bootstrap] 初回bootstrapの完了markerを記録できないため起動しません"
                    + "（ProductionBootstrapCompletionLatchが利用できません）");
        }
        try {
            bootstrapCompletionLatch.markCompletedIfAbsent();
        } catch (RuntimeException e) {
            log.error("[bootstrap] 初回bootstrapの完了marker（m_system_config: {}）を記録できないため起動を中止します",
                    ProductionBootstrapCompletionLatch.CONFIG_KEY, e);
            bootstrapCompletionLatch.recordCompletionFailure();
            throw new IllegalStateException("[bootstrap] 初回bootstrapの完了markerを記録できないため起動しません", e);
        }
    }

    /**
     * break-glass incidentを起票・承認できる管理者が居ることを検査する（完全な検査だけ）。
     * 「OIDCでログインできる」は {@code OidcLoginUserService} のDB側の照合条件（設定tenant・issuerの有効なOIDC providerに
     * 対するAPPROVEDの外部identity linkを持つ、有効な管理者）で判定する。IdP側のaccount状態やMFA assuranceは起動時に
     * 確認できないため見ない（必要条件の検査）。break-glass usernameはOIDC sessionが失効させられるため数えない。
     */
    private void validateOidcAdministrators(List<String> errors) {
        String tenantId = trimmed(oidcProperties.getTenantId());
        String issuer = trimmed(oidcProperties.getIssuerUri());
        if (tenantId == null || issuer == null) {
            // tenant-id・issuer-uriの未設定は別の検査でエラーにしている
            return;
        }
        if (identityProviderMapper == null || userExternalIdentityMapper == null) {
            errors.add("OIDCでログインできる管理者の人数を確認できません");
            return;
        }
        com.ses.entity.IdentityProvider provider = identityProviderMapper.selectEnabledByTenantAndIssuer(tenantId, issuer);
        if (provider == null || provider.getId() == null || !OIDC_PROVIDER_TYPE.equalsIgnoreCase(provider.getProviderType())) {
            errors.add("設定tenant・issuerの有効なOIDC providerが登録されていません（m_identity_provider）");
            return;
        }
        List<String> linked = userExternalIdentityMapper.selectApprovedActiveUsernamesByRole(
                tenantId, provider.getId(), ADMIN_ROLE);
        Set<String> administrators = new java.util.TreeSet<>();
        if (linked != null) {
            linked.stream().filter(StringUtils::hasText).map(String::trim).forEach(administrators::add);
        }
        administrators.removeAll(normalizedUsernames(oidcProperties.getBreakGlassUsernames()));
        if (administrators.size() < MIN_OIDC_ADMINISTRATORS) {
            errors.add("OIDCでログインできる有効な管理者（break-glass username以外）が" + MIN_OIDC_ADMINISTRATORS
                    + "名以上必要です（break-glass incidentの起票1名・承認2名。現在" + administrators.size() + "名）");
        }
    }

    private static String trimmed(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * ai.provider=gemini かつ ai.api-url が指定されている場合、
     * 本番起動時にallowlist（HTTPS/ホスト完全一致/443）へ適合するか検証する（送信時と同一規則でfail-closed）。
     */
    private void validateAiApiUrl(List<String> errors) {
        if (aiConfig == null || !"gemini".equalsIgnoreCase(aiConfig.getProvider())) {
            return;
        }
        String apiUrl = aiConfig.getApiUrl();
        if (!StringUtils.hasText(apiUrl)) {
            // 未指定なら既定のGeminiエンドポイントを使うため検証不要。
            return;
        }
        try {
            outboundUrlGuard.validateExactHostHttpsUrl(apiUrl, ALLOWED_AI_HOSTS);
        } catch (OutboundUrlException e) {
            errors.add("ai.api-urlは許可されたGeminiエンドポイントでなければなりません: " + e.getMessage());
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

    /**
     * bootstrap中の代替検査。break-glass管理者（2件・MFA登録済み）の検査は省略するが、
     * <ul>
     *   <li>break-glass usernameは未設定であること（初期管理者のbootstrap loginがbreak-glass扱いになる衝突と、
     *       OIDCでMFAを登録する予定の管理者のsessionがincident未束縛として失効させられることを防ぐ。
     *       break-glass usernameはbootstrapを無効にする再起動で設定する）</li>
     *   <li>bootstrap用の初期管理者が有効な管理者として存在すること</li>
     * </ul>
     * を要求する。
     */
    private void validateBootstrap(List<String> errors) {
        log.warn("[bootstrap] 期限付きbootstrap mode中のため、break-glass管理者の登録検査とOIDC管理者の人数検査を"
                + "省略します（期限: {}、再開ticket: {}）", productionBootstrapMode.getExpiresAt(),
                productionBootstrapMode.getReopenTicket() == null ? "なし（初回）" : productionBootstrapMode.getReopenTicket());
        if (!normalizedUsernames(oidcProperties.getBreakGlassUsernames()).isEmpty()) {
            errors.add("bootstrap中はbreak-glass usernameを設定しないでください（bootstrapを無効にする再起動で設定します）");
        }
        SysUser bootstrapUser = sysUserMapper.selectByUsername(ProductionBootstrapMode.BOOTSTRAP_USERNAME);
        if (bootstrapUser == null || !ProductionBootstrapMode.BOOTSTRAP_USERNAME.equals(bootstrapUser.getUsername())
                || !ADMIN_ROLE.equals(bootstrapUser.getRole()) || !Integer.valueOf(1).equals(bootstrapUser.getStatus())) {
            errors.add("bootstrap用の初期管理者(" + ProductionBootstrapMode.BOOTSTRAP_USERNAME
                    + ")が有効な管理者として存在しません");
        }
    }

    private void validateBreakGlassUsers(List<String> errors) {
        Set<String> usernames = normalizedUsernames(oidcProperties.getBreakGlassUsernames());
        if (usernames.size() != 2) {
            errors.add("有効なbreak-glass管理者usernameをちょうど2件設定してください");
            return;
        }
        String tenantId = StringUtils.hasText(oidcProperties.getTenantId())
                ? oidcProperties.getTenantId().trim() : "default";
        for (String username : usernames) {
            SysUser user = sysUserMapper.selectByUsername(username);
            // selectByUsernameは大文字小文字を区別しないcollationで一致するが、実行時のbreak-glass判定
            // （OidcSecurityProperties.isBreakGlassUsername）は区別する。表記が違うまま通すと、break-glassが
            // 使えない、または通常管理者sessionになるため、DBのusernameと完全一致を要求する（D1 review N-1）。
            if (user == null || !username.equals(user.getUsername())
                    || !ADMIN_ROLE.equals(user.getRole()) || !Integer.valueOf(1).equals(user.getStatus())) {
                errors.add("break-glass usernameは有効な管理者ユーザーのusername（大文字小文字も一致）でなければなりません: "
                        + username);
                continue;
            }
            if (userMfaMapper.countEnrolled(tenantId, user.getId()) != 1) {
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
