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
 * PasswordEncoder を「明示プロファイル」に基づき fail-closed で解決する（ACC-SEC-P1-007 / REV-P1-006）。
 *
 * <p>従来は {@code @Profile("!prod")} で NoOp（平文）を既定にしていたため、本番デプロイで
 * {@code SPRING_PROFILES_ACTIVE=prod} を付け忘れる／{@code application.yml} の既定 {@code dev} が
 * そのまま効くと、平文パスワードエンコーダーで静かに起動してしまう危険があった。
 *
 * <p>本クラスは次のルールで解決する:
 * <ul>
 *   <li>{@code prod} が有効: BCrypt（{@code dev}/{@code test} 併用時も prod を優先し、平文へフォールバックしない）。</li>
 *   <li>{@code prod} 以外で、明示的に {@code dev} または {@code test} が有効: NoOp（平文。開発・テスト専用）。</li>
 *   <li>プロファイル未指定、または {@code prod}/{@code dev}/{@code test} のいずれでもない値のみ
 *       （例: "production" などの prod もどき、"staging" 等）: 起動を失敗させる（fail-closed）。</li>
 * </ul>
 *
 * <p>本番デプロイでは必ず {@code SPRING_PROFILES_ACTIVE=prod} を設定すること。
 * ローカル開発は {@code dev}、テストは {@code test} を明示的に有効化すること
 * （{@code mvn spring-boot:run} は pom の spring-boot-maven-plugin 設定で dev を付与する）。
 */
@Configuration
public class PasswordEncoderConfig {

    static final String PROFILE_PROD = "prod";
    static final String PROFILE_DEV = "dev";
    static final String PROFILE_TEST = "test";

    @Bean
    public PasswordEncoder passwordEncoder(Environment environment) {
        return resolve(environment.getActiveProfiles());
    }

    /**
     * 有効プロファイルから PasswordEncoder を決定する。テストから直接呼べるよう static + package-private。
     *
     * @param activeProfiles Environment#getActiveProfiles の値
     * @return prod は BCrypt、明示 dev/test は NoOp
     * @throws IllegalStateException 安全に決定できない（未指定・不明・prod もどき）場合
     */
    @SuppressWarnings("deprecation") // 開発・テスト用の平文シードとの互換性のため NoOp を明示利用する。
    static PasswordEncoder resolve(String[] activeProfiles) {
        Set<String> profiles = new LinkedHashSet<>();
        if (activeProfiles != null) {
            for (String profile : activeProfiles) {
                if (profile != null && !profile.isBlank()) {
                    profiles.add(profile.trim());
                }
            }
        }
        boolean prod = profiles.contains(PROFILE_PROD);
        boolean dev = profiles.contains(PROFILE_DEV);
        boolean test = profiles.contains(PROFILE_TEST);

        // prod が有効なら常に BCrypt。dev/test 併用時も prod を優先し、平文へフォールバックしない。
        if (prod) {
            return new BCryptPasswordEncoder();
        }
        // prod 以外で、明示的な dev / test のときのみ平文（NoOp）を許可する。
        if (dev || test) {
            return NoOpPasswordEncoder.getInstance();
        }
        // プロファイル未指定・不明（"production" 等の prod もどきを含む）は起動を失敗させる（fail-closed）。
        throw new IllegalStateException(
                "PasswordEncoder を安全に決定できません。明示的な 'prod'（本番: BCrypt）または "
                        + "'dev' / 'test'（開発・テスト: 平文）プロファイルを有効化してください。"
                        + " 現在の active profiles=" + profiles
                        + "。本番デプロイでは SPRING_PROFILES_ACTIVE=prod を必ず設定してください。");
    }
}
