package com.ses.service;

import com.ses.BaseIntegrationTest;
import com.ses.common.audit.ActorAttribution;
import com.ses.common.audit.ActorType;
import com.ses.common.audit.ConfirmationSource;
import com.ses.common.exception.BusinessException;
import com.ses.dto.asset.ExternalAccountReferenceDto;
import com.ses.entity.AssetEvent;
import com.ses.entity.AuditLog;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.ExternalAccountSystem;
import com.ses.mapper.AssetEventMapper;
import com.ses.mapper.AuditLogMapper;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.ExternalAccountSystemMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/** NF-09の主体/チャネル分離、DB制約、監査原子性を検証する。 */
class ExternalAccountActorAttributionTest extends BaseIntegrationTest {

    @Autowired
    private ExternalAccountService externalAccountService;

    @Autowired
    private ExternalAccountSystemMapper externalAccountSystemMapper;

    @Autowired
    private ExternalAccountReferenceMapper externalAccountReferenceMapper;

    @Autowired
    private AssetEventMapper assetEventMapper;

    @SpyBean
    private AuditLogMapper auditLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void resetAuditMapper() {
        reset(auditLogMapper);
    }

    @Test
    @DisplayName("NF-09: HUMAN/SYSTEM/PROVIDERの組合せは閉じた値で検証される")
    void attributionUsesClosedActorAndSourcePairs() {
        assertThatThrownBy(() -> new ActorAttribution(
                ActorType.HUMAN, ConfirmationSource.PROVIDER_CALLBACK, 1L, "c", "i"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new ActorAttribution(
                ActorType.SYSTEM, ConfirmationSource.MANUAL_API, null, "c", "i"))
                .isInstanceOf(BusinessException.class);
        assertThat(ActorAttribution.providerCallback("callback-correlation", "provider-event-1").actorType())
                .isEqualTo(ActorType.PROVIDER);
    }

    @Test
    @DisplayName("NF-09: 手動確認は解決不能なprincipalを拒否し、SYSTEMへ降格しない")
    void manualConfirmationRejectsUnresolvedPrincipal() {
        ExternalAccountReference ref = newReference("manual-unresolved");

        assertThatThrownBy(() -> externalAccountService.confirmRevokeManually(
                ref.getId(), 999_999L, "manual-correlation", "manual-idem"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("解決できない");

        ExternalAccountReference unchanged = externalAccountReferenceMapper.selectById(ref.getId());
        assertThat(unchanged.getStatus()).isEqualTo("ACTIVE");
        assertThat(unchanged.getActorType()).isNull();
        assertThat(unchanged.getConfirmationSource()).isNull();
        assertThat(assetEventMapper.selectList(null).stream()
                .filter(event -> ref.getId().equals(event.getReferenceId())))
                .isEmpty();
    }

    @Test
    @DisplayName("NF-09: provider sync/callback/scheduler pollは別チャネルで同じCAS境界を通る")
    void providerAndSchedulerSourcesRemainDistinct() {
        ExternalAccountReference sync = newReference("provider-sync");
        ExternalAccountReference callback = newReference("provider-callback");
        ExternalAccountReference poll = newReference("scheduler-poll");

        ExternalAccountReference syncResult = externalAccountService.confirmRevokeFromProviderSync(
                sync.getId(), "sync-correlation", "sync-idem");
        ExternalAccountReference callbackResult = externalAccountService.confirmRevokeFromProviderCallback(
                callback.getId(), "provider-event-42", "callback-correlation");
        ExternalAccountReference pollResult = externalAccountService.confirmRevokeFromSchedulerPoll(
                poll.getId(), "poll-correlation", "poll-idem");

        assertAttribution(syncResult, ActorType.PROVIDER, ConfirmationSource.PROVIDER_SYNC);
        assertAttribution(callbackResult, ActorType.PROVIDER, ConfirmationSource.PROVIDER_CALLBACK);
        assertAttribution(pollResult, ActorType.SYSTEM, ConfirmationSource.SCHEDULER_POLL);

        List<AssetEvent> events = assetEventMapper.selectList(null).stream()
                .filter(event -> event.getReferenceId() != null)
                .filter(event -> List.of(sync.getId(), callback.getId(), poll.getId()).contains(event.getReferenceId()))
                .toList();
        assertThat(events).hasSize(3);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getActorUserId()).isNull();
            assertThat(event.getHumanUserId()).isNull();
            assertThat(event.getFromStatus()).isEqualTo("ACTIVE");
            assertThat(event.getToStatus()).isEqualTo("REVOKED");
            assertThat(event.getCorrelationId()).isNotBlank();
            assertThat(event.getIdempotencyKey()).isNotBlank();
        });

        List<AuditLog> audits = auditLogMapper.selectList(null).stream()
                .filter(log -> List.of(sync.getId(), callback.getId(), poll.getId()).contains(log.getReferenceId()))
                .toList();
        assertThat(audits).hasSize(3);
        assertThat(audits).allSatisfy(log -> {
            assertThat(log.getReferenceType()).isEqualTo("EXTERNAL_ACCOUNT_REFERENCE");
            assertThat(log.getBeforeState()).isEqualTo("ACTIVE");
            assertThat(log.getAfterState()).isEqualTo("REVOKED");
            assertThat(log.getCorrelationId()).isNotBlank();
            assertThat(log.getIdempotencyKey()).isNotBlank();
            assertThat(log.getHumanUserId()).isNull();
        });

        ExternalAccountReferenceDto dto = ExternalAccountReferenceDto.from(callbackResult);
        assertThat(dto.getActorType()).isEqualTo("PROVIDER");
        assertThat(dto.getConfirmationSource()).isEqualTo("PROVIDER_CALLBACK");
    }

