package com.ses.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PasswordEncoder を「明示プロファイル」に基づき fail-closed で解決する（ACC-SEC-P1-007 / REV-P1-006 / REV-B2-P1-003）。
 *
 * <p>ルール:
 * <ul>
 *   <li>{@code prod} 単独: BCrypt。{@code dev}/{@code test}/その他との併用は起動失敗（fail-closed）。</li>
 *   <li>{@code prod} 以外で明示 {@code dev} または {@code test}: NoOp（平文。開発・テスト専用）。</li>
 *   <li>未指定・不明値のみ（{@code production} もどき等）: 起動失敗。</li>
 *   <li>{@code prod} 時の Flyway locations に {@code migration-dev} が含まれていれば起動失敗。
 *       locations が設定されている場合は {@code migration-prod} を必須とする。</li>
 * </ul>
 */
@Configuration
public class PasswordEncoderConfig {

    static final String PROFILE_PROD = "prod";
    static final String PROFILE_DEV = "dev";
    static final String PROFILE_TEST = "test";

    @Bean
    public PasswordEncoder passwordEncoder(Environment environment) {
        assertProfileAndFlywaySafety(environment);
        return resolve(environment.getActiveProfiles());
    }

    /**
     * 有効プロファイルから PasswordEncoder を決定する。テストから直接呼べるよう static + package-private。
     */
    @SuppressWarnings("deprecation")
    static PasswordEncoder resolve(String[] activeProfiles) {
        Set<String> profiles = normalizeProfiles(activeProfiles);
        boolean prod = profiles.contains(PROFILE_PROD);
        boolean dev = profiles.contains(PROFILE_DEV);
        boolean test = profiles.contains(PROFILE_TEST);

        if (prod) {
            if (profiles.size() > 1 || dev || test) {
                throw new IllegalStateException(
                        "prod プロファイルは dev/test および他プロファイルと併用できません（fail-closed）。"
                                + " 現在の active profiles=" + profiles
                                + "。本番では SPRING_PROFILES_ACTIVE=prod のみを設定してください。");
            }
            return new BCryptPasswordEncoder();
        }
        if (dev || test) {
            return NoOpPasswordEncoder.getInstance();
        }
        throw new IllegalStateException(
                "PasswordEncoder を安全に決定できません。明示的な 'prod'（本番: BCrypt）または "
                        + "'dev' / 'test'（開発・テスト: 平文）プロファイルを有効化してください。"
                        + " 現在の active profiles=" + profiles
                        + "。本番デプロイでは SPRING_PROFILES_ACTIVE=prod を必ず設定してください。");
    }

    /**
     * prod 混在と Flyway locations の安全条件を検証する（REV-B2-P1-003）。
     */
    static void assertProfileAndFlywaySafety(Environment environment) {
        Set<String> profiles = normalizeProfiles(environment.getActiveProfiles());
        boolean prod = profiles.contains(PROFILE_PROD);
        if (prod && profiles.size() > 1) {
            throw new IllegalStateException(
                    "prod プロファイルは他プロファイルと併用できません（fail-closed）。"
                            + " active profiles=" + profiles);
        }
        if (!prod) {
            return;
        }
        String locations = environment.getProperty("spring.flyway.locations", "");
        if (locations == null) {
            locations = "";
        }
        String normalized = Arrays.stream(locations.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(","));
        if (normalized.contains("migration-dev")) {
            throw new IllegalStateException(
                    "prod プロファイルで Flyway locations に migration-dev が含まれています（fail-closed）: "
                            + locations);
        }
        if (!normalized.isEmpty() && !normalized.contains("migration-prod")) {
            throw new IllegalStateException(
                    "prod プロファイルの Flyway locations には migration-prod が必要です（fail-closed）: "
                            + locations);
        }
    }

    private static Set<String> normalizeProfiles(String[] activeProfiles) {
        Set<String> profiles = new LinkedHashSet<>();
        if (activeProfiles != null) {
            for (String profile : activeProfiles) {
                if (profile != null && !profile.isBlank()) {
                    profiles.add(profile.trim());
                }
            }
        }
        return profiles;
    }
}
