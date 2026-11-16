package com.ses.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * OIDC/local loginの安全な既定値を一元管理する設定。
 * client secretは設定値として受け取るが、ログやDBへ出力しない。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.security.oidc")
public class OidcSecurityProperties {

    /** 本番でも明示的に有効化するまでOIDCを起動しない。 */
    private boolean enabled = false;

    /** falseの場合、通常のlocal username/password loginを拒否する。 */
    private boolean localLoginEnabled = true;

    /** 通常local loginを閉じても、明示したbreak-glass login入口を表示・許可する。 */
    private boolean breakGlassLoginEnabled = true;

    /** 未設定時はmulti-tenantの認証済みSysUser.tenant_idだけを信頼する。 */
    private String tenantId;
    private String providerRegistrationId = "enterprise";
    private String issuerUri;
    /** 起動時discoveryを行わず使用する固定metadata。 */
    private String authorizationUri;
    private String tokenUri;
    private String jwkSetUri;
    private String userInfoUri;
    private String clientId;
    private String clientSecret;
    /** token endpoint等の外部通信上限。startup discoveryは行わない。 */
    private int connectTimeoutMillis = 2000;
    private int readTimeoutMillis = 5000;
    private Set<String> breakGlassUsernames = new LinkedHashSet<>();
    /** IdP側でMFA assurance claimを要求する。prodではtrueを維持する。 */
    private boolean requireMfaAssurance = true;
    private Set<String> acceptedMfaAmr = new LinkedHashSet<>(Set.of("mfa", "otp", "fido", "fido2"));
    private Set<String> acceptedMfaAcr = new LinkedHashSet<>();

    /**
     * break-glass username か。{@code sys_user.username} の照合順序（{@code utf8mb4_unicode_ci}）は大文字小文字と末尾空白を
     * 区別せず、表記違いの入力も同じ行へ解決されるため、ここでも前後空白を除いて大文字小文字を区別せずに比べる
     * （{@code SecurityConfig} の HTTP Basic 防護と同じ比較。X3-2・NF07-A1J review P3-2）。区別すると、入力・設定・DB の
     * 表記が違うだけで経路ごとに判定が食い違う（login 時は入力値、login 後は DB の username で判定する）。その結果
     * break-glass 口座の session が incident の束縛・MFA の要求から外れ得る。prod の起動時検査は設定値と DB の表記の
     * 完全一致を別に要求する（{@code ProductionSecurityConfigurationValidator}）。
     */
    public boolean isBreakGlassUsername(String username) {
        if (username == null || breakGlassUsernames == null) {
            return false;
        }
        String candidate = username.trim();
        if (candidate.isEmpty()) {
            return false;
        }
        return breakGlassUsernames.stream()
                .anyMatch(name -> name != null && name.trim().equalsIgnoreCase(candidate));
    }
}
