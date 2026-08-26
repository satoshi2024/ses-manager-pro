package com.ses.config;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * Prometheus スクレイパー専用マシン認証の組み立て（REV-RP-P1-004 / ACC-OPS-P1-002）。
 *
 * <p>username / password が両方とも非空のときだけ {@link AuthenticationProvider} を生成する。
 * {@code AuthenticationProvider} を Spring {@code @Bean} として公開すると
 * {@link CustomUserDetailsService} の自動配線が欠落し得るため、
 * {@link SecurityConfig} から {@code HttpSecurity#authenticationProvider} へ明示登録する。
 * ロールは {@code ROLE_METRICS_SCRAPER} のみ（業務ロールは付与しない）。
 */
public final class MetricsScraperAuthConfig {

    private MetricsScraperAuthConfig() {
    }

    /** Spring Security のロール名（ROLE_ 接頭辞なし）。 */
    public static final String ROLE_METRICS_SCRAPER = "METRICS_SCRAPER";

    /**
     * 資格情報が揃っているときだけインメモリのスクレイパーユーザーを認証する Provider を返す。
     * 未設定時は null（SecurityConfig 側で登録しない）。
     * PasswordEncoder はプロファイルに従う（test/dev=NoOp、prod=BCrypt）。
     */
    public static AuthenticationProvider createAuthenticationProvider(
            MetricsScraperProperties properties,
            PasswordEncoder passwordEncoder) {
        if (properties == null || !properties.credentialsConfigured()) {
            return null;
        }
        UserDetails scraper = User.builder()
                .username(properties.getUsername())
                .password(passwordEncoder.encode(properties.getPassword()))
                .roles(ROLE_METRICS_SCRAPER)
                .build();
        // InMemoryUserDetailsManager はここでローカルに閉じ、UserDetailsService Bean にはしない
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(new InMemoryUserDetailsManager(scraper));
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }
}
