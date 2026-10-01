package com.ses.service;

import com.ses.BaseIntegrationTest;
import com.ses.dto.asset.OffboardingClearanceResultDto;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.ExternalAccountSystem;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.ExternalAccountSystemMapper;
import com.ses.service.provider.ExternalAccountProviderClient;
import com.ses.service.provider.impl.FailClosedExternalAccountProviderClient;
import com.ses.service.impl.AssetOffboardingServiceImpl;
import com.ses.service.impl.ExternalAccountServiceImpl;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.aop.support.AopUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** fail-closed providerから実台帳・退社blockerまでのH2回帰。 */
@TestPropertySource(properties = "external-account.provider=none")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExternalAccountFailClosedOffboardingIntegrationTest extends BaseIntegrationTest {

    private static final Long ENGINEER_ID = 9909L;
    private static final String TEST_USERNAME = "nf09-failclosed-offboarding";

    @Autowired private ExternalAccountProviderClient providerClient;
    @Autowired private ExternalAccountService externalAccountService;
    @Autowired private AssetOffboardingService assetOffboardingService;
    @Autowired private ExternalAccountReferenceMapper referenceMapper;
    @Autowired private ExternalAccountSystemMapper systemMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void bindTenantAndCreateScope() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username = ? AND deleted_flag = 0",
                Integer.class, TEST_USERNAME);
        if (count == null || count == 0) {
            jdbcTemplate.update("""
                    INSERT INTO sys_user (tenant_id, username, password, real_name, role, status, deleted_flag)
                    VALUES ('default', ?, 'N/A', 'NF09 fail-closed test', 'HR', 1, 0)
                    """, TEST_USERNAME);
        }
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ? AND deleted_flag = 0", Long.class, TEST_USERNAME);
        TenantTestSecurity.ensureLegalEntity(jdbcTemplate, 1L, userId);
        TenantTestSecurity.bindAs(userId, TEST_USERNAME, "default", "HR");
    }

    @AfterEach
    void clearTenantAndSecurityContext() {
        com.ses.service.accounting.AccountingTenantContextHolder.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void failClosedRequestAndUnknownPollKeepOffboardingBlockedUntilHumanConfirmation() {
        assertThat(providerClient).isInstanceOf(FailClosedExternalAccountProviderClient.class);
        assertThat(AopUtils.getTargetClass(externalAccountService)).isEqualTo(ExternalAccountServiceImpl.class);
        assertThat(AopUtils.getTargetClass(assetOffboardingService)).isEqualTo(AssetOffboardingServiceImpl.class);

        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("NF09_FAILCLOSED_" + System.nanoTime())
                .systemName("fail-closed integration fixture")
                .systemType("IDP")
                .build();
        systemMapper.insert(system);
        ExternalAccountReference reference = ExternalAccountReference.builder()
                .tenantId("default")
                .legalEntityId(1L)
                .systemId(system.getId())
                .accountIdentifier("nf09-failclosed-9909@example.invalid")
                .assigneeType("ENGINEER")
                .assigneeId(ENGINEER_ID)
                .status("ACTIVE")
                .build();
        referenceMapper.insert(reference);

        assetOffboardingService.triggerOffboardingRevocations(ENGINEER_ID, 1L);

        ExternalAccountReference afterRequest = referenceMapper.selectByIdAndScope(reference.getId(), "default", 1L);
        assertThat(afterRequest.getStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(afterRequest.getRevokeRequestedAt()).isNotNull();
        assertThat(afterRequest.getRevokeConfirmedAt()).isNull();
        assertThat(afterRequest.getRetryCount()).isZero();

        int confirmedByPoll = externalAccountService.processPendingRevokePollJob();

        ExternalAccountReference afterPoll = referenceMapper.selectByIdAndScope(reference.getId(), "default", 1L);
        assertThat(confirmedByPoll).isZero();
        assertThat(afterPoll.getStatus()).isEqualTo("UNKNOWN");
        assertThat(afterPoll.getRevokeConfirmedAt()).isNull();
        assertThat(afterPoll.getRetryCount()).isEqualTo(1);
        assertThat(afterPoll.getNextRetryAt()).isAfter(LocalDateTime.now().plusMinutes(9));

        OffboardingClearanceResultDto firstCheck = assetOffboardingService
                .checkOffboardingClearance(ENGINEER_ID, null, null);
        OffboardingClearanceResultDto repeatedCheck = assetOffboardingService
                .checkOffboardingClearance(ENGINEER_ID, null, null);
        assertThat(firstCheck.isClearancePassed()).isFalse();
        assertThat(firstCheck.getUnrevokedAccountCount()).isEqualTo(1);
        assertThat(firstCheck.isWaived()).isFalse();
        assertThat(repeatedCheck.isClearancePassed()).isFalse();
        assertThat(repeatedCheck.getUnrevokedAccountCount()).isEqualTo(1);
        assertThat(repeatedCheck.isWaived()).isFalse();
    }
}
