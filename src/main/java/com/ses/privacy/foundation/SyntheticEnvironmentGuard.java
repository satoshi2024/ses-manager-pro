package com.ses.privacy.foundation;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.Objects;

/** 信頼された起動profileを読み、audience claimから実行環境を選択させない。 */
public final class SyntheticEnvironmentGuard {
    public static final String SYNTHETIC_AUDIENCE = "SYNTHETIC";
    private final Environment environment;

    public SyntheticEnvironmentGuard(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public void requireSyntheticAllowed(String claimedAudience) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new SecurityException("prod profileでは合成承認を拒否します");
        }
        if (!SYNTHETIC_AUDIENCE.equals(claimedAudience)) {
            throw new SecurityException("このverifierは合成audienceのみ受け付けます");
        }
    }
}
