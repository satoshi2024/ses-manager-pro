package com.ses.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.Engineer;
import com.ses.entity.LifecycleCase;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalUser;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NF01 N1-4（決定 Q-10）: ポータル管理画面からのポータルユーザーと要員の紐付けの登録・解除。
 * 管理者だけが操作でき（静的限定と業務判定の両方）、対象はtenant内に限り、登録・解除は監査へ必須記録される。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@com.ses.test.DisableDefaultTenantTestContext
class PortalEngineerLinkApiTest extends PortalTestSupport {

    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private LifecycleCaseMapper lifecycleCaseMapper;
    @Autowired private ObjectMapper objectMapper;

    private SysUser admin;
    private PortalUser portalUser;
    private Engineer engineer;

    @BeforeEach
    void setUp() {
        com.ses.service.accounting.AccountingTenantContextHolder.setTenantId("default");
        long seq = SEQUENCE.incrementAndGet();
        admin = SysUser.builder().tenantId("default").username("pel_admin_" + seq).password("pass")
                .realName("紐付け管理者").role("管理者").status(1).build();
        sysUserMapper.insert(admin);
        PortalOrganization org = createCustomerOrg("pel-" + seq);
        portalUser = createUser(org, "pel-" + seq + "@example.test");
        engineer = engineer("default", "紐付け要員-" + seq);
    }

    @AfterEach
    void clearTenantContext() {
        com.ses.service.accounting.AccountingTenantContextHolder.clear();
    }

    @Override
    protected JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    @Test
    void 管理者は紐付けを登録して解除でき両方が監査に必須記録される() throws Exception {
        JsonNode registered = data(mockMvc.perform(post("/api/portal-admin/users/" + portalUser.getId() + "/engineer-links")
                        .with(as(admin, "管理者")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("engineerId", engineer.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.engineerResigned").value(false))
                .andReturn().getResponse().getContentAsString());
        long linkId = registered.path("id").asLong();
        assertEquals(engineer.getId().longValue(), registered.path("engineerId").asLong());

        Map<String, Object> registerAudit = audit("PORTAL_ENGINEER_LINK_REGISTERED", linkId);
        assertEquals("HUMAN", registerAudit.get("ACTOR_TYPE"));
        assertEquals("MANUAL_API", registerAudit.get("CONFIRMATION_SOURCE"));
        assertEquals(admin.getId(), ((Number) registerAudit.get("HUMAN_USER_ID")).longValue());
        assertEquals("PORTAL_USER_ENGINEER_LINK", registerAudit.get("REFERENCE_TYPE"));

        mockMvc.perform(get("/api/portal-admin/users/" + portalUser.getId() + "/engineer-links").with(as(admin, "管理者")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(linkId))
                .andExpect(jsonPath("$.data[0].engineerName").value(engineer.getFullName()));

        mockMvc.perform(post("/api/portal-admin/engineer-links/" + linkId + "/release")
                        .with(as(admin, "管理者")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT released_at FROM t_portal_user_engineer_link WHERE id = ?", java.time.LocalDateTime.class, linkId));
        assertEquals(admin.getId(), jdbcTemplate.queryForObject(
                "SELECT released_by FROM t_portal_user_engineer_link WHERE id = ?", Long.class, linkId));
        assertEquals("RELEASED", audit("PORTAL_ENGINEER_LINK_RELEASED", linkId).get("AFTER_STATE"));

        // 解除済みの再解除は409。解除後は同じポータルユーザーを再び紐付けられる（履歴は残る）。
        mockMvc.perform(post("/api/portal-admin/engineer-links/" + linkId + "/release")
                        .with(as(admin, "管理者")).with(csrf()))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/portal-admin/users/" + portalUser.getId() + "/engineer-links")
                        .with(as(admin, "管理者")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("engineerId", engineer.getId()))))
                .andExpect(status().isOk());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_portal_user_engineer_link WHERE portal_user_id = ?", Integer.class, portalUser.getId()));
    }

    @Test
    void 現行の紐付けがあるポータルユーザーへの重複登録は409で拒否する() throws Exception {
        register(engineer.getId()).andExpect(status().isOk());
        Engineer another = engineer("default", "別要員-" + SEQUENCE.incrementAndGet());

        register(another.getId()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        register(engineer.getId()).andExpect(status().isConflict());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_portal_user_engineer_link "
                + "WHERE portal_user_id = ? AND released_at IS NULL", Integer.class, portalUser.getId()));
    }

    @Test
    void 他tenantの要員とポータルユーザーは存在しないものと同じ404で拒否し紐付けも監査も残さない() throws Exception {
        Engineer foreignEngineer = engineer("tenant-pel-foreign", "他tenant要員");
        register(foreignEngineer.getId()).andExpect(status().isNotFound());

        PortalOrganization foreignOrg = createCustomerOrg("pel-foreign-" + SEQUENCE.incrementAndGet());
        jdbcTemplate.update("UPDATE m_portal_organization SET tenant_id = 'tenant-pel-foreign' WHERE id = ?", foreignOrg.getId());
        PortalUser foreignPortalUser = createUser(foreignOrg, "pel-foreign-" + SEQUENCE.incrementAndGet() + "@example.test");
        mockMvc.perform(post("/api/portal-admin/users/" + foreignPortalUser.getId() + "/engineer-links")
                        .with(as(admin, "管理者")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("engineerId", engineer.getId()))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/portal-admin/users/" + foreignPortalUser.getId() + "/engineer-links").with(as(admin, "管理者")))
                .andExpect(status().isNotFound());

        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_portal_user_engineer_link "
                + "WHERE portal_user_id IN (?, ?)", Integer.class, portalUser.getId(), foreignPortalUser.getId()));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_audit_log WHERE application_code = "
                + "'PORTAL_ENGINEER_LINK_REGISTERED' AND human_user_id = ?", Integer.class, admin.getId()));
    }

