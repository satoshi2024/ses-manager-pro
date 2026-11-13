package com.ses.service.lifecycle;

import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.LifecycleCase;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * NF01 batch 0 L6: 退社自動処理のポータルsession失効は案件tenantのポータル組織に限定される。
 * 旧実装はemailだけで全tenantのポータルユーザーを検索し、他tenantの同一emailユーザーを誤って失効させた。
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
    @DisplayName("案件tenantのポータル組織に属する同一emailユーザーのsessionは失効する")
    void sameTenantPortalSessionIsRevoked() {
        Long portalUserId = portalUserWithActiveSession("default", email);

        resignationGateChecker.executeAutomaticGateActions(resignationCase("default"), engineer);

        assertNotNull(revokedAt(portalUserId), "案件tenantのポータルsessionは失効すること");
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
