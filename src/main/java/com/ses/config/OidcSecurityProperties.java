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

    /** falseの場合、break-glass usernames以外のform loginを拒否する。 */
    private boolean localLoginEnabled = true;

    private String tenantId = "default";
    private String providerRegistrationId = "enterprise";
    private String issuerUri;
    private String clientId;
    private String clientSecret;
    private Set<String> breakGlassUsernames = new LinkedHashSet<>();

    public boolean isBreakGlassUsername(String username) {
        return username != null && breakGlassUsernames != null && breakGlassUsernames.contains(username);
    }
}
