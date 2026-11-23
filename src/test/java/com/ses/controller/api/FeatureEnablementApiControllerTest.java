package com.ses.controller.api;

import com.ses.config.feature.Feature;
import com.ses.entity.SysUser;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.service.FeatureEnablementActor;
import com.ses.featureenablement.state.FeatureEnablementOverlay;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.config.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FE-A09（feature-enablement-console design §15.1・FE-RQ-011・012）: 機能の開放の API の権限と応答。管理者は 200、営業・HR・マネージャーは
 * group の有無（管理者相当の '*' を持つ誤った group を含む）に関わらず 403、未認証は 401（画面は login への 302）、CSRF の無い POST は
 * 403。一覧は実効値と閉じている理由を返し、署名者の氏名を返さない（詳細だけ）。再評価は状態を遷移させず、1分に1回まで。試験の
 * transaction の中で入れた行だけを読み、rollback で消す（MockMvc の要求は同じ thread で同じ transaction に加わる）。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
class FeatureEnablementApiControllerTest {

    private static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);
    private static final LocalDate EXPIRES = LocalDate.of(2027, 10, 1);
    private static final String SIGNER_NAME = "署名 花子";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private FeatureEnablementSnapshotHolder holder;
    @Autowired
    private FeatureEnablementOverlay overlay;
    @Autowired
    private FeatureEnablementTestConfiguration.MutableTestClock clock;

    private FeatureEnablementH2Fixture fixture;
    private FeatureEnablementActor admin;
    private FeatureEnablementActor otherAdmin;

    @BeforeEach
    void setUp() {
        clock.reset();
        fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
        admin = fixture.admin();
        otherAdmin = fixture.admin();
    }

    @AfterTransaction
    void restoreSharedState() {
        for (Feature feature : Feature.values()) {
            overlay.open(feature, FeatureEnablementOverlay.AUTO_CLOSE_PENDING);
        }
        overlay.clearAll(FeatureEnablementOverlay.EVALUATION_UNVERIFIABLE);
        holder.reload();
    }

    @Test
    void 管理者の一覧は実効値と閉じている理由を返し署名者の氏名は詳細だけに出す() throws Exception {
        fixture.confirmedSignOffs(Feature.NF04, SIGNED, EXPIRES);
        fixture.openDirectly(Feature.NF04, admin, otherAdmin);
        jdbc.update("UPDATE t_feature_sign_off SET signer_name = ? WHERE tenant_id = 'default' AND kind_code = 'NF04_SRE' "
                + "AND status = 'CONFIRMED'", SIGNER_NAME);
        Long signOffId = fixture.confirmedId("NF04_SRE");
        holder.reload();

        MvcResult units = mockMvc.perform(get("/api/feature-enablement/units").with(as(admin, "管理者")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String body = units.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"key\":\"NF04\"").contains("\"effectiveOpen\":true")
                .contains("\"key\":\"NF03\"").contains("\"state\":\"CLOSED\"").contains("state.CLOSED")
                .doesNotContain(SIGNER_NAME);
        assertThat(mockMvc.perform(get("/api/feature-enablement/sign-offs").with(as(admin, "管理者")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .as("署名の一覧に氏名を返さない（FE-AC-011-02）").doesNotContain(SIGNER_NAME).contains("NF04_SRE");
        String detail = mockMvc.perform(get("/api/feature-enablement/sign-offs/" + signOffId).with(as(admin, "管理者")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(detail).contains(SIGNER_NAME).doesNotContain("storedName").doesNotContain("originalName");
        mockMvc.perform(get("/api/feature-enablement/units/NF04").with(as(admin, "管理者")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unit.key").value("NF04"))
                .andExpect(jsonPath("$.data.signOffs[0].status").value("CONFIRMED"));
        mockMvc.perform(get("/api/feature-enablement/catalog").with(as(admin, "管理者")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.manualCloseReasons[0]").value("MANUAL_OWNER_DECISION"));
        mockMvc.perform(get("/api/feature-enablement/events").param("size", "5000").with(as(admin, "管理者")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(200));
    }

    @Test
    void 営業とHRとマネージャーはgroupの有無に関わらず画面もAPIも403() throws Exception {
        long copiedAdminGroup = copiedAdminGroup();
        for (String role : List.of("営業", "HR", "マネージャー")) {
            FeatureEnablementActor withoutGroup = fixture.user(role);
            FeatureEnablementActor withGroup = fixture.user(role);
            jdbc.update("INSERT INTO t_user_permission_group (tenant_id, user_id, group_id, assigned_at, deleted_flag) "
                    + "VALUES ('default', ?, ?, CURRENT_TIMESTAMP, 0)", withGroup.userId(), copiedAdminGroup);
            for (FeatureEnablementActor user : List.of(withoutGroup, withGroup)) {
                mockMvc.perform(get("/api/feature-enablement/units").with(as(user, role)))
                        .andExpect(status().isForbidden());
                mockMvc.perform(post("/api/feature-enablement/requests").with(as(user, role)).with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"type\":\"OPEN\",\"target\":\"NF03\"}"))
                        .andExpect(status().isForbidden());
                mockMvc.perform(get("/feature-enablement").with(as(user, role))).andExpect(status().isForbidden());
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_feature_enablement_request WHERE tenant_id = 'default' "
                + "AND target_key = 'NF03'", Long.class)).isZero();
    }

    @Test
    void 未認証のAPIは401で画面はloginへ送りCSRFの無いPOSTは403() throws Exception {
        mockMvc.perform(get("/api/feature-enablement/units")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/feature-enablement").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/api/feature-enablement/units/NF04/evaluate").with(as(admin, "管理者")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 再評価は結果を返すだけで状態を遷移させず1分に1回まで() throws Exception {
        fixture.confirmedSignOffs(Feature.NF04, SIGNED, EXPIRES);
        fixture.openDirectly(Feature.NF04, admin, otherAdmin);
        jdbc.update("UPDATE t_feature_sign_off SET status = 'REVOKED', ended_by = ?, ended_at = TIMESTAMP "
                + "'2026-11-23 09:00:00', end_reason = 'TEST' WHERE tenant_id = 'default' AND kind_code = 'NF04_PRIVACY' "
                + "AND status = 'CONFIRMED'", otherAdmin.userId());
        holder.reload();
        long eventsBefore = fixture.countEvents("FEATURE", "AUTO_CLOSED");

        mockMvc.perform(post("/api/feature-enablement/units/NF04/evaluate").with(as(admin, "管理者")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.purpose").value("DISPLAY"))
                .andExpect(jsonPath("$.data.failing").value(org.hamcrest.Matchers.greaterThan(0)));

        assertThat(fixture.state(Feature.NF04)).as("再評価は状態を遷移させない（閉じるのは定期の評価）").isEqualTo("OPEN");
        assertThat(fixture.countEvents("FEATURE", "AUTO_CLOSED")).isEqualTo(eventsBefore);
        mockMvc.perform(get("/api/feature-enablement/units").with(as(admin, "管理者")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"purpose\":\"DISPLAY\"")));
        mockMvc.perform(post("/api/feature-enablement/units/NF04/evaluate").with(as(admin, "管理者")).with(csrf()))
                .andExpect(status().isTooManyRequests());
        clock.advance(java.time.Duration.ofMinutes(1));
        mockMvc.perform(post("/api/feature-enablement/units/NF04/evaluate").with(as(admin, "管理者")).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void 申請者本人の承認は403で別の管理者が承認し署名の登録と確認と文書の取得ができる() throws Exception {
        fixture.confirmedSignOffs(Feature.NF03, SIGNED, EXPIRES);
        MvcResult requested = mockMvc.perform(post("/api/feature-enablement/requests").with(as(admin, "管理者"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"OPEN\",\"target\":\"NF03\",\"reason\":\"試験\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.mine").value(true))
                .andReturn();
        Number requestId = com.jayway.jsonpath.JsonPath.read(requested.getResponse().getContentAsString(
                StandardCharsets.UTF_8), "$.data.id");

        mockMvc.perform(post("/api/feature-enablement/requests/" + requestId + "/approve").with(as(admin, "管理者"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(post("/api/feature-enablement/requests/" + requestId + "/approve").with(as(otherAdmin, "管理者"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk());
        assertThat(fixture.state(Feature.NF03)).isEqualTo("OPEN");

        byte[] pdf = "%PDF-1.7\n1 0 obj<<>>endobj\n% api\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        MvcResult registered = mockMvc.perform(multipart("/api/feature-enablement/sign-offs")
                        .file(new MockMultipartFile("file", "sre.pdf", "application/pdf", pdf))
                        .param("kindCode", "NF04_SRE").param("reference", "SRE-2026-1122").param("signerRole", "SRE")
                        .param("signerName", SIGNER_NAME).param("signedOn", "2026-11-22").param("expiresOn", "2027-11-22")
                        .with(as(admin, "管理者")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REGISTERED"))
                .andReturn();
        String registeredBody = registered.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(registeredBody).doesNotContain(SIGNER_NAME);
        Number signOffId = com.jayway.jsonpath.JsonPath.read(registeredBody, "$.data.id");
        mockMvc.perform(post("/api/feature-enablement/sign-offs/" + signOffId + "/confirm").with(as(otherAdmin, "管理者"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        mockMvc.perform(get("/api/feature-enablement/sign-offs/" + signOffId + "/document").with(as(admin, "管理者")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(pdf));
    }

    /** A2 の復審 P3-3: file の部を欠いた署名の登録は Spring の既定の 400 ではなく、文書が必要という業務の 400 を JSON で返す。 */
    @Test
    void 文書の部を欠いた署名の登録は文書が必要という400で行を作らない() throws Exception {
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM t_feature_sign_off WHERE tenant_id = ?",
                Integer.class, FeatureEnablementH2Fixture.TENANT);
        mockMvc.perform(multipart("/api/feature-enablement/sign-offs")
                        .param("kindCode", "NF04_SRE").param("reference", "SRE-2026-1123").param("signerRole", "SRE")
                        .param("signerName", SIGNER_NAME).param("signedOn", "2026-11-22").param("expiresOn", "2027-11-22")
                        .with(as(admin, "管理者")).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("署名の文書（PDF）を添付してください"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_feature_sign_off WHERE tenant_id = ?", Integer.class,
                FeatureEnablementH2Fixture.TENANT)).isEqualTo(before);
    }

    /** 管理者の '*' を誤って複製した group（非管理者に付けても SecurityConfig と isAdminOnlyAction が拒否する）。 */
    private long copiedAdminGroup() {
        String key = "fe-copied-admin-" + System.nanoTime();
        jdbc.update("INSERT INTO m_permission_group (tenant_id, group_key, group_name, enabled, deleted_flag) "
                + "VALUES ('default', ?, '複製された管理者group', 1, 0)", key);
        long id = jdbc.queryForObject("SELECT id FROM m_permission_group WHERE tenant_id = 'default' AND group_key = ?",
                Long.class, key);
        jdbc.update("INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag) "
                + "VALUES ('default', ?, '*', 0, 0)", id);
        return id;
    }

    private static RequestPostProcessor as(FeatureEnablementActor actor, String role) {
        SysUser user = new SysUser();
        user.setId(actor.userId());
        user.setUsername(actor.username());
        user.setPassword("pw");
        user.setRole(role);
        user.setStatus(1);
        user.setTenantId(FeatureEnablementH2Fixture.TENANT);
        user.setDeletedFlag(0);
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
