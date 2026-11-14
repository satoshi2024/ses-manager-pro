package com.ses.config;

import com.ses.service.security.AuthorizationService;
import com.ses.test.DisableDefaultTenantTestContext;
import com.ses.test.TenantTestSecurity;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.authentication.www.DigestAuthenticationFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP Basicが業務chainの自前filterを迂回しないことを実SecurityFilterChainで固定する。
 *
 * <p>Spring Security 6.3の既定順は UsernamePasswordAuthenticationFilter(1900) &lt;
 * BasicAuthenticationFilter(2600) である。業務chainは PersistentSessionFilter(1900) /
 * InternalTenantContextFilter(1901) / MenuPermissionFilter(1902) / ApiAuditFilter(1903) /
 * MfaEnforcementFilter(1904) をUsernamePasswordAuthenticationFilter基準で置いているため、
 * 業務chainでBasicを受け付けると、これらは認証null（未認証扱い）で素通りし、その後で
 * Basicが主体を確定させる。結果としてBasic主体はmenu/action deny・session検証・break-glassの
 * incident/scope検証・MFAを経ずに、静的role規則だけで業務APIへ到達していた。</p>
 *
 * <p>修正前はBasic要求が業務APIへ到達するため、本classの401/302/構造検証は失敗する。</p>
 */
@SpringBootTest(properties = "app.security.oidc.break-glass-usernames=basic-bg-01")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisableDefaultTenantTestContext
class HttpBasicChainBoundaryTest {

    private static final String SALES_USER = "basic-sales";
    private static final String SALES_PASS = "basic-sales-pass";
    private static final String ADMIN_USER = "basic-admin";
    private static final String ADMIN_PASS = "basic-admin-pass";
    private static final String BREAK_GLASS_USER = "basic-bg-01";
    private static final String BREAK_GLASS_PASS = "basic-bg-pass";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FilterChainProxy filterChainProxy;

    /** 管理者が対象roleのaction権限を剥奪した状態（全actionをdeny）を再現する。 */
    @MockBean
    private AuthorizationService authorizationService;

    @BeforeEach
    void seedUsers() {
        // test profileはNoOpPasswordEncoderのため平文で投入する
        upsertUser(SALES_USER, SALES_PASS, "営業");
        upsertUser(ADMIN_USER, ADMIN_PASS, "管理者");
        upsertUser(BREAK_GLASS_USER, BREAK_GLASS_PASS, "管理者");
    }

