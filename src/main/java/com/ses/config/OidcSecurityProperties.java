package com.ses.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Locale;
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
     * break-glass username か。前後空白を除き、両辺を {@code toLowerCase(Locale.ROOT)} にしてから比べる（X3-2・NF07-A1J
     * review P3-2）。ASCII の大文字小文字の表記違い（{@code BG-Ops} と {@code bg-ops}）は {@code sys_user.username} の照合順序
     * （{@code utf8mb4_unicode_ci}）でも同じ行へ解決されるため、同じ口座として扱う。区別すると、login 時（入力値）と login 後
     * （DB の username）で判定が食い違い、break-glass 口座の session が incident の束縛・MFA の要求から外れ得る。
     *
     * <p>照合順序と等価ではない。{@code equalsIgnoreCase} は使わない: 文字ごとに大文字へも折り畳むため、無点の
     * {@code ı}（U+0131）を {@code I} と同一視し、DB では別の行になる {@code admın} を break-glass の {@code admin} として
     * 扱ってしまう（DOC-FINAL review P2-1）。逆に DB が同じ行とみなす非 ASCII の表記違い（全角・アクセント等）は概ね false
     * になり、拒否側に倒れる。ただし {@code toLowerCase(Locale.ROOT)} は ASCII だけを畳むのではなく、Unicode の小文字化で
     * ASCII になる非 ASCII 文字も畳む（例: U+212A KELVIN SIGN は {@code k} になる）。設定値は prod の起動時検査で ASCII
     * {@code [A-Za-z0-9._-]} に限られ、{@code sys_user.username} の一意制約（{@code utf8mb4_unicode_ci}）もこの表記を同じ名前と
     * みなすため、別の口座が break-glass 名に一致することは無い（FINAL review §3 #7）。
     *
     * <p>名前が一致しても別の口座へ解決されないよう、{@code BreakGlassServiceImpl} は解決した行の
     * username も同じ規則で設定値と一致することを要求する。prod の起動時検査は設定値と DB の表記の完全一致を別に要求する
     * （{@code ProductionSecurityConfigurationValidator}）。
     */
    public boolean isBreakGlassUsername(String username) {
        if (username == null || breakGlassUsernames == null) {
            return false;
        }
        String candidate = username.trim().toLowerCase(Locale.ROOT);
        if (candidate.isEmpty()) {
            return false;
        }
        return breakGlassUsernames.stream()
                .anyMatch(name -> name != null && name.trim().toLowerCase(Locale.ROOT).equals(candidate));
    }

    /**
     * DB に保存された {@code sys_user.username} が break-glass 名か。入力名（{@link #isBreakGlassUsername}）と違い、保存値の
     * 前後空白は除かない（DOC-FINAL delta review P3-a）: MySQL では先頭の空白に意味があり、{@code " admin"} は {@code admin}
     * と別の行になり得る。保存値をそのまま、設定値（前後空白を除いたもの）と {@code toLowerCase(Locale.ROOT)} で大文字小文字を
     * 区別せずに比べる（ASCII 以外でも小文字化で ASCII になる文字は畳む。{@link #isBreakGlassUsername} の説明）。
     * 解決した行が設定の口座かを確かめる {@code BreakGlassServiceImpl} が使う。
     */
    public boolean isBreakGlassStoredUsername(String storedUsername) {
        if (storedUsername == null || storedUsername.isEmpty() || breakGlassUsernames == null) {
            return false;
        }
        String stored = storedUsername.toLowerCase(Locale.ROOT);
        return breakGlassUsernames.stream()
                .filter(name -> name != null && !name.trim().isEmpty())
                .anyMatch(name -> name.trim().toLowerCase(Locale.ROOT).equals(stored));
    }
}
