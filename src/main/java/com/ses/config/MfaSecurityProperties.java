package com.ses.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ローカルbreak-glass向けMFA設定。secretの原文やrecovery codeは設定・ログへ出力しない。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.security.mfa")
public class MfaSecurityProperties {

    private boolean enabled = true;
    private boolean requiredForBreakGlass = true;
    private String issuer = "SES Manager Pro";
    private int digits = 6;
    private int periodSeconds = 30;
    private int clockSkewSteps = 1;
    private int recoveryCodeCount = 10;
    private String encryptionKey = "dev-only-change-this-mfa-key";
}