    @Test
    @DisplayName("NF-09: 未確認のアカウントをLEGACY_UNRESOLVEDへ誤分類しない")
    void unconfirmedAccountIsNotMisclassifiedAsLegacy() {
        ExternalAccountReference active = new ExternalAccountReference();
        active.setStatus("ACTIVE");

        ExternalAccountReferenceDto dto = ExternalAccountReferenceDto.from(active);

        assertThat(dto.getActorType()).isNull();
        assertThat(dto.getConfirmationSource()).isNull();
        assertThat(dto.getActorTypeDisplay()).isEqualTo("未確認");
        assertThat(dto.getConfirmationSourceDisplay()).isEqualTo("未確認");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("NF-09: CAS後のイベント/監査の失敗は同一トランザクションで全体rollbackする")
    void auditFailureRollsBackCasAndEvent() {
        ExternalAccountReference ref = newReference("audit-rollback");
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditLogMapper).insert(any(AuditLog.class));

        assertThatThrownBy(() -> externalAccountService.confirmRevokeFromSchedulerPoll(
                ref.getId(), "rollback-correlation", "rollback-idem"))
                .isInstanceOf(IllegalStateException.class);

        ExternalAccountReference unchanged = externalAccountReferenceMapper.selectById(ref.getId());
        assertThat(unchanged.getStatus()).isEqualTo("ACTIVE");
        assertThat(unchanged.getVersion()).isEqualTo(0);
        assertThat(assetEventMapper.selectList(null).stream()
                .filter(event -> ref.getId().equals(event.getReferenceId())))
                .isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("NF-09: 二重schedulerと手動/poll競合はCASで確認証跡を一件に収束させる")
    void concurrentSchedulersAndManualPollProduceOneConfirmation() throws Exception {
        ExternalAccountReference doublePoll = newReference("double-poll");
        int pollSuccesses = runConcurrently(
                () -> externalAccountService.confirmRevokeFromSchedulerPoll(
                        doublePoll.getId(), "double-poll-a", "double-poll-a"),
                () -> externalAccountService.confirmRevokeFromSchedulerPoll(
                        doublePoll.getId(), "double-poll-b", "double-poll-b"));
        assertThat(pollSuccesses).isGreaterThanOrEqualTo(1);
        assertSingleConfirmation(doublePoll.getId());

        ExternalAccountReference manualAndPoll = newReference("manual-poll-race");
        int raceSuccesses = runConcurrently(
                () -> externalAccountService.confirmRevokeManually(
                        manualAndPoll.getId(), 1L, "manual-race", "manual-race"),
                () -> externalAccountService.confirmRevokeFromSchedulerPoll(
                        manualAndPoll.getId(), "poll-race", "poll-race"));
        assertThat(raceSuccesses).isGreaterThanOrEqualTo(1);
        assertSingleConfirmation(manualAndPoll.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("NF-09: 外部アカウント/イベント/監査ログのDB制約は矛盾した組合せを拒否する")
    void databaseConstraintsRejectContradictoryAttribution() {
        ExternalAccountReference ref = newReference("constraints");
        String externalInsert = "INSERT INTO t_external_account_reference "
                + "(system_id, account_identifier, assignee_type, assignee_id, status, "
                + "revoke_confirmed_at, revoke_confirmed_by, actor_type, confirmation_source, revoke_confirmed_source) "
                + "VALUES (?, ?, 'ENGINEER', 1, 'REVOKED', CURRENT_TIMESTAMP, ?, ?, ?, ?)";

        assertConstraintViolation(externalInsert, ref.getSystemId(), "human-without-id", null,
                "HUMAN", "MANUAL_API", "MANUAL_API");
        assertConstraintViolation(externalInsert, ref.getSystemId(), "system-with-human", 1L,
                "SYSTEM", "SCHEDULER_POLL", "SCHEDULER_POLL");
        assertConstraintViolation(externalInsert, ref.getSystemId(), "provider-manual", null,
                "PROVIDER", "MANUAL_API", "MANUAL_API");
        assertConstraintViolation(externalInsert, ref.getSystemId(), "partial-attribution", null,
                "SYSTEM", null, null);
        assertConstraintViolation(externalInsert, ref.getSystemId(), "arbitrary-actor", null,
                "OPERATOR", "MANUAL_API", "MANUAL_API");

        assertConstraintViolation("INSERT INTO t_asset_event "
                        + "(event_type, event_summary, actor_type, confirmation_source, human_user_id) "
                        + "VALUES ('TEST', 'test', 'SYSTEM', 'SCHEDULER_POLL', 1)");
        assertConstraintViolation("INSERT INTO t_asset_event "
                        + "(event_type, event_summary, actor_type) "
                        + "VALUES ('TEST', 'test', 'SYSTEM')");
        assertConstraintViolation("INSERT INTO t_audit_log "
                        + "(method, uri, status, actor_type, confirmation_source, human_user_id) "
                        + "VALUES ('POST', '/test', 200, 'PROVIDER', 'MANUAL_API', NULL)");
        assertConstraintViolation("INSERT INTO t_audit_log "
                        + "(method, uri, status, actor_type) "
                        + "VALUES ('POST', '/test', 200, 'SYSTEM')");
    }

    @Test
    @DisplayName("NF-09: 旧REVOKEDのsource空欄はconfirmed_by=1からSYSTEMへ推測しない")
    void legacyMigrationUsesExplicitUnresolvedFallback() throws Exception {
        String migration = new ClassPathResource(
                "db/migration/V136__external_account_revoke_system_actor_attribution.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration).contains("WHERE status = 'REVOKED'")
                .contains("ELSE 'LEGACY_UNRESOLVED'")
                .contains("SET revoke_confirmed_by = NULL")
                .contains("revoke_confirmed_source = confirmation_source");
        assertThat(migration).contains("COUNT(*) > 0 AND MAX(IS_NULLABLE = 'NO') = 1");
        assertThat(migration).doesNotContain("confirmed_by = 1 THEN 'SYSTEM'");
    }

    private ExternalAccountReference newReference(String suffix) {
        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("NF09_" + suffix.toUpperCase() + "_" + System.nanoTime())
                .systemName("NF-09 " + suffix)
                .systemType("SAAS_COLLAB")
                .isActive(1)
                .build();
        externalAccountSystemMapper.insert(system);
        return externalAccountService.registerAccountReference(
                system.getId(), suffix + "@ses-test.jp", "ENGINEER", 1L, "MEMBER", 1L);
    }

    private void assertAttribution(ExternalAccountReference reference,
                                   ActorType actorType,
                                   ConfirmationSource source) {
        assertThat(reference.getActorType()).isEqualTo(actorType.name());
        assertThat(reference.getConfirmationSource()).isEqualTo(source.name());
        assertThat(reference.getRevokeConfirmedSource()).isEqualTo(source.name());
        assertThat(reference.getRevokeConfirmedBy()).isNull();
    }

    private void assertConstraintViolation(String sql, Object... args) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql, args))
                .isInstanceOf(DataAccessException.class);
    }

    private int runConcurrently(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = List.of(
                executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return first.call();
                }),
                executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return second.call();
                }));
        try {
            start.countDown();
            int successes = 0;
            for (Future<?> future : futures) {
                try {
                    future.get(20, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException ignored) {
                    // CASの後着側は409になり得る。終端状態と証跡の一件性を検証する。
                }
            }
            return successes;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertSingleConfirmation(Long referenceId) {
        ExternalAccountReference confirmed = externalAccountReferenceMapper.selectById(referenceId);
        assertThat(confirmed.getStatus()).isEqualTo("REVOKED");
        assertThat(confirmed.getActorType()).isIn(ActorType.HUMAN.name(), ActorType.SYSTEM.name());
        assertThat(confirmed.getConfirmationSource()).isIn(
                ConfirmationSource.MANUAL_API.name(), ConfirmationSource.SCHEDULER_POLL.name());
        assertThat(assetEventMapper.selectList(null).stream()
                .filter(event -> referenceId.equals(event.getReferenceId()))).hasSize(1);
        assertThat(auditLogMapper.selectList(null).stream()
                .filter(log -> referenceId.equals(log.getReferenceId()))).hasSize(1);
    }
}
