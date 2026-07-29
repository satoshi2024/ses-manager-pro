package com.ses.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 永続sessionのidle/max lifetimeと同時session上限。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.security.session")
public class PersistentSessionProperties {

    private boolean enabled = true;
    private int idleTimeoutMinutes = 30;
    private int maxLifetimeHours = 12;
    private int maxConcurrentSessions = 5;
    private String hashKey = "dev-only-change-this-session-key";
}
