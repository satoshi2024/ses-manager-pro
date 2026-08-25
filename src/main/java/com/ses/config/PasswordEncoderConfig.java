package com.ses.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * PasswordEncoder を「明示プロファイル」に基づき fail-closed で解決する
 * （ACC-SEC-P1-007 / REV-P1-006 / REV-B2-P1-003 / REV-B2.1-P1-002）。
 *
 * <p>ルール:
 * <ul>
 *   <li>{@code prod} 単独: BCrypt。他プロファイル併用は起動失敗。</li>
 *   <li>{@code prod} 以外で明示 {@code dev}/{@code test}: NoOp。</li>
 *   <li>未指定・不明値: 起動失敗。</li>
 *   <li>{@code prod} 時の {@code spring.flyway.locations} は必須（欠損・空・空白は失敗）。
 *       カンマ分割した<strong>正確な</strong> location 集合が
 *       {@code classpath:db/migration-prod} を含み、
 *       {@code classpath:db/migration-dev} を含んではならない（substring 判定禁止）。</li>
 * </ul>
 */
@Configuration
public class PasswordEncoderConfig {

    static final String PROFILE_PROD = "prod";
    static final String PROFILE_DEV = "dev";
    static final String PROFILE_TEST = "test";
    static final String FLYWAY_LOCATION_PROD = "classpath:db/migration-prod";
    static final String FLYWAY_LOCATION_DEV = "classpath:db/migration-dev";

    @Bean
    public PasswordEncoder passwordEncoder(Environment environment) {
        assertProfileAndFlywaySafety(environment);
        return resolve(environment.getActiveProfiles());
    }

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
     * prod 混在と Flyway locations の安全条件を検証する（REV-B2.1-P1-002）。
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
        // Environment#getProperty は未設定時 null。空文字・空白も拒否する。
        if (!environment.containsProperty("spring.flyway.locations")) {
            throw new IllegalStateException(
                    "prod プロファイルでは spring.flyway.locations が必須です（欠損は fail-closed）。");
        }
        String locations = environment.getProperty("spring.flyway.locations");
        Set<String> parsed = parseFlywayLocations(locations);
        if (!parsed.contains(FLYWAY_LOCATION_PROD)) {
            throw new IllegalStateException(
                    "prod の Flyway locations は正確に '" + FLYWAY_LOCATION_PROD
                            + "' を含める必要があります（fail-closed）: " + locations);
        }
        if (parsed.contains(FLYWAY_LOCATION_DEV)) {
            throw new IllegalStateException(
                    "prod の Flyway locations に正確な '" + FLYWAY_LOCATION_DEV
                            + "' を含めてはいけません（fail-closed）: " + locations);
        }
    }

    /**
     * カンマ区切り locations を trim 済みの正確な集合へ解析する。
     * 欠損・null・空白のみ・要素が空の場合は失敗する。substring 判定は行わない。
     */
    static Set<String> parseFlywayLocations(String locations) {
        if (locations == null || locations.isBlank()) {
            throw new IllegalStateException(
                    "prod プロファイルでは spring.flyway.locations が必須です（空/空白は fail-closed）。");
        }
        Set<String> set = new LinkedHashSet<>();
        for (String part : locations.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            set.add(trimmed);
        }
        if (set.isEmpty()) {
            throw new IllegalStateException(
                    "prod プロファイルでは spring.flyway.locations が必須です（空/空白は fail-closed）。");
        }
        return set;
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
