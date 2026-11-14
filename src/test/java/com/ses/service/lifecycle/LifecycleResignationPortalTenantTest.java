package com.ses.service.lifecycle;

import com.ses.common.exception.BusinessException;
import com.ses.dto.lifecycle.ResignationGateResultDto;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.LifecycleCase;
import com.ses.entity.PortalUserEngineerLink;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.PortalUserEngineerLinkMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NF01 N1-4（決定 B-NF01-8・Q-10）: 退社自動処理のポータルsession失効は、管理者が登録した紐付け（V188）があり、
 * 紐付け・ポータル組織とも案件tenantのポータルユーザーだけに行う。emailでは照合しない。
 * 同じemailでも紐付けの無いポータルユーザー・他tenantのユーザーは処理せず、前者はゲートに「要確認」として表示する
 * （NF01 batch 0 L6 の「案件tenantに限定したemail照合」を置き換える）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LifecycleResignationPortalTenantTest {

    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime());

    @Autowired
    private ResignationGateChecker resignationGateChecker;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private EngineerAccountLinkMapper engineerAccountLinkMapper;

    @Autowired
    private PortalUserEngineerLinkMapper portalUserEngineerLinkMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SysUser linkedUser;
    private Engineer engineer;
    private String email;

    @BeforeEach
    void setUp() {
        TenantTestSecurity.bindAs("default", "管理者");
        long suffix = SEQUENCE.incrementAndGet();
        email = "resign-portal-" + suffix + "@example.test";
        linkedUser = SysUser.builder()
                .tenantId("default")
                .username("resign_portal_" + suffix)
                .password("pass")
                .realName("退社ポータル確認")
                .role("要員")
                .email(email)
                .status(1)
                .build();
        sysUserMapper.insert(linkedUser);

        engineer = Engineer.builder()
                .tenantId("default")
                .fullName("退社ポータル要員-" + suffix)
                .status("稼動中")
                .employmentType("正社員")
                .build();
        engineerMapper.insert(engineer);

        EngineerAccountLink link = new EngineerAccountLink();
        link.setTenantId("default");
        link.setEngineerId(engineer.getId());
        link.setSysUserId(linkedUser.getId());
        engineerAccountLinkMapper.insert(link);
    }

    @AfterEach
    void tearDown() {
        TenantTestSecurity.clear();
    }

    @Test
    @DisplayName("他tenantの同一emailポータルユーザーのsessionは退社自動処理で失効しない")
    void foreignTenantPortalSessionIsNotRevoked() {
        Long foreignPortalUserId = portalUserWithActiveSession("tenant-other-portal", email);

        resignationGateChecker.executeAutomaticGateActions(resignationCase("default"), engineer);

        assertNull(revokedAt(foreignPortalUserId), "他tenantのポータルsessionを失効させないこと");
        assertEquals(0, sysUserMapper.selectById(linkedUser.getId()).getStatus(), "退社者の内部アカウントは無効化されること");
    }

    @Test
    @DisplayName("案件tenantで要員と紐付けたポータルユーザーのsessionは失効する")
    void sameTenantPortalSessionIsRevoked() {
        Long portalUserId = portalUserWithActiveSession("default", email);
        registerLink("default", portalUserId);

        resignationGateChecker.executeAutomaticGateActions(resignationCase("default"), engineer);

        assertNotNull(revokedAt(portalUserId), "紐付け済みのポータルsessionは失効すること");
    }

    @Test
    @DisplayName("同tenant・同emailでも紐付けの無いポータルユーザーは失効させず、ゲートに要確認として表示する")
    void unlinkedSameEmailPortalUserIsListedAsPendingWithoutRevocation() {
        Long unlinkedPortalUserId = portalUserWithActiveSession("default", email);

        ResignationGateResultDto gate = evaluateAsGateOperator();
        resignationGateChecker.executeAutomaticGateActions(resignationCase("default"), engineer);

        assertNull(revokedAt(unlinkedPortalUserId), "紐付けの無いポータルユーザーは自動処理しないこと");
        ResignationGateResultDto.GateItemResult item = portalItem(gate);
        assertTrue(item.isPassed(), "要確認はゲートをブロックしないこと");
        assertTrue(item.isPendingConfirmation());
        assertEquals(List.of(unlinkedPortalUserId), item.getPendingPortalUserIds());
        assertFalse(item.getMessage().contains(email), "要確認の表示にemailを出さないこと");
    }

    @Test
    @DisplayName("紐付けのtenantが案件tenantと違う場合はゲートを不通過にし、自動処理も撤権せず409で止める")
    void foreignTenantLinkFailsTheGateAndAutomaticActions() {
        Long portalUserId = portalUserWithActiveSession("default", "other-" + email);
        registerLink("tenant-other-portal", portalUserId);

        ResignationGateResultDto gate = evaluateAsGateOperator();
        assertFalse(portalItem(gate).isPassed());
        assertFalse(gate.isPassed());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> resignationGateChecker.executeAutomaticGateActions(resignationCase("default"), engineer));
        assertEquals(409, ex.getCode());
        assertNull(revokedAt(portalUserId));
        assertEquals(1, sysUserMapper.selectById(linkedUser.getId()).getStatus(), "不整合のまま内部アカウントも停止しないこと");
    }

    /**
     * ゲート評価は貸与資産の確認（AssetOffboardingService → 外部アカウントの法人scope）で操作者の一意な法人を要求する。
     * AssetOffboardingServiceTest と同じく、法人1の組織に所属するHRを操作者として評価し、その後の自動処理は
     * 従来どおり管理者のcontextへ戻す（法人の決まり方は main code を変えない。R2 N9-P2-1 は W3-11 で扱う）。
     */
    private ResignationGateResultDto evaluateAsGateOperator() {
        SysUser operator = SysUser.builder()
                .tenantId("default")
                .username("resign_portal_gate_hr_" + SEQUENCE.incrementAndGet())
                .password("pass")
                .realName("退社ゲート確認HR")
                .role("HR")
                .status(1)
                .build();
        sysUserMapper.insert(operator);
        TenantTestSecurity.ensureLegalEntity(jdbcTemplate, 1L, operator.getId());
        TenantTestSecurity.bindAs(operator.getId(), operator.getUsername(), "default", "HR");
        try {
            return resignationGateChecker.evaluate(resignationCase("default"), engineer);
        } finally {
            TenantTestSecurity.bindAs("default", "管理者");
        }
    }

    private ResignationGateResultDto.GateItemResult portalItem(ResignationGateResultDto gate) {
        return gate.getItems().stream()
                .filter(item -> "PORTAL_ACCOUNT_LINK".equals(item.getCode()))
                .findFirst()
                .orElseThrow();
    }

    private void registerLink(String tenantId, Long portalUserId) {
        PortalUserEngineerLink link = new PortalUserEngineerLink();
        link.setTenantId(tenantId);
        link.setPortalUserId(portalUserId);
        link.setEngineerId(engineer.getId());
        link.setLinkedBy(1L);
        link.setLinkedAt(LocalDateTime.now());
        portalUserEngineerLinkMapper.insert(link);
    }

    private Long portalUserWithActiveSession(String portalTenantId, String portalEmail) {
        long customerId = 900_000_000L + SEQUENCE.incrementAndGet() % 100_000_000L;
        jdbcTemplate.update("INSERT INTO m_portal_organization (tenant_id, type, customer_id, status) "
                + "VALUES (?, 'CUSTOMER', ?, 'ACTIVE')", portalTenantId, customerId);
        Long orgId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_portal_organization WHERE customer_id = ?", Long.class, customerId);
        jdbcTemplate.update("INSERT INTO t_portal_user (portal_org_id, email, status, mfa_policy, notify_email, version) "
                + "VALUES (?, ?, 'ACTIVE', 'REQUIRED', 1, 0)", orgId, portalEmail);
        Long portalUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_portal_user WHERE email = ?", Long.class, portalEmail);
        LocalDateTime now = LocalDateTime.now();
        String tokenHash = String.format("%064d", SEQUENCE.incrementAndGet());
        jdbcTemplate.update("INSERT INTO t_portal_session (user_id, token_hash, issued_at, last_seen_at, idle_expires_at, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", portalUserId, tokenHash, now, now, now.plusHours(1), now.plusHours(12));
        return portalUserId;
    }

    private LocalDateTime revokedAt(Long portalUserId) {
        return jdbcTemplate.queryForObject("SELECT revoked_at FROM t_portal_session WHERE user_id = ?",
                LocalDateTime.class, portalUserId);
    }

    private LifecycleCase resignationCase(String tenantId) {
        return LifecycleCase.builder()
                .tenantId(tenantId)
                .lifecycleType("RESIGNATION")
                .engineerId(engineer.getId())
                .anchorDate(LocalDate.now())
                .status("ACTIVE")
                .build();
    }
}