    @Test
    void session相当の認証主体はactionDenyにより403になる() throws Exception {
        // 対照: 自前filterより前に認証済み主体が居れば、MenuPermissionFilterがaction denyを適用する
        mockMvc.perform(get("/api/skill-tags")
                        .with(authentication(TenantTestSecurity.authentication(
                                userId(SALES_USER), SALES_USER, "default", "営業"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verify(authorizationService, atLeastOnce()).isAllowed(any(), eq("skill-tag.view"));
    }

    @Test
    void 同じ主体のBasic資格情報は業務APIで認証されず401になる() throws Exception {
        // 修正前: 自前filterを素通りした後にBasicで営業主体が確定し、静的role規則だけで200になっていた
        mockMvc.perform(get("/api/skill-tags")
                        .header(HttpHeaders.AUTHORIZATION, basic(SALES_USER, SALES_PASS)))
                .andExpect(status().isUnauthorized())
                // 業務chainはBasic challengeを返さない（browserの資格情報入力・自動再送を誘発しない）
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    void Basic資格情報では更新系APIへも到達できない() throws Exception {
        // CSRF tokenは未認証のGETで取得できるため、Basic + CSRFの組み合わせは非browser clientでも成立する。
        // 修正前は管理者Basicで@PreAuthorizeまで到達していた。
        mockMvc.perform(delete("/api/skill-tags/999999")
                        .with(csrf())
                        .header(HttpHeaders.AUTHORIZATION, basic(ADMIN_USER, ADMIN_PASS)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void Basic資格情報では業務画面へも到達できずloginへ誘導される() throws Exception {
        mockMvc.perform(get("/engineer/list")
                        .accept(MediaType.TEXT_HTML)
                        .header(HttpHeaders.AUTHORIZATION, basic(ADMIN_USER, ADMIN_PASS)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void breakGlass主体はBasicで業務APIへ到達できない() throws Exception {
        // 修正前: incident束縛・MFA・scope検証を一切経ずに管理者として到達していた
        mockMvc.perform(get("/api/skill-tags")
                        .header(HttpHeaders.AUTHORIZATION, basic(BREAK_GLASS_USER, BREAK_GLASS_PASS)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void breakGlass主体はmetrics経路でもBasic認証できず通常の管理者は従来どおりscrapeできる() throws Exception {
        // 修正前: 業務chainのBasicで管理者として200になっていた（MFA・incident/scope検証なし）
        mockMvc.perform(get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, basic(BREAK_GLASS_USER, BREAK_GLASS_PASS)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("SES Metrics")));

        mockMvc.perform(get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, basic(ADMIN_USER, ADMIN_PASS)))
                .andExpect(status().isOk())
                .andExpect(content().string(anyOf(
                        containsString("# HELP"), containsString("# TYPE"), containsString("jvm_"))));
    }

    @Test
    void breakGlass判定は大小文字と前後空白の表記揺れでも外れない() {
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setBreakGlassUsernames(new LinkedHashSet<>(Set.of("BG-01")));
        SecurityConfig.BreakGlassBasicRejectingProvider provider =
                new SecurityConfig.BreakGlassBasicRejectingProvider(properties);

        assertThatThrownBy(() -> provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(" bg-01 ", "secret")))
                .isInstanceOf(DisabledException.class);
        // break-glass以外は判定せず、scraper/DB利用者の通常の認証へ委ねる
        assertThat(provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(ADMIN_USER, "secret"))).isNull();
    }

    @Test
    void Basic認証filterは自前filterを持たないmetrics専用chainだけに存在する() {
        List<SecurityFilterChain> chains = filterChainProxy.getFilterChains();

        // 自前filterを持つchain（業務chain）では、それより後ろで認証を確定させて継続するfilterを置かない
        List<SecurityFilterChain> businessChains = chains.stream()
                .filter(chain -> chain.getFilters().stream().anyMatch(MenuPermissionFilter.class::isInstance))
                .toList();
        assertThat(businessChains).hasSize(1);
        List<Filter> businessFilters = businessChains.get(0).getFilters();
        int firstBoundary = firstIndexOfBoundaryFilter(businessFilters);
        assertThat(firstBoundary).isGreaterThanOrEqualTo(0);
        for (Class<? extends Filter> boundary : boundaryFilterTypes()) {
            assertThat(businessFilters).filteredOn(boundary::isInstance).hasSize(1);
        }
        for (int i = firstBoundary; i < businessFilters.size(); i++) {
            Filter filter = businessFilters.get(i);
            assertThat(isContinuingAuthenticationFilter(filter))
                    .as("自前filterより後ろで認証を確定させるfilterが業務chainにある: %s",
                            filter.getClass().getName())
                    .isFalse();
        }
        assertThat(businessFilters).noneMatch(BasicAuthenticationFilter.class::isInstance);

        // Basicを持つのはmetrics専用chainだけで、そこには業務chainの自前filterもform loginも無い
        List<SecurityFilterChain> basicChains = chains.stream()
                .filter(chain -> chain.getFilters().stream().anyMatch(BasicAuthenticationFilter.class::isInstance))
                .toList();
        assertThat(basicChains).hasSize(1);
        SecurityFilterChain metricsChain = basicChains.get(0);
        for (Class<? extends Filter> boundary : boundaryFilterTypes()) {
            assertThat(metricsChain.getFilters()).noneMatch(boundary::isInstance);
        }
        assertThat(metricsChain.getFilters()).noneMatch(UsernamePasswordAuthenticationFilter.class::isInstance);

        // metrics chainが引き受けるのは「/actuator/prometheus かつ Basic資格情報あり」だけ
        assertThat(metricsChain.matches(request("/actuator/prometheus", basic("scraper", "secret")))).isTrue();
        assertThat(metricsChain.matches(request("/actuator/prometheus", null))).isFalse();
        assertThat(metricsChain.matches(request("/api/skill-tags", basic(SALES_USER, SALES_PASS)))).isFalse();
        assertThat(metricsChain.matches(request("/engineer/list", basic(ADMIN_USER, ADMIN_PASS)))).isFalse();
    }

    private static List<Class<? extends Filter>> boundaryFilterTypes() {
        return List.of(PersistentSessionFilter.class, InternalTenantContextFilter.class,
                MenuPermissionFilter.class, ApiAuditFilter.class, MfaEnforcementFilter.class);
    }

    private static int firstIndexOfBoundaryFilter(List<Filter> filters) {
        for (int i = 0; i < filters.size(); i++) {
            Filter filter = filters.get(i);
            if (boundaryFilterTypes().stream().anyMatch(type -> type.isInstance(filter))) {
                return i;
            }
        }
        return -1;
    }

    /** 認証成功後に後続filterへ処理を継続させる（=自前filterより前に置く必要がある）認証filter。 */
    private static boolean isContinuingAuthenticationFilter(Filter filter) {
        return filter instanceof BasicAuthenticationFilter
                || filter instanceof RememberMeAuthenticationFilter
                || filter instanceof DigestAuthenticationFilter
                || filter instanceof AbstractPreAuthenticatedProcessingFilter
                || filter instanceof AuthenticationFilter
                || "org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter"
                        .equals(filter.getClass().getName());
    }

    private static MockHttpServletRequest request(String path, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        if (authorization != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request;
    }

    private Long userId(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private void upsertUser(String username, String password, String role) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username = ?", Integer.class, username);
        if (count != null && count > 0) {
            jdbcTemplate.update(
                    "UPDATE sys_user SET password = ?, role = ?, tenant_id = 'default', status = 1 WHERE username = ?",
                    password, role, username);
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO sys_user (username, password, real_name, role, email, tenant_id, status) "
                        + "VALUES (?,?,?,?,?,'default',1)",
                username, password, "Basic境界テスト " + role, role, username + "@ses.test");
    }

    private static String basic(String user, String password) {
        String raw = user + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
