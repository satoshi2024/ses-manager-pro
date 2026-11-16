package com.ses.privacy.foundation;

import com.ses.config.LoginUser;
import com.ses.config.OidcLoginUser;
import com.ses.entity.BreakGlassIncident;
import com.ses.entity.SysUser;
import com.ses.mapper.BreakGlassIncidentMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AuditLogService;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.LegalEntityContextService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/**
 * D-P3-1: 通常 local login 無効（prod 相当）で、未認証者が OIDC 管理者の username へ form login を送り続けても
 * {@code failed_count} / {@code locked_until} が動かず、privacy の照会者判定（{@link SecurityContextCallerContextProvider}）が
 * その管理者を拒否しないことを、実 SecurityFilterChain・実 form login・H2 で確かめる。
 * パスワードを照合する break-glass 利用者は従来どおり5回でロックされ、ロック中は照会者として拒否される。
 *
 * <p>行は本 test が挿入したものだけを読み、class の {@code @Transactional} で巻き戻す。
 */
@SpringBootTest(properties = {
        "app.security.oidc.local-login-enabled=false",
        "app.security.oidc.break-glass-usernames=" + LocalLoginRefusalLockoutIntegrationTest.BREAK_GLASS
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LocalLoginRefusalLockoutIntegrationTest {

    static final String BREAK_GLASS = "nf07-dp31-breakglass";
    private static final String OIDC_ADMIN = "nf07-dp31-oidc-admin";
    private static final String TENANT = "default";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SysUserMapper sysUserMapper;

    @MockBean
    private BreakGlassIncidentMapper incidentMapper;
    @MockBean
    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        insertAdmin(OIDC_ADMIN, "OidcOnly2026x");
        insertAdmin(BREAK_GLASS, "BreakGlass2026x");
        when(incidentMapper.selectActive(eq(TENANT), any(LocalDateTime.class))).thenReturn(activeIncident());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 未認証者が繰り返し失敗してもOIDC管理者はロックされずprivacy照会者として拒否されない() throws Exception {
        for (int attempt = 0; attempt < 12; attempt++) {
            // 誤り・正しいパスワードのどちらでも、local login 無効の拒否はパスワード照合前に起きる
            String password = attempt % 3 == 0 ? "OidcOnly2026x" : "guess-" + attempt;
            mockMvc.perform(formLogin("/login").user(OIDC_ADMIN).password(password))
                    .andExpect(redirectedUrl("/login?error"));
        }

        Map<String, Object> lockState = lockState(OIDC_ADMIN);
        assertThat(((Number) lockState.get("failed_count")).intValue()).isZero();
        assertThat(lockState.get("locked_until")).isNull();

        SysUser persisted = sysUserMapper.selectByUsername(OIDC_ADMIN);
        OidcUser delegate = mock(OidcUser.class);
        when(delegate.getClaims()).thenReturn(Map.of("tenant_id", TENANT));
        authenticate(new OidcLoginUser(persisted, delegate, authorities()));

        assertThat(callerProvider().getCallerContext())
                .as("OIDC 管理者の privacy 操作は未認証者の失敗で妨害されない")
                .hasValueSatisfying(caller -> {
                    assertThat(caller.tenantId()).isEqualTo(TENANT);
                    assertThat(caller.actorId()).isEqualTo(Long.toString(persisted.getId()));
                });
    }

    @Test
    void break_glass利用者のパスワード誤りは従来どおり5回でロックしロック中は照会者として拒否する() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            mockMvc.perform(formLogin("/login").user(BREAK_GLASS).password("wrong-" + attempt))
                    .andExpect(redirectedUrl("/login?error"));
        }
        assertThat(((Number) lockState(BREAK_GLASS).get("failed_count")).intValue()).isEqualTo(4);
        assertThat(lockState(BREAK_GLASS).get("locked_until")).isNull();

        mockMvc.perform(formLogin("/login").user(BREAK_GLASS).password("wrong-5"))
                .andExpect(redirectedUrl("/login?error"));
        assertThat(lockState(BREAK_GLASS).get("locked_until")).as("5回目でロック").isNotNull();

        // ロック中は正しいパスワードでもロック画面へ
        mockMvc.perform(formLogin("/login").user(BREAK_GLASS).password("BreakGlass2026x"))
                .andExpect(redirectedUrl("/login?locked"));

        SysUser persisted = sysUserMapper.selectByUsername(BREAK_GLASS);
        authenticate(new LoginUser(persisted, authorities(), TENANT));
        assertThat(callerProvider().getCallerContext()).as("ロック中の local login 利用者は従来どおり拒否").isEmpty();
    }

    @Test
    void 前後に空白やタブを付けたusernameのパスワード誤りも同じ口座に数え5回でロックする() throws Exception {
        // DOC-FINAL delta review N-1: 実 form login は username を trim して認証するため、計数も trim 後の名前で行う。
        // 入力値のままだと先頭に空白を付けた名前の失敗が空振りし、lock されずに推測を続けられた。
        String[] variants = {" " + BREAK_GLASS, BREAK_GLASS + " ", "\t" + BREAK_GLASS, " \t" + BREAK_GLASS};
        for (int attempt = 0; attempt < variants.length; attempt++) {
            mockMvc.perform(formLogin("/login").user(variants[attempt]).password("wrong-" + attempt))
                    .andExpect(redirectedUrl("/login?error"));
        }
        assertThat(((Number) lockState(BREAK_GLASS).get("failed_count")).intValue()).isEqualTo(4);
        assertThat(lockState(BREAK_GLASS).get("locked_until")).isNull();

        mockMvc.perform(formLogin("/login").user("  " + BREAK_GLASS).password("wrong-5"))
                .andExpect(redirectedUrl("/login?error"));
        assertThat(lockState(BREAK_GLASS).get("locked_until")).as("表記の違う5回目でもロック").isNotNull();
        mockMvc.perform(formLogin("/login").user(BREAK_GLASS).password("BreakGlass2026x"))
                .andExpect(redirectedUrl("/login?locked"));
    }

    private SecurityContextCallerContextProvider callerProvider() {
        LegalEntityContextService legalEntityContext = mock(LegalEntityContextService.class);
        when(legalEntityContext.requireCurrentLegalEntityId()).thenReturn(42L);
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        when(authorizationService.isAllowed(any(), eq(SecurityContextCallerContextProvider.PRIVACY_POLICY_VIEW_ACTION)))
                .thenReturn(true);
        return new SecurityContextCallerContextProvider(legalEntityContext, sysUserMapper,
                () -> authorizationService, TENANT, Clock.system(ZoneId.of("Asia/Tokyo")));
    }

    private void insertAdmin(String username, String password) {
        jdbcTemplate.update("INSERT INTO sys_user (username, password, real_name, role, email, tenant_id, status, "
                        + "failed_count, locked_until) VALUES (?, ?, 'D-P3-1 検証', '管理者', ?, ?, 1, 0, NULL)",
                username, password, username + "@ses.test", TENANT);
    }

    private Map<String, Object> lockState(String username) {
        return jdbcTemplate.queryForMap("SELECT failed_count, locked_until FROM sys_user WHERE username = ?", username);
    }

    private static void authenticate(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities()));
    }

    private static List<SimpleGrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_管理者"));
    }

    private static BreakGlassIncident activeIncident() {
        BreakGlassIncident incident = new BreakGlassIncident();
        incident.setId(10L);
        incident.setTenantId(TENANT);
        incident.setStatus("ACTIVE");
        incident.setIdpOutageConfirmed(1);
        incident.setApprovedBy1(11L);
        incident.setApprovedBy2(12L);
        incident.setEnabledFrom(LocalDateTime.now().minusMinutes(1));
        incident.setEnabledUntil(LocalDateTime.now().plusMinutes(30));
        incident.setAllowedActions("dashboard.view");
        return incident;
    }
}
