package com.ses.config;

import lombok.Getter;
import lombok.Setter;
import java.util.LinkedHashMap;
import java.util.Map;
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
    /** user/session/sourceごとの試行制限。 */
    private int maxAttempts = 5;
    private int attemptWindowSeconds = 300;
    private int lockSeconds = 900;
    /** 新規暗号化に使う鍵version。既存payloadのversionとは独立にrotationできる。 */
    private String currentKeyVersion = "v1";
    /** version→原文鍵。設定値・ログ・DBへsecretを出力しない。 */
    private Map<String, String> keyring = new LinkedHashMap<>();
    private String encryptionKey = "dev-only-change-this-mfa-key";
}