    @Test
    void 営業は紐付けの参照も登録も解除もできない() throws Exception {
        SysUser sales = SysUser.builder().tenantId("default").username("pel_sales_" + SEQUENCE.incrementAndGet())
                .password("pass").realName("営業").role("営業").status(1).build();
        sysUserMapper.insert(sales);

        mockMvc.perform(get("/api/portal-admin/users/" + portalUser.getId() + "/engineer-links").with(as(sales, "営業")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/portal-admin/users/" + portalUser.getId() + "/engineer-links")
                        .with(as(sales, "営業")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("engineerId", engineer.getId()))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/portal-admin/engineer-links/1/release").with(as(sales, "営業")).with(csrf()))
                .andExpect(status().isForbidden());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_portal_user_engineer_link "
                + "WHERE portal_user_id = ?", Integer.class, portalUser.getId()));
    }

    @Test
    void 退社完了済みの要員への紐付けは拒否せず要確認として返し監査にも残す() throws Exception {
        LifecycleCase completed = LifecycleCase.builder().tenantId("default").caseNo("LC-PEL-" + SEQUENCE.incrementAndGet())
                .lifecycleType("RESIGNATION").engineerId(engineer.getId()).templateId(1L).templateVersion(1)
                .anchorDate(LocalDate.now().minusDays(1)).status("COMPLETED").title("退社完了済み")
                .applicantUserId(admin.getId()).engineerSnapshotJson("{}").version(0).build();
        lifecycleCaseMapper.insert(completed);

        JsonNode registered = data(register(engineer.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.engineerResigned").value(true))
                .andReturn().getResponse().getContentAsString());

        String afterState = (String) audit("PORTAL_ENGINEER_LINK_REGISTERED", registered.path("id").asLong()).get("AFTER_STATE");
        assertNotNull(afterState);
        assertEquals(true, afterState.contains("engineerResigned=true"));
    }

    @Test
    void 要員を指定しない登録は400で拒否する() throws Exception {
        mockMvc.perform(post("/api/portal-admin/users/" + portalUser.getId() + "/engineer-links")
                        .with(as(admin, "管理者")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        assertNull(jdbcTemplate.query("SELECT id FROM t_portal_user_engineer_link WHERE portal_user_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, portalUser.getId()));
    }

    private org.springframework.test.web.servlet.ResultActions register(Long engineerId) throws Exception {
        return mockMvc.perform(post("/api/portal-admin/users/" + portalUser.getId() + "/engineer-links")
                .with(as(admin, "管理者")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("engineerId", engineerId))));
    }

    private RequestPostProcessor as(SysUser user, String role) {
        return authentication(TenantTestSecurity.authentication(user.getId(), user.getUsername(), "default", role));
    }

    private Engineer engineer(String tenantId, String name) {
        Engineer created = Engineer.builder().tenantId(tenantId).fullName(name)
                .status("稼動中").employmentType("正社員").build();
        engineerMapper.insert(created);
        return created;
    }

    private Map<String, Object> audit(String applicationCode, long linkId) {
        return jdbcTemplate.queryForMap("SELECT actor_type, confirmation_source, human_user_id, reference_type, "
                + "before_state, after_state FROM t_audit_log WHERE application_code = ? AND reference_id = ?",
                applicationCode, linkId);
    }

    private JsonNode data(String body) throws Exception {
        return objectMapper.readTree(body).path("data");
    }
}
