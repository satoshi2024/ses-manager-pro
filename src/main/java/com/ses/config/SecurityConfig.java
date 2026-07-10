package com.ses.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Spring Security 設定クラス
 * 認証・認可、ログイン、ログアウト、CSRF設定を管理する
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * セキュリティフィルタチェーンの設定
     * アクセス制御、フォームログイン、ログアウト、CSRF設定を定義する
     *
     * @param http HttpSecurityオブジェクト
     * @return SecurityFilterChain フィルタチェーン
     * @throws Exception セキュリティ設定エラー
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // アクセス制御の設定
            .authorizeHttpRequests(auth -> auth
                // 認証不要のパス（ログインページ、静的リソース、認証API）
                .requestMatchers(
                    "/login",
                    "/css/**",
                    "/js/**",
                    "/lib/**",
                    "/img/**",
                    "/api/auth/**"
                ).permitAll()
                // その他のリクエストは認証が必要
                .anyRequest().authenticated()
            )
            // フォームログインの設定
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/dashboard", true)
                .failureUrl("/login?error")
                .permitAll()
            )
            // ログアウトの設定
            .logout(logout -> logout
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout"))
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            )
            // REST API向けにCSRFを無効化（/api/** パス）
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/**")
            );

        return http.build();
    }

    /**
     * パスワードエンコーダーの設定
     * BCrypt方式でパスワードをハッシュ化する
     *
     * @return PasswordEncoder BCryptパスワードエンコーダー
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return NoOpPasswordEncoder.getInstance();
    }
}
