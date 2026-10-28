package com.ses.privacy.foundation;

import org.springframework.context.ApplicationContext;

import java.util.Arrays;

/** W2b合成DBと鍵fixtureに共通するprofile境界。 */
final class W2bTestEnvironment {
    private W2bTestEnvironment() { }

    static void requireTest(ApplicationContext context) {
        if (context == null) throw new SecurityException("W2bのSpring contextが必要です");
        org.springframework.core.env.Environment environment = context.getEnvironment();
        String[] active = environment.getActiveProfiles();
        String[] defaults = environment.getDefaultProfiles();
        if (!Arrays.asList(active).contains("test") || active.length == 0
                || Arrays.stream(active).anyMatch("prod"::equals)
                || Arrays.stream(defaults).anyMatch("prod"::equals)) {
            throw new SecurityException("W2b合成capabilityはprodを含まないactive test context専用です");
        }
    }
}
