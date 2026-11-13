package com.ses.config;

import com.ses.common.exception.BusinessException;
import com.ses.controller.api.SystemConfigApiController;
import com.ses.entity.SystemConfig;
import com.ses.mapper.UserExternalIdentityMapper;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 初回bootstrapの完了marker（{@link ProductionBootstrapCompletionLatch}、review P2-A）と、
 * 起動時検査が数えるOIDC管理者のquery（{@link UserExternalIdentityMapper#selectApprovedActiveUsernamesByRole}）を
 * H2の実 {@code m_system_config} / {@code t_audit_log} / {@code t_user_external_identity} で検証する。
 * 全行は自分で投入し、test transactionでrollbackする。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductionBootstrapPersistenceTest {

    private static final String KEY = ProductionBootstrapCompletionLatch.CONFIG_KEY;

    @Autowired
    private ProductionBootstrapCompletionLatch latch;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SystemConfigService systemConfigService;
    @Autowired
    private SystemConfigApiController systemConfigApiController;
    @Autowired
    private UserExternalIdentityMapper userExternalIdentityMapper;

    @Test
    void 完了markerは一度だけ書き込み再実行では上書きせず完了を監査する() {
        jdbcTemplate.update("DELETE FROM m_system_config WHERE config_key = ?", KEY);
        assertThat(latch.findCompletedAt()).isEmpty();
        long auditsBefore = auditCount("BOOTSTRAP_COMPLETED");

        assertThat(latch.markCompletedIfAbsent()).isTrue();
        String written = configValue();
        assertThatCode(() -> OffsetDateTime.parse(written)).as("offset付きISO-8601").doesNotThrowAnyException();
        assertThat(latch.findCompletedAt()).contains(written);
        assertThat(auditCount("BOOTSTRAP_COMPLETED")).isEqualTo(auditsBefore + 1);

        assertThat(latch.markCompletedIfAbsent()).as("冪等: 既にあれば書かない").isFalse();
        assertThat(configValue()).isEqualTo(written);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM m_system_config WHERE config_key = ?",
                Long.class, KEY)).isEqualTo(1L);
        assertThat(auditCount("BOOTSTRAP_COMPLETED")).as("2回目は監査もしない").isEqualTo(auditsBefore + 1);
    }

    @Test
    void 値が空の完了markerも完了済みとして扱い上書きしない() {
        jdbcTemplate.update("DELETE FROM m_system_config WHERE config_key = ?", KEY);
        jdbcTemplate.update("INSERT INTO m_system_config (config_key, config_value, description) VALUES (?, NULL, NULL)", KEY);

        assertThat(latch.findCompletedAt()).as("rowが有れば完了済み（fail-closed）").contains("");
        assertThat(latch.markCompletedIfAbsent()).isFalse();
        assertThat(configValue()).isNull();
    }

    @Test
    void 再開をticketと期限付きで必須監査に残す() {
        long before = auditCount("BOOTSTRAP_REOPENED");

        latch.recordReopened("SEC-1234", Instant.parse("2026-11-14T09:00:00Z"), "2026-11-10T12:00:00+09:00");

        assertThat(auditCount("BOOTSTRAP_REOPENED")).isEqualTo(before + 1);
        String uri = jdbcTemplate.queryForObject("SELECT uri FROM t_audit_log WHERE application_code = ? "
                + "ORDER BY id DESC LIMIT 1", String.class, "BOOTSTRAP_REOPENED");
        assertThat(uri).contains("reopen-ticket=SEC-1234").contains("expires-at=2026-11-14T09:00:00Z")
                .contains("completed-at=2026-11-10T12:00:00+09:00");
        assertThat(jdbcTemplate.queryForObject("SELECT method FROM t_audit_log WHERE application_code = ? "
                + "ORDER BY id DESC LIMIT 1", String.class, "BOOTSTRAP_REOPENED")).isEqualTo("STARTUP");
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 完了markerはシステム設定画面から閲覧も更新もできない() {
        jdbcTemplate.update("DELETE FROM m_system_config WHERE config_key = ?", KEY);
        jdbcTemplate.update("INSERT INTO m_system_config (config_key, config_value, description) VALUES (?, ?, ?)",
                KEY, "2026-11-10T12:00:00+09:00", "test");

        List<SystemConfig> listed = systemConfigApiController.list().getData();
        assertThat(listed).extracting(SystemConfig::getConfigKey).doesNotContain(KEY);
        assertThatThrownBy(() -> systemConfigService.updateAll(List.of(new SystemConfig(KEY, "", null))))
                .isInstanceOf(BusinessException.class);
        assertThat(configValue()).isEqualTo("2026-11-10T12:00:00+09:00");
    }

    @Test
    void OIDC管理者のqueryはAPPROVEDのlinkを持つ有効な管理者だけを重複なく返す() {
        jdbcTemplate.update("INSERT INTO m_identity_provider (tenant_id, provider_type, issuer_uri, client_id, enabled) "
                + "VALUES ('default', 'OIDC', 'https://idp.invalid/p2a-persistence', 'p2a-client', 1)");
        Long providerId = jdbcTemplate.queryForObject("SELECT id FROM m_identity_provider WHERE issuer_uri = ?",
                Long.class, "https://idp.invalid/p2a-persistence");
        Long reviewer = insertUser("p2a-reviewer", "管理者", 1, 0);

        link(providerId, insertUser("p2a-ops-1", "管理者", 1, 0), "sub-ops-1", "APPROVED", reviewer);
        Long ops2 = insertUser("p2a-ops-2", "管理者", 1, 0);
        link(providerId, ops2, "sub-ops-2a", "APPROVED", reviewer);
        link(providerId, ops2, "sub-ops-2b", "APPROVED", reviewer);
        link(providerId, insertUser("p2a-quarantined", "管理者", 1, 0), "sub-q", "QUARANTINED", null);
        link(providerId, insertUser("p2a-disabled", "管理者", 0, 0), "sub-d", "APPROVED", reviewer);
        link(providerId, insertUser("p2a-deleted", "管理者", 1, 1), "sub-x", "APPROVED", reviewer);
        link(providerId, insertUser("p2a-sales", "営業", 1, 0), "sub-s", "APPROVED", reviewer);

        assertThat(userExternalIdentityMapper.selectApprovedActiveUsernamesByRole("default", providerId, "管理者"))
                .containsExactlyInAnyOrder("p2a-ops-1", "p2a-ops-2");
        assertThat(userExternalIdentityMapper.selectApprovedActiveUsernamesByRole("other-tenant", providerId, "管理者"))
                .isEmpty();
    }

    // ---------------------------------------------------------------- helper

    private String configValue() {
        return jdbcTemplate.queryForObject("SELECT config_value FROM m_system_config WHERE config_key = ?",
                String.class, KEY);
    }

    private long auditCount(String applicationCode) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_audit_log WHERE application_code = ?",
                Long.class, applicationCode);
        return count == null ? 0 : count;
    }

    private Long insertUser(String username, String role, int status, int deletedFlag) {
        jdbcTemplate.update("INSERT INTO sys_user (username, password, real_name, role, email, tenant_id, status, deleted_flag) "
                + "VALUES (?, 'p2a-test-only', ?, ?, ?, 'default', ?, ?)",
                username, username, role, username + "@ses.test", status, deletedFlag);
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private void link(Long providerId, Long userId, String subject, String reviewStatus, Long reviewer) {
        jdbcTemplate.update("INSERT INTO t_user_external_identity (tenant_id, user_id, provider_id, subject, "
                        + "review_status, reviewed_at, reviewed_by, deleted_flag) "
                        + "VALUES ('default', ?, ?, ?, ?, " + (reviewer == null ? "NULL" : "CURRENT_TIMESTAMP") + ", ?, 0)",
                userId, providerId, subject, reviewStatus, reviewer);
    }
}
