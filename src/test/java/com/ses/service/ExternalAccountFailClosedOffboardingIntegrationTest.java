package com.ses.service;

import com.ses.BaseIntegrationTest;
import com.ses.dto.asset.OffboardingClearanceResultDto;
import com.ses.entity.Engineer;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.ExternalAccountSystem;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.ExternalAccountSystemMapper;
import com.ses.service.impl.AssetOffboardingServiceImpl;
import com.ses.service.impl.ExternalAccountServiceImpl;
import com.ses.service.provider.ExternalAccountProviderClient;
import com.ses.service.provider.impl.FailClosedExternalAccountProviderClient;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** fail-closed providerから実台帳・退社blockerまでのH2回帰。 */
@TestPropertySource(properties = {"external-account.provider=none", "app.security.oidc.tenant-id="})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExternalAccountFailClosedOffboardingIntegrationTest extends BaseIntegrationTest {

    private String tenantId;
    private String username;
    private Long legalEntityId;
    private Long actorUserId;
    private Long engineerId;
    private Long systemId;
    private Long referenceId;

    @Autowired private ExternalAccountProviderClient providerClient;
    @Autowired private ExternalAccountService externalAccountService;
    @Autowired private AssetOffboardingService assetOffboardingService;
    @Autowired private ExternalAccountReferenceMapper referenceMapper;
    @Autowired private ExternalAccountSystemMapper systemMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void bindIsolatedTenantAndCreateRealActorAndEngineer() {
        String suffix = Long.toUnsignedString(System.nanoTime());
        tenantId = "nf09-it-" + suffix;
        username = "nf09-it-" + suffix;
        legalEntityId = Math.abs(System.nanoTime()) + 1_000_000L;
        jdbcTemplate.update("""
                INSERT INTO sys_user (tenant_id, username, password, real_name, role, status, deleted_flag)
                VALUES (?, ?, 'N/A', 'NF09 fail-closed integration', 'HR', 1, 0)
                """, tenantId, username);
        actorUserId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        jdbcTemplate.update("""
                INSERT INTO m_organization_unit (tenant_id, legal_entity_id, code, name, type, valid_from, status, deleted_flag)
                VALUES (1, ?, ?, 'NF09 isolated integration scope', '法人', '2020-01-01', '有効', 0)
                """, legalEntityId, username);
        Long organizationId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_organization_unit WHERE code = ?", Long.class, username);
        jdbcTemplate.update("""
                INSERT INTO t_user_organization (user_id, organization_id, primary_flag, valid_from, deleted_flag)
                VALUES (?, ?, 1, '2020-01-01', 0)
                """, actorUserId, organizationId);
        Engineer engineer = Engineer.builder().tenantId(tenantId).legalEntityId(legalEntityId)
                .fullName("NF09 fail-closed target " + suffix).employmentType("正社員").status("Bench").build();
        engineerMapper.insert(engineer);
        engineerId = engineer.getId();
        TenantTestSecurity.bindAs(actorUserId, username, tenantId, "HR");
    }

    @AfterEach
    void cleanOnlyFixtureRowsAndSecurityContext() {
        TenantTestSecurity.clear();
        if (referenceId != null) jdbcTemplate.update("DELETE FROM t_external_account_reference WHERE id = ?", referenceId);
        if (systemId != null) jdbcTemplate.update("DELETE FROM m_external_account_system WHERE id = ?", systemId);
        if (engineerId != null) jdbcTemplate.update("DELETE FROM t_engineer WHERE id = ?", engineerId);
        if (actorUserId != null) {
            jdbcTemplate.update("DELETE FROM t_user_organization WHERE user_id = ?", actorUserId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", actorUserId);
        }
        if (username != null) jdbcTemplate.update("DELETE FROM m_organization_unit WHERE code = ?", username);
    }

    @RepeatedTest(2)
    void failClosedRequestAndUnknownPollKeepOffboardingBlockedUntilHumanConfirmation() {
        assertThat(providerClient).isInstanceOf(FailClosedExternalAccountProviderClient.class);
        assertThat(AopUtils.getTargetClass(externalAccountService)).isEqualTo(ExternalAccountServiceImpl.class);
        assertThat(AopUtils.getTargetClass(assetOffboardingService)).isEqualTo(AssetOffboardingServiceImpl.class);

        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("NF09_FAILCLOSED_" + tenantId)
                .systemName("fail-closed integration fixture")
                .systemType("IDP")
                .build();
        systemMapper.insert(system);
        systemId = system.getId();
        ExternalAccountReference reference = ExternalAccountReference.builder()
                .tenantId(tenantId)
                .legalEntityId(legalEntityId)
                .systemId(systemId)
                .accountIdentifier(tenantId + "@example.invalid")
                .assigneeType("ENGINEER")
                .assigneeId(engineerId)
                .status("ACTIVE")
                .build();
        referenceMapper.insert(reference);
        referenceId = reference.getId();

        assetOffboardingService.triggerOffboardingRevocations(engineerId, actorUserId);

        ExternalAccountReference afterRequest = referenceMapper.selectByIdAndScope(referenceId, tenantId, legalEntityId);
        assertThat(afterRequest.getStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(afterRequest.getRevokeRequestedAt()).isNotNull();
        assertThat(afterRequest.getRevokeRequestedBy()).isEqualTo(actorUserId);
        assertThat(afterRequest.getRevokeConfirmedAt()).isNull();
        assertThat(afterRequest.getRetryCount()).isZero();

        int confirmedByPoll = externalAccountService.processPendingRevokePollJob();

        ExternalAccountReference afterPoll = referenceMapper.selectByIdAndScope(referenceId, tenantId, legalEntityId);
        assertThat(confirmedByPoll).isZero();
        assertThat(afterPoll.getStatus()).isEqualTo("UNKNOWN");
        assertThat(afterPoll.getRevokeConfirmedAt()).isNull();
        assertThat(afterPoll.getRevokeConfirmedBy()).isNull();
        assertThat(afterPoll.getActorType()).isNull();
        assertThat(afterPoll.getConfirmationSource()).isNull();
        assertThat(afterPoll.getRevokeConfirmedSource()).isNull();
        assertThat(afterPoll.getRetryCount()).isEqualTo(1);
        assertThat(afterPoll.getNextRetryAt()).isAfter(LocalDateTime.now().plusMinutes(9));

        // この機能に外部アカウント専用event tableはない。永続confirmation attributionを全列で検証する。
        OffboardingClearanceResultDto firstCheck = assetOffboardingService
                .checkOffboardingClearance(engineerId, null, null);
        OffboardingClearanceResultDto repeatedCheck = assetOffboardingService
                .checkOffboardingClearance(engineerId, null, null);
        assertThat(firstCheck.isClearancePassed()).isFalse();
        assertThat(firstCheck.getUnrevokedAccountCount()).isEqualTo(1);
        assertThat(firstCheck.isWaived()).isFalse();
        assertThat(repeatedCheck.isClearancePassed()).isFalse();
        assertThat(repeatedCheck.getUnrevokedAccountCount()).isEqualTo(1);
        assertThat(repeatedCheck.isWaived()).isFalse();
    }
}
