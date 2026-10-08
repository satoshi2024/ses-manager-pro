package com.ses.privacy.foundation;

import org.springframework.core.env.Environment;

import java.util.Arrays;

/** W2b合成DBと鍵fixtureに共通するprofile境界。 */
final class W2bTestEnvironment {
    private W2bTestEnvironment() { }

    static void requireTest(Environment environment) {
        String[] active = environment.getActiveProfiles();
        String[] defaults = environment.getDefaultProfiles();
        if (!Arrays.asList(active).contains("test") || active.length == 0
                || Arrays.stream(active).anyMatch("prod"::equals)
                || Arrays.stream(defaults).anyMatch("prod"::equals)) {
            throw new SecurityException("W2b合成capabilityはprodを含まないactive test context専用です");
        }
    }
}
