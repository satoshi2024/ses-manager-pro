package com.ses.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.util.TotpUtil;
import com.ses.entity.PortalInvitation;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalUser;
import com.ses.mapper.PortalInvitationMapper;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T083 F2のportal認証フロー（login/MFA/招待/規約同意/session失効）を検証する。
 * portal POSTはportal専用CSRF（XSRF-TOKEN-PORTAL）が必須（内部CSRFと分離）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PortalAuthFlowTest extends PortalTestSupport {

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected PortalInvitationMapper invitationMapper;
    @Autowired
    protected SystemConfigService systemConfigService;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected TransactionTemplate transactionTemplate;

    @Override
    protected JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder portalPost(String url,
                                                                                                 CsrfPair csrf) {
        return post(url).cookie(csrf.cookie()).header("X-XSRF-TOKEN-PORTAL", csrf.headerValue());
    }

    @Test
    void ログインはMFA_SETUPから有効化フローを経てsessionを発行する() throws Exception {
        CsrfPair csrf = fetchPortalCsrf(mockMvc);
        PortalOrganization org = createCustomerOrg("flow");
        PortalUser user = createUser(org, "flow-" + unique() + "@example.com");

        // パスワード誤り
        mockMvc.perform(portalPost("/api/portal/auth/login", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        // 正しいパスワード → MFA_SETUP + secret
        MvcResult setupResult = mockMvc.perform(portalPost("/api/portal/auth/login", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("MFA_SETUP"))
                .andReturn();
        JsonNode setup = objectMapper.readTree(setupResult.getResponse().getContentAsString()).path("data");
        String secret = setup.path("mfaSetup").path("secret").asText();
        assertTrue(secret.length() >= 16, "TOTP secretが返るはず");

        // 不正コードは拒否
        mockMvc.perform(portalPost("/api/portal/auth/mfa/complete", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + user.getEmail() + "\",\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized());

        // 正しいコード → recovery code + session cookie
        String code = TotpUtil.code(secret, Instant.now().getEpochSecond() / 30, 6);
        MvcResult completeResult = mockMvc.perform(portalPost("/api/portal/auth/mfa/complete", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + user.getEmail() + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("PORTAL_SESSION"))
                .andReturn();
        JsonNode complete = objectMapper.readTree(completeResult.getResponse().getContentAsString()).path("data");
        assertTrue(complete.path("recoveryCode").asText().length() >= 8, "recovery codeが返るはず");

        MockCookie session = new MockCookie("PORTAL_SESSION",
                completeResult.getResponse().getCookie("PORTAL_SESSION").getValue());

        // 有効化済みに対する再完了は409（alreadyEnabled）
        mockMvc.perform(portalPost("/api/portal/auth/mfa/complete", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + user.getEmail() + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isConflict());

        // session有効・規約未同意（termsPending=true）の間はindexへ行けない
        mockMvc.perform(get("/api/portal/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(user.getEmail()))
                .andExpect(jsonPath("$.data.termsPending").value(true));
        mockMvc.perform(get("/portal").cookie(session)).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/portal/terms"));
        // 規約同意（version不一致は拒否、現行一致で成功）
        mockMvc.perform(portalPost("/api/portal/auth/consent", csrf).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"termsVersion\":\"999\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(portalPost("/api/portal/auth/consent", csrf).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"termsVersion\":\"1\"}"))
                .andExpect(status().isOk());

        // 同意後: termsPending=false・自組織のportal画面（顧客→/portal/customer）へ
        mockMvc.perform(get("/api/portal/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.termsPending").value(false));
        mockMvc.perform(get("/portal").cookie(session)).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/portal/customer"));

        // 再ログイン（password + TOTP）。completeで使ったstepより大きいstepのコードを使う
        // （last_used_step CASは同一step以下の再使用を拒否するため）
        long completedStep = Instant.now().getEpochSecond() / 30;
        String secondCode = TotpUtil.code(secret, completedStep + 1, 6);
        mockMvc.perform(portalPost("/api/portal/auth/login", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail()
                                + "\",\"password\":\"" + PASSWORD + "\",\"mfaCode\":\"" + secondCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OK"))
                .andExpect(jsonPath("$.data.termsPending").value(false));

        // 同一TOTPコードの再使用（同一step）はlast_used_step CASで拒否される
        mockMvc.perform(portalPost("/api/portal/auth/login", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail()
                                + "\",\"password\":\"" + PASSWORD + "\",\"mfaCode\":\"" + secondCode + "\"}"))
                .andExpect(status().isUnauthorized());

        // logoutでsession失効
        mockMvc.perform(portalPost("/api/portal/auth/logout", csrf).cookie(session))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/portal/auth/me").cookie(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void recovery_codeは1回だけloginに使える() throws Exception {
        CsrfPair csrf = fetchPortalCsrf(mockMvc);
        PortalOrganization org = createCustomerOrg("recovery");
        PortalUser user = createUser(org, "recovery-" + unique() + "@example.com");
        String secret = uniqueSecret();
        enableMfa(user, secret);
        consentTerms(user);
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalUser>()
                .eq("id", user.getId())
                .set("recovery_code_hash",
                        com.ses.common.util.SecurityHashUtil.sha256("AAAA-0000"))
                .set("recovery_code_used_at", null));

        mockMvc.perform(portalPost("/api/portal/auth/login", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"" + PASSWORD
                                + "\",\"mfaCode\":\"AAAA-0000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OK"));

        // 2回目は拒否
        mockMvc.perform(portalPost("/api/portal/auth/login", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"" + PASSWORD
                                + "\",\"mfaCode\":\"AAAA-0000\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 招待は4条件を検証しtokenは一回だけ使える() throws Exception {
        CsrfPair csrf = fetchPortalCsrf(mockMvc);
        PortalOrganization org = createCustomerOrg("invite");
        String email = "invite-" + unique() + "@example.com";
        String rawToken = "raw-token-" + unique();
        PortalInvitation invitation = new PortalInvitation();
        invitation.setPortalOrgId(org.getId());
        invitation.setEmail(email);
        invitation.setRole("ADMIN");
        invitation.setTokenHash(com.ses.common.util.SecurityHashUtil.sha256(rawToken));
        invitation.setExpiresAt(LocalDateTime.now().plusHours(72));
        invitationMapper.insert(invitation);

        String body = "{\"token\":\"" + rawToken + "\",\"email\":\"" + email
                + "\",\"displayName\":\"新規利用者\",\"password\":\"password123\"}";

        // email不一致
        mockMvc.perform(portalPost("/api/portal/auth/accept-invitation", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace(email, "other-" + email)))
                .andExpect(status().isBadRequest());

        // 正しく受諾
        mockMvc.perform(portalPost("/api/portal/auth/accept-invitation", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        PortalUser created = userMapper.selectByEmail(email);
        assertNotNull(created, "portal userが作成されるはず");
        assertEquals(org.getId(), created.getPortalOrgId());

        // token再利用は拒否（CAS）
        mockMvc.perform(portalPost("/api/portal/auth/accept-invitation", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());

        // tokenはhashのみ保存されている（平文がDBに残らない）
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM t_portal_invitation WHERE id = ?", String.class, invitation.getId());
        assertEquals(com.ses.common.util.SecurityHashUtil.sha256(rawToken), storedHash,
                "tokenはSHA-256 hashのみ保存されるはず");

        // 期限切れ招待
        String expiredEmail = "expired-" + unique() + "@example.com";
        PortalInvitation expired = new PortalInvitation();
        expired.setPortalOrgId(org.getId());
        expired.setEmail(expiredEmail);
        expired.setRole("MEMBER");
        expired.setTokenHash(com.ses.common.util.SecurityHashUtil.sha256("expired-token-xyz"));
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        invitationMapper.insert(expired);
        mockMvc.perform(portalPost("/api/portal/auth/accept-invitation", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"expired-token-xyz\",\"email\":\"" + expiredEmail
                                + "\",\"displayName\":\"期限切れ\",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest());

        // 組織停止時は受諾拒否
        PortalOrganization suspendedOrg = createBpOrg("suspend-org");
        suspendedOrg.setStatus("SUSPENDED");
        organizationMapper.updateById(suspendedOrg);
        PortalInvitation orgInvite = new PortalInvitation();
        orgInvite.setPortalOrgId(suspendedOrg.getId());
        orgInvite.setEmail("org-suspend@example.com");
        orgInvite.setRole("MEMBER");
        orgInvite.setTokenHash(com.ses.common.util.SecurityHashUtil.sha256("suspended-org-token"));
        orgInvite.setExpiresAt(LocalDateTime.now().plusHours(72));
        invitationMapper.insert(orgInvite);
        mockMvc.perform(portalPost("/api/portal/auth/accept-invitation", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"suspended-org-token\",\"email\":\"org-suspend@example.com\","
                                + "\"displayName\":\"停止組織\",\"password\":\"password123\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 停止されたuserの招待受諾は拒否されreactivateは組織を付け替える() throws Exception {
        CsrfPair csrf = fetchPortalCsrf(mockMvc);
        PortalOrganization orgA = createCustomerOrg("reorg-a");
        PortalOrganization orgB = createCustomerOrg("reorg-b");
        String email = "reorg-" + unique() + "@example.com";
        PortalUser user = createUser(orgA, email);
        String secret = uniqueSecret();
        enableMfa(user, secret);
        consentTerms(user);

        // 停止されたuser（org A）への org B の招待 → 409（S13-R1-P0-01: 自己復活拒否）
        PortalInvitation inviteToSuspended = new PortalInvitation();
        inviteToSuspended.setPortalOrgId(orgB.getId());
        inviteToSuspended.setEmail(email);
        inviteToSuspended.setRole("MEMBER");
        inviteToSuspended.setTokenHash(com.ses.common.util.SecurityHashUtil.sha256("reorg-token-1"));
        inviteToSuspended.setExpiresAt(LocalDateTime.now().plusHours(72));
        invitationMapper.insert(inviteToSuspended);
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalUser>()
                .eq("id", user.getId()).set("status", "SUSPENDED"));
        mockMvc.perform(portalPost("/api/portal/auth/accept-invitation", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"reorg-token-1\",\"email\":\"" + email
                                + "\",\"displayName\":\"復活試行\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict());
        assertEquals("SUSPENDED", jdbcTemplate.queryForObject(
                "SELECT status FROM t_portal_user WHERE id = ?", String.class, user.getId()));

        // 論理削除済みuser（org A）への org B の招待 → reactivateかつ組織をBへ付け替え
        String email2 = "reorg2-" + unique() + "@example.com";
        PortalUser user2 = createUser(orgA, email2);
        jdbcTemplate.update("UPDATE t_portal_user SET deleted_flag = 1 WHERE id = ?", user2.getId());
        PortalInvitation inviteToDeleted = new PortalInvitation();
        inviteToDeleted.setPortalOrgId(orgB.getId());
        inviteToDeleted.setEmail(email2);
        inviteToDeleted.setRole("MEMBER");
        inviteToDeleted.setTokenHash(com.ses.common.util.SecurityHashUtil.sha256("reorg-token-2"));
        inviteToDeleted.setExpiresAt(LocalDateTime.now().plusHours(72));
        invitationMapper.insert(inviteToDeleted);
        mockMvc.perform(portalPost("/api/portal/auth/accept-invitation", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"reorg-token-2\",\"email\":\"" + email2
                                + "\",\"displayName\":\"再参加\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());
        Long newOrgId = jdbcTemplate.queryForObject(
                "SELECT portal_org_id FROM t_portal_user WHERE id = ?", Long.class, user2.getId());
        assertEquals(orgB.getId(), newOrgId, "reactivate時は招待の組織へ付け替えられるはず（S13-R1-P0-01）");
        assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                "SELECT status FROM t_portal_user WHERE id = ?", String.class, user2.getId()));
    }

    @Test
    void user停止と組織停止でsessionが即時失効する() throws Exception {        PortalOrganization org = createCustomerOrg("suspend");
        PortalUser user = createUser(org, "suspend-" + unique() + "@example.com");
        String secret = uniqueSecret();
        enableMfa(user, secret);
        consentTerms(user);
        MockCookie session = issueSession(user);

        mockMvc.perform(get("/api/portal/auth/me").cookie(session)).andExpect(status().isOk());

        // user停止 → 即時失効
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalUser>()
                .eq("id", user.getId()).set("status", "SUSPENDED"));
        mockMvc.perform(get("/api/portal/auth/me").cookie(session))
                .andExpect(status().isUnauthorized());

        // 復活 → sessionはrevokeAllForUser後なので再発行が必要
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalUser>()
                .eq("id", user.getId()).set("status", "ACTIVE"));
        MockCookie session2 = issueSession(userMapper.selectById(user.getId()));
        mockMvc.perform(get("/api/portal/auth/me").cookie(session2)).andExpect(status().isOk());

        // 組織停止 → 即時失効
        organizationMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalOrganization>()
                .eq("id", org.getId()).set("status", "SUSPENDED"));
        mockMvc.perform(get("/api/portal/auth/me").cookie(session2))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 規約version更新後に再同意を強制する() throws Exception {
        PortalOrganization org = createCustomerOrg("terms");
        UserFixture fixture = readyUser(org, "terms-" + unique() + "@example.com");

        mockMvc.perform(get("/api/portal/auth/me").cookie(fixture.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.termsPending").value(false));

        // version 2を発行（管理者操作を模擬。キャッシュ失効はcommit後に走るため、
        // REQUIRES_NEWでコミットしてから検証する。最後に1へ戻す）
        try {
            transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transactionTemplate.executeWithoutResult(status ->
                    systemConfigService.put("portal.terms.current-version", "2", "test"));

            mockMvc.perform(get("/api/portal/auth/me").cookie(fixture.sessionCookie()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.termsPending").value(true));
            // 同意待ちの間はindexへリダイレクト
            mockMvc.perform(get("/portal").cookie(fixture.sessionCookie()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/portal/terms"));
            mockMvc.perform(get("/portal/terms").cookie(fixture.sessionCookie())).andExpect(status().isOk());
            // 旧versionでの同意は拒否、現行で同意 → termsPending false
            CsrfPair csrf = fetchPortalCsrf(mockMvc);
            mockMvc.perform(portalPost("/api/portal/auth/consent", csrf).cookie(fixture.sessionCookie())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"termsVersion\":\"1\"}"))
                    .andExpect(status().isConflict());
            mockMvc.perform(portalPost("/api/portal/auth/consent", csrf).cookie(fixture.sessionCookie())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"termsVersion\":\"2\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/portal/auth/me").cookie(fixture.sessionCookie()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.termsPending").value(false));
        } finally {
            transactionTemplate.executeWithoutResult(status ->
                    systemConfigService.put("portal.terms.current-version", "1", "test"));
        }
    }

    private String unique() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
