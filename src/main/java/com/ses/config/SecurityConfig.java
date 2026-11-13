package com.ses.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.StringUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Set;
import lombok.RequiredArgsConstructor;

/**
 * Spring Security 設定クラス
 * 認証・認可、ログイン、ログアウト、CSRF設定を管理する
 */
@Configuration
@EnableWebSecurity
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** Prometheus scrape endpoint。HTTP Basicを受け付ける唯一の内部経路。 */
    static final String METRICS_ENDPOINT = "/actuator/prometheus";

    /** Prometheus Basic challengeのrealm名（業務chain・metrics chainで共通）。 */
    private static final String METRICS_REALM = "SES Metrics";

    @Value("${app.security.require-https:false}")
    private boolean requireHttps;

    private final MenuPermissionFilter menuPermissionFilter;
    private final ApiAuditFilter apiAuditFilter;
    private final LoginSuccessHandler loginSuccessHandler;
    private final LoginFailureHandler loginFailureHandler;
    private final OidcSecurityProperties oidcSecurityProperties;
    private final OidcLoginUserService oidcLoginUserService;
    private final OidcAuthenticationFailureHandler oidcAuthenticationFailureHandler;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrationRepositoryProvider;
    private final MfaEnforcementFilter mfaEnforcementFilter;
    private final PasswordChangeEnforcementFilter passwordChangeEnforcementFilter;
    private final PersistentSessionFilter persistentSessionFilter;
    private final InternalTenantContextFilter internalTenantContextFilter;
    private final ImportRawBodyTransportGuardFilter importRawBodyTransportGuardFilter;
    private final com.ses.service.AuditLogService auditLogService;
    private final com.ses.service.security.PersistentSessionService persistentSessionService;
    private final ObjectProvider<org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient<
            org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest>>
            oidcTokenResponseClientProvider;
    private final MetricsScraperProperties metricsScraperProperties;
    private final PasswordEncoder passwordEncoder;

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
     * ApiAuditFilterのServletコンテナへの自動登録を無効化する
     * （Spring Securityフィルタチェーン内で明示的に addFilterAfter するため、二重登録を防ぐ）
     */
    @Bean
    public FilterRegistrationBean<ApiAuditFilter> disableAuditAutoRegistration(ApiAuditFilter filter) {
        FilterRegistrationBean<ApiAuditFilter> registrationBean = new FilterRegistrationBean<>(filter);
        registrationBean.setEnabled(false);
        return registrationBean;
    }

    /** raw CSV guardはCSRFより前に一度だけ動かし、Servlet containerへの二重登録を防ぐ。 */
    @Bean
    public FilterRegistrationBean<ImportRawBodyTransportGuardFilter> disableImportRawBodyGuardAutoRegistration(
            ImportRawBodyTransportGuardFilter filter) {
        FilterRegistrationBean<ImportRawBodyTransportGuardFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * MfaEnforcementFilterのServletコンテナへの自動登録を無効化する
     * （Spring Securityフィルタチェーン内で addFilterAfter するため、二重登録を防ぐ）
     */
    @Bean
    public FilterRegistrationBean<MfaEnforcementFilter> disableMfaAutoRegistration(MfaEnforcementFilter filter) {
        FilterRegistrationBean<MfaEnforcementFilter> registrationBean = new FilterRegistrationBean<>(filter);
        registrationBean.setEnabled(false);
        return registrationBean;
    }

    /**
     * PasswordChangeEnforcementFilterのServletコンテナへの自動登録を無効化する
     * （Spring Securityフィルタチェーン内で MfaEnforcementFilter の後に addFilterAfter するため、二重登録を防ぐ）
     */
    @Bean
    public FilterRegistrationBean<PasswordChangeEnforcementFilter> disablePasswordChangeAutoRegistration(
            PasswordChangeEnforcementFilter filter) {
        FilterRegistrationBean<PasswordChangeEnforcementFilter> registrationBean = new FilterRegistrationBean<>(filter);
        registrationBean.setEnabled(false);
        return registrationBean;
    }

    /**
     * PersistentSessionFilterのServletコンテナへの自動登録を無効化する
     * （Spring Securityフィルタチェーン内で addFilterAfter するため、二重登録を防ぐ）
     */
    @Bean
    public FilterRegistrationBean<PersistentSessionFilter> disableSessionAutoRegistration(
            PersistentSessionFilter filter) {
        FilterRegistrationBean<PersistentSessionFilter> registrationBean = new FilterRegistrationBean<>(filter);
        registrationBean.setEnabled(false);
        return registrationBean;
    }

    /** 内部tenant context filterのServletコンテナへの自動登録を無効化する。 */
    @Bean
    public FilterRegistrationBean<InternalTenantContextFilter> disableInternalTenantAutoRegistration(
            InternalTenantContextFilter filter) {
        FilterRegistrationBean<InternalTenantContextFilter> registrationBean = new FilterRegistrationBean<>(filter);
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
        // 不変条件: 以下の自前filter（PersistentSession / InternalTenantContext / MenuPermission /
        // ApiAudit / MfaEnforcement）は「認証済み主体がSecurityContextに居る」ことを前提に検証し、
        // 認証nullなら未認証として素通りさせる。そのため本chainでは、これらより後ろで認証を確定させて
        // chainを継続するfilter（BasicAuthenticationFilter / RememberMe / Bearer等）を有効にしてはならない。
        // Spring Securityの既定順ではBasicAuthenticationFilterがUsernamePasswordAuthenticationFilterより後ろに
        // 置かれるため、ここで httpBasic を有効にすると上記の検証をすべて迂回した主体が静的role規則だけで
        // 業務APIへ到達する。Basicは metricsSecurityFilterChain（Prometheus scrape専用）だけで受け付ける。
        // HttpBasicChainBoundaryTest がこの不変条件を固定する。
        http
            .addFilterBefore(importRawBodyTransportGuardFilter, CsrfFilter.class)
            // 認証済みLoginUserのtenantを以降の内部Security/業務チェーン全体へ固定する
            .addFilterAfter(internalTenantContextFilter, UsernamePasswordAuthenticationFilter.class)
            // break-glassを認証主体・incident tenantで再検証してから、業務tenantを固定する。
            // persistent session filterはThreadLocalに依存しないため、InternalTenantContextFilterより前に置く。
            .addFilterBefore(persistentSessionFilter, InternalTenantContextFilter.class)
            // ロール別メニューアクセス制御フィルター（認証フィルターの後、認可判定の前に実行）
            .addFilterAfter(menuPermissionFilter, InternalTenantContextFilter.class)
            // API操作ログフィルター（メニュー権限フィルターの後に実行）
            .addFilterAfter(apiAuditFilter, MenuPermissionFilter.class)
            // break-glassのMFA未完了中はMFA endpoint以外を遮断
            .addFilterAfter(mfaEnforcementFilter, ApiAuditFilter.class)
            // 初回ログイン時のパスワード変更が済むまで、変更画面・変更API以外を遮断（MFAの後に判定する）
            .addFilterAfter(passwordChangeEnforcementFilter, MfaEnforcementFilter.class)
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
                    "/favicon.svg",
                    "/favicon.ico",
                    "/manifest.webmanifest",
                    "/service-worker.js",
                    "/offline.html",
                    "/api/auth/**",
                    "/oauth2/**",
                    "/login/oauth2/**",
                    "/api/webhooks/**",
                    // ===== Actuator: 監視/K8sプローブの3パスのみ匿名許可（ACC-OPS-P0-001） =====
                    // health と liveness/readiness プローブに限定する。/actuator/env 等は
                    // exposure で未公開かつここでも許可しないため、匿名では到達できない。
                    // /actuator/prometheus はここに含めない（ACC-OPS-P1-002: 認証必須）。
                    "/actuator/health",
                    "/actuator/health/liveness",
                    "/actuator/health/readiness"
                ).permitAll()
                // Prometheus: 管理者/マネージャー、またはスクレイパー専用ロール（マシン認証）。
                // 匿名・営業・HR・要員は拒否。env/beans は exposure 未登録のまま公開しない。
                // Basic資格情報つきの要求は metricsSecurityFilterChain が先に処理する。本chainへ来るのは
                // session主体（自前filterの検証を通過済み）または未認証の要求だけである。
                .requestMatchers(METRICS_ENDPOINT).hasAnyRole(
                        "管理者", "マネージャー", MetricsScraperAuthConfig.ROLE_METRICS_SCRAPER)
                // ユーザー管理・identity-provider・権限設定・監査・システム設定は管理者のみアクセス可能
                .requestMatchers(
                    "/user/**",
                    "/api/users/**",
                    "/api/identity-providers/**",
                    "/api/permission-groups/**",
                    "/api/files/*/rescan",
                    "/api/role-menus/**",
                    "/api/notifications/generate",
                    "/system-config/**",
                    "/api/system-configs/**",
                    "/audit-log/**",
                    "/api/audit-logs/**",
                    "/integration-hub/**",
                    "/api/integration-hub/**",
                    "/api/autocomplete/users",
                    "/api/customer-success/health/snapshots",
                    "/api/customer-success/health/snapshots/**",
                    "/api/service-desk/health/snapshots",
                    "/api/service-desk/health/snapshots/**"
                ).hasRole("管理者")
                .requestMatchers("/api/management-reports/schedules/*/enable",
                        "/api/management-reports/schedules/*/disable")
                .hasRole("管理者")
                // 定期管理レポートは管理者・マネージャーのみ。section/scopeの再検証はserviceで行う。
                .requestMatchers("/management-reports/**", "/api/management-reports/**")
                .hasAnyRole("管理者", "マネージャー")
                // サービスデスク・顧客ヘルス機能は管理者・営業・マネージャーのみ（HR・要員は拒否）
                .requestMatchers(
                    "/service-desk/**",
                    "/api/service-desk/**",
                    "/customer-success/**",
                    "/api/customer-success/**"
                ).hasAnyRole("管理者", "営業", "マネージャー")
                // 資格・学習・skill gapの管理導線。本人申請はA2の /api/my/** へ分離する。
                .requestMatchers("/certification-learning-skill-gap/**", "/api/certification-learning-gap/**")
                .hasAnyRole("管理者", "HR", "マネージャー")
                // HR/マネージャーに開放済みの運用導線。管理者境界とは分離して定義する。
                .requestMatchers(
                    "/api/work-records/confirm",
                    "/api/work-records/reopen",
                    "/api/contracts/generate-renewals",
                    // G2 gate approval（R23-P1-01 §5・P0-2）: 管理者・HR・マネージャーはapproval画面へ入れる。
                    // ただしserviceでcurrent assignment.user_id == currentUserIdを必須にする（§5）。
                    // 具体的パターンを先にマッチさせる（/** 管理者限定より前に置かないと到達不能）。
                    // page/APIのapproval・read系を管理者・HR・マネージャーへ開放。
                    "/api/compliance-gate/approvals",
                    "/api/compliance-gate/capabilities",
                    "/api/compliance-gate/mappings/*/external-reviews",
                    "/api/compliance-gate/mappings/*/verifications",
                    "/api/compliance-gate/submitted-reviews/*/verifications",
                    "/api/compliance-gate/submitted-reviews/*/adoptions"
                ).hasAnyRole("管理者", "HR", "マネージャー")
                // subjectsのGET（閲覧）はHR/マネージャー可・POST（作成・P0-4）は管理者のみ
                .requestMatchers(HttpMethod.GET, "/api/compliance-gate/subjects")
                .hasAnyRole("管理者", "HR", "マネージャー")
                // G2 gate（Phase A step 3・R23-P1-01）: type/policy/assignment/external review/verification/adoption管理は管理者のみ
                .requestMatchers(
                    "/compliance-gate/**",
                    "/api/compliance-gate/**"
                ).hasRole("管理者")
                // 新雇用勤怠の管理画面/API。営業には客先工数のwork-record権限があっても見せない。
                .requestMatchers("/work-record/attendance/**", "/api/work-records/attendance/**")
                .hasAnyRole("管理者", "HR", "マネージャー")
                // 休暇管理（T071/A2）。営業は休暇scopeを持たず、客先報告が必要な休暇の通知だけを受ける（design §5.3）。
                // 本人申請 /my/leave は要員ロールの /my/** 規則が先に適用される。
                .requestMatchers("/leave/**", "/api/leave/**")
                .hasAnyRole("管理者", "HR", "マネージャー")
                // 要員本人のマイ勤怠は要員ロールのみ（本人の画面。管理側は勤怠グリッドで到達する）
                .requestMatchers("/my/**", "/api/my/**").hasRole("要員")
                // ===== HFP-01: freee給与連携の静的境界（menu権限は第三層。UI非表示だけを境界にしない） =====
                // OAuth認可・callback・解除は管理者のみ（callbackも有効な管理者sessionを要求）
                .requestMatchers("/integrations/freee/**").hasRole("管理者")
                // 給与page/APIは管理者・HRのみ
                .requestMatchers("/payroll/**", "/api/payroll/**").hasAnyRole("管理者", "HR")
                // ===== 要員を含む全認証ユーザーが利用できる共通経路 =====
                // 新たに認証ユーザー全体向け機能を追加する場合は必ずここへ追記すること。
                //   /                    ← ロール別振り分けルーター（LoginSuccessHandler が全ロールを / へ送る）
                //   /api/profile/**      ← パスワード変更（ヘッダーに表示・要員も利用）
                //   /password/change     ← 初回ログイン時のパスワード変更画面（PasswordChangeEnforcementFilter）
                //   /api/notifications   ← 通知ベル
                .requestMatchers("/", "/password/change", "/api/profile/**").authenticated()
                .requestMatchers("/api/notifications", "/api/notifications/**").authenticated()
                // 資産貸与証跡のdetail/downloadはDocumentServiceのDocumentLink認可を通す。
                // 一覧/exportは要員へ開放せず、本人証跡の対象IDだけをserviceで再検証する。
                // CERTIFICATION_EVIDENCE は本経路では認可しない（FileScopeが403）。
                // 資格証憑は /api/certification-learning-gap/** または /api/my/** の専用境界のみ。
                .requestMatchers(HttpMethod.GET, "/api/documents/*",
                        "/api/documents/*/versions/*/download")
                .hasAnyRole("管理者", "営業", "HR", "マネージャー", "要員")
                // MFA/session管理は認証後の共通セキュリティ経路（管理者resetはmethod securityで制限）
                .requestMatchers("/mfa/**", "/api/security/**").authenticated()
                // それ以外のリクエストは要員以外のロール（管理者、営業、HR、マネージャー）のみ許可する
                .anyRequest().hasAnyRole("管理者", "営業", "HR", "マネージャー")
            )
            // 例外処理:
            //   - API(/api/**)への未認証アクセスは302ではなく401 JSON
            //   - Prometheus は Basic 向けに 401 + WWW-Authenticate（フォームリダイレクトにしない）。
            //     challengeを受けたclientが資格情報を付けて再送すると metricsSecurityFilterChain が処理する。
            .exceptionHandling(ex -> {
                ex.defaultAuthenticationEntryPointFor(
                    new org.springframework.security.web.authentication.HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED),
                    new AntPathRequestMatcher("/api/**")
                );
                ex.defaultAuthenticationEntryPointFor(
                    metricsEntryPoint(),
                    new AntPathRequestMatcher(METRICS_ENDPOINT)
                );
            })
            // HTTP Basicは本chainでは有効にしない（冒頭の不変条件を参照）。
            // フォームログインの設定（local-login-enabled=false時はUserDetailsService側でbreak-glass以外を拒否）
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .successHandler(loginSuccessHandler)
                .failureHandler(loginFailureHandler)
                .permitAll()
            );

        // スクレイパー資格情報が設定されているときだけ専用 AuthenticationProvider を追加する
        // （@Bean 公開は UserDetailsService 自動配線を壊し得るため、ここでのみ登録）
        AuthenticationProvider scraperProvider = MetricsScraperAuthConfig.createAuthenticationProvider(
                metricsScraperProperties, passwordEncoder);
        if (scraperProvider != null) {
            http.authenticationProvider(scraperProvider);
        }

        if (oidcSecurityProperties.isEnabled()) {
            http.oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .successHandler(loginSuccessHandler)
                .failureHandler(oidcAuthenticationFailureHandler)
                .tokenEndpoint(token -> {
                    org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient<
                            org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest>
                            client = oidcTokenResponseClientProvider.getIfAvailable();
                    if (client != null) {
                        token.accessTokenResponseClient(client);
                    }
                })
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcLoginUserService))
                .permitAll());
        }

        // ログアウトの設定。OIDC endpointが利用可能な場合だけRP initiated logoutへ遷移する。
        http.logout(logout -> {
            // POST 限定: GET /logout による外部サイトからの強制ログアウト（A7-27）を防ぐ。
            logout.logoutRequestMatcher(new AntPathRequestMatcher("/logout", "POST"))
                .addLogoutHandler((request, response, authentication) ->
                    persistentSessionService.revokeCurrent(request, authentication, "LOGOUT"))
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll();
            ClientRegistrationRepository repository = clientRegistrationRepositoryProvider.getIfAvailable();
            OidcClientInitiatedLogoutSuccessHandler oidcHandler = null;
            if (oidcSecurityProperties.isEnabled() && repository != null) {
                oidcHandler = new OidcClientInitiatedLogoutSuccessHandler(repository);
                // redirect先は固定したlogin画面だけにし、request parameterのopen redirectを許さない。
                oidcHandler.setPostLogoutRedirectUri("{baseUrl}/login?logout");
            }
            final OidcClientInitiatedLogoutSuccessHandler selectedOidcHandler = oidcHandler;
            logout.logoutSuccessHandler((request, response, authentication) -> {
                String username = authentication == null ? null : authentication.getName();
                auditLogService.record(username, "AUTH", "/logout", 302, "LOGOUT", true);
                if (selectedOidcHandler != null) {
                    selectedOidcHandler.onLogoutSuccess(request, response, authentication);
                } else {
                    response.sendRedirect("/login?logout");
                }
            });
        })
            // CSRF: Cookie(XSRF-TOKEN)→ヘッダー(X-XSRF-TOKEN)方式に移行。
            // 生トークンをCookieに載せ、JSがヘッダーへ複製する（SPA/AJAX向け標準構成）。
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers("/api/webhooks/**")
            );

        applyTransportSecurity(http);

        http
            // CSRFトークンを毎リクエストで解決し、XSRF-TOKEN Cookieを確実に発行する
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);

        return http.build();
    }

    /**
     * Prometheus scrape専用SecurityFilterChain（HTTP Basicを受け付ける唯一の内部chain）。
     *
     * <p>対象は {@code Authorization: Basic ...} を伴う {@value #METRICS_ENDPOINT} への要求だけである。
     * Basic資格情報を伴わない要求（OIDC/form loginのsession主体、未認証の要求）は従来どおり業務chainへ流れ、
     * session失効・MFA未完了・break-glass scope等の自前filterによる検証を受ける。</p>
     * <ul>
     *   <li>sessionを読まず発行もしない。毎要求でBasic資格情報そのものを検証する。</li>
     *   <li>break-glass利用者はBasicで認証させない。incident束縛・MFA・scope検証はform login sessionでしか
     *       行えないため、パスワード照合より前に拒否し、lockoutを伴わない推測の口も作らない。</li>
     *   <li>GETだけを許可し、それ以外はdenyAll。cookie/sessionによる認証を受け付けず、
     *       状態を変える操作も持たないためCSRF tokenは扱わない。</li>
     * </ul>
     */
    @Bean
    @Order(2)
    public SecurityFilterChain metricsSecurityFilterChain(HttpSecurity http) throws Exception {
        BasicAuthenticationEntryPoint entryPoint = metricsEntryPoint();
        http
            .securityMatcher(metricsBasicRequestMatcher())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(new AntPathRequestMatcher(METRICS_ENDPOINT, HttpMethod.GET.name()))
                .hasAnyRole("管理者", "マネージャー", MetricsScraperAuthConfig.ROLE_METRICS_SCRAPER)
                .anyRequest().denyAll()
            )
            .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
            .requestCache(AbstractHttpConfigurer::disable)
            .csrf(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            // 親AuthenticationManager（DB利用者）より前に評価させ、break-glass名ならそこで打ち切る
            .authenticationProvider(new BreakGlassBasicRejectingProvider(oidcSecurityProperties));

        AuthenticationProvider scraperProvider = MetricsScraperAuthConfig.createAuthenticationProvider(
                metricsScraperProperties, passwordEncoder);
        if (scraperProvider != null) {
            http.authenticationProvider(scraperProvider);
        }

        applyTransportSecurity(http);
        return http.build();
    }

    /**
     * {@value #METRICS_ENDPOINT} かつBasic資格情報を伴う要求だけに一致するmatcher。
     * 一致しない要求は業務chainへ流れるが、業務chainはBasicを処理しないため、判定の取りこぼしは
     * 「Basicが無視されて未認証扱い」になるだけで、自前filterの迂回にはならない（fail-closed）。
     */
    private static RequestMatcher metricsBasicRequestMatcher() {
        RequestMatcher basicCredentials = request -> {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            // BasicAuthenticationConverterと同じく前後空白を除き、scheme名の大小文字を区別しない
            return header != null && StringUtils.startsWithIgnoreCase(header.trim(), "Basic");
        };
        return new AndRequestMatcher(new AntPathRequestMatcher(METRICS_ENDPOINT), basicCredentials);
    }

    private static BasicAuthenticationEntryPoint metricsEntryPoint() {
        BasicAuthenticationEntryPoint entryPoint = new BasicAuthenticationEntryPoint();
        entryPoint.setRealmName(METRICS_REALM);
        return entryPoint;
    }

    /** HTTPS強制とHSTSを全内部chainで同一にする（metrics chainだけ平文で到達させない）。 */
    private void applyTransportSecurity(HttpSecurity http) throws Exception {
        http.headers(headers -> {
            if (requireHttps) {
                headers.httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .preload(false)
                    .maxAgeInSeconds(31536000));
            }
        });
        if (requireHttps) {
            http.requiresChannel(channel -> channel.anyRequest().requiresSecure());
        }
    }

    /**
     * metrics chainでbreak-glass利用者のBasic認証をパスワード照合前に拒否するprovider。
     * {@link DisabledException} は AccountStatusException のため、ProviderManagerは後続providerや
     * 親AuthenticationManagerへ委譲せずにその場で認証失敗（401）とする。
     * break-glass名以外は {@code null} を返し、scraper/DB利用者の通常の認証へ委ねる。
     */
    static final class BreakGlassBasicRejectingProvider implements AuthenticationProvider {

        private final OidcSecurityProperties oidcSecurityProperties;

        BreakGlassBasicRejectingProvider(OidcSecurityProperties oidcSecurityProperties) {
            this.oidcSecurityProperties = oidcSecurityProperties;
        }

        @Override
        public Authentication authenticate(Authentication authentication) {
            String username = authentication.getName();
            Set<String> breakGlassUsernames = oidcSecurityProperties.getBreakGlassUsernames();
            if (username == null || breakGlassUsernames == null) {
                return null;
            }
            // DBの照合順序（大小文字・末尾空白を区別しない）でbreak-glass行へ解決される表記揺れも止める
            String candidate = username.trim();
            boolean breakGlass = breakGlassUsernames.stream()
                    .anyMatch(name -> name != null && name.trim().equalsIgnoreCase(candidate));
            if (breakGlass) {
                throw new DisabledException("break-glass account cannot use HTTP Basic");
            }
            return null;
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
        }
    }

    private CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie
            .secure(requireHttps)
            .sameSite("Lax"));
        return repository;
    }

    // PasswordEncoder の定義は PasswordEncoderConfig に集約した（ACC-SEC-P1-007）。
    // 明示的な dev/test プロファイルでのみ平文(NoOp)を許可し、prod は BCrypt、
    // プロファイル未指定・不明な場合は起動を fail-closed で失敗させる。

    /**
     * CSRFトークンを解決してCookie(XSRF-TOKEN)を確実に発行するためのフィルター。
     * CookieCsrfTokenRepositoryはトークンが読み出された時にCookieを書き込むため、
     * 各リクエストでgetToken()を呼び出してCookie発行をトリガーする。
     */
    static class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                // getToken() の呼び出しでCookieへの書き込みが行われる
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
