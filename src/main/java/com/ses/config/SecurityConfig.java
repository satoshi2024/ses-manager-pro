package com.ses.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import lombok.RequiredArgsConstructor;

/**
 * Spring Security 設定クラス
 * 認証・認可、ログイン、ログアウト、CSRF設定を管理する
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final MenuPermissionFilter menuPermissionFilter;

    /**
     * MenuPermissionFilterのServletコンテナへの自動登録を無効化する
     * （Spring Securityフィルタチェーン内で明示的に addFilterAfter するため、二重登録を防ぐ）
     */
    @Bean
    public FilterRegistrationBean<MenuPermissionFilter> disableAutoRegistration(MenuPermissionFilter filter) {
        FilterRegistrationBean<MenuPermissionFilter> registrationBean = new FilterRegistrationBean<>(filter);
        registrationBean.setEnabled(false);
        return registrationBean;
    }

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
            // ロール別メニューアクセス制御フィルター（認証フィルターの後、認可判定の前に実行）
            .addFilterAfter(menuPermissionFilter, UsernamePasswordAuthenticationFilter.class)
            // アクセス制御の設定
            .authorizeHttpRequests(auth -> auth
                // 認証不要のパス（ログインページ、静的リソース、認証API）
                .requestMatchers(
                    "/login",
                    "/error",
                    "/css/**",
                    "/js/**",
                    "/lib/**",
                    "/img/**",
                    "/api/auth/**"
                ).permitAll()
                // ユーザー管理・ロール権限設定は管理者のみアクセス可能
                .requestMatchers(
                    "/user/**",
                    "/api/users/**",
                    "/api/role-menus/**",
                    "/api/notifications/generate"
                ).hasRole("管理者")
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
     * パスワードエンコーダーの設定（開発環境）
     * 開発中は平文のまま比較する（既存の平文シードデータと整合させるため）
     *
     * @return PasswordEncoder 平文エンコーダー
     */
    @Bean
    @Profile("!prod")
    public PasswordEncoder passwordEncoder() {
        return NoOpPasswordEncoder.getInstance();
    }

    /**
     * パスワードエンコーダーの設定（本番環境）
     * 本番環境ではBCrypt方式でパスワードをハッシュ化する
     *
     * @return PasswordEncoder BCryptパスワードエンコーダー
     */
    @Bean
    @Profile("prod")
    public PasswordEncoder prodPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
