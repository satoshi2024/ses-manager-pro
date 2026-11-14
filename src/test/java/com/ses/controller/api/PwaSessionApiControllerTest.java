package com.ses.controller.api;

import com.ses.config.LoginUser;
import com.ses.config.OidcLoginUser;
import com.ses.entity.SysUser;
import com.ses.service.pwa.PwaUserContextService;
import com.ses.service.security.BreakGlassService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** session-contextの復元可否（restoreAvailable）がserver側の認証主体だけで決まることを確認する（NF04 P1-N4-1）。 */
@WebMvcTest(PwaSessionApiController.class)
class PwaSessionApiControllerTest {
    private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");

    @Autowired private MockMvc mockMvc;
    @MockBean private PwaUserContextService userContextService;

    @BeforeEach
    void stubContext() {
        when(userContextService.resolve(any())).thenReturn(new PwaUserContextService.ContextResolution(
                new PwaUserContextService.CurrentContext(7L, 44L, "scope-v2", NOW), true));
    }

    @Test
    void ローカルパスワードでloginしたsessionはrestoreAvailableをtrueで返す() throws Exception {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_要員"));
        LoginUser principal = new LoginUser(user(), authorities, "tenant-a");
        // sessionの無い要求は復元できない側に倒すため、session認証と同じくsessionを付ける（R2-P3-1）。
        mockMvc.perform(get("/api/my/session-context").session(new MockHttpSession())
                        .with(authentication(UsernamePasswordAuthenticationToken.authenticated(
                                principal, "", authorities))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userScope").value("scope-v2"))
                .andExpect(jsonPath("$.data.preserveQueue").value(true))
                .andExpect(jsonPath("$.data.restoreAvailable").value(true));
    }

    @Test
    void breakGlassのsessionはローカルパスワードでもrestoreAvailableをfalseで返す() throws Exception {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_要員"));
        LoginUser principal = new LoginUser(user(), authorities, "tenant-a");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 99L);
        mockMvc.perform(get("/api/my/session-context").session(session)
                        .with(authentication(UsernamePasswordAuthenticationToken.authenticated(
                                principal, "", authorities))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restoreAvailable").value(false));
    }

    @Test
    void oidcでloginしたsessionはclient申告に関係なくrestoreAvailableをfalseで返す() throws Exception {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_要員"));
        OidcIdToken idToken = OidcIdToken.withTokenValue("pwa-session-test-id-token")
                .subject("idp-subject-7").issuer("https://idp.invalid/tenant-a")
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(300)).build();
        OAuth2AuthenticationToken oidc = new OAuth2AuthenticationToken(
                new OidcLoginUser(user(), new DefaultOidcUser(authorities, idToken), authorities),
                authorities, "enterprise");
        mockMvc.perform(get("/api/my/session-context")
                        .param("restoreAvailable", "true")
                        .header("X-Restore-Available", "true")
                        .with(authentication(oidc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userScope").value("scope-v2"))
                .andExpect(jsonPath("$.data.restoreAvailable").value(false));
    }

    private static SysUser user() {
        SysUser user = new SysUser();
        user.setId(7L);
        user.setUsername("user-a");
        user.setTenantId("tenant-a");
        user.setRole("要員");
        user.setStatus(1);
        return user;
    }
}
