package com.ses.privacy.production.retention.hold;

import com.ses.common.exception.BusinessException;
import com.ses.privacy.foundation.PrivacyJournalPort;
import com.ses.privacy.production.PrivacyAccessGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;

/**
 * NF07 hold 開始（A1H-1、production-bridge-phased-plan.md §3.4.4）。専用の H2 と test 用 Journal で、決定の欠落・Journal 不達・
 * ACK 前に projection を書かないこと・監査の失敗で操作が失敗すること・監査本文に subject 識別子が無いこと・帰属できない
 * element の選択（既定値なし）を確かめる。
 */
class PrivacyHoldServiceTest {

    private PrivacyHoldTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new PrivacyHoldTestFixture("nf07-hold-service-" + UUID.randomUUID());
        fixture.defaultCatalog();
    }

    @AfterEach
    void tearDown() {
        fixture.dropSchema();
    }

    @Test
    void D05の決定IDが無ければDECISION_UNKNOWNで拒否しJournalにも状態表にも触れない() {
        fixture.approval.getD05().setDecisionId(null);

        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 409, "error.privacy.decisionUnknown");
        assertThat(fixture.journal.appendCalls.get()).isZero();
        assertThat(fixture.count("SELECT COUNT(*) FROM t_privacy_projection_state")).isZero();
    }

    @Test
    void D05がplaceholderや失効済みならDECISION_UNKNOWNで拒否する() {
        fixture.approval.getD05().setDecisionId("D05-TBD-0001");
        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 409, "error.privacy.decisionUnknown");

        fixture.approval.getD05().setDecisionId("D05-NF07-TEST-001");
        fixture.approval.getD05().setExpiresOn("2026-11-16");
        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 409, "error.privacy.decisionUnknown");
        assertThat(fixture.journal.appendCalls.get()).isZero();
    }

    @Test
    void 許可する対象種別が未設定または解釈できなければDECISION_UNKNOWNで拒否する() {
        fixture.holdProperties.setAllowedTargetTypes(new java.util.ArrayList<>());
        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 409, "error.privacy.decisionUnknown");

        fixture.holdProperties.setAllowedTargetTypes(new java.util.ArrayList<>(List.of("SUBJECT", "subject")));
        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 409, "error.privacy.decisionUnknown");
        assertThat(fixture.journal.appendCalls.get()).isZero();
    }

    @Test
    void 時計の許容ずれが未設定ならDECISION_UNKNOWNで拒否する() {
        fixture.clockProperties.setMaxSkew(null);

        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 409, "error.privacy.decisionUnknown");
        assertThat(fixture.journal.appendCalls.get()).isZero();
    }

    @Test
    void D05で許可していない対象種別は拒否する() {
        fixture.holdProperties.setAllowedTargetTypes(new java.util.ArrayList<>(List.of("SUBJECT")));

        assertRejected(() -> fixture.service.start(fixture.elementRequest("DOCUMENT:IMPORT")), 409,
                "error.privacy.holdTargetTypeNotAllowed");
        assertThat(fixture.journal.appendCalls.get()).isZero();
    }

    @Test
    void 起動時検査がAVAILABLEでなければprofileに関係なく503で拒否する() {
        fixture.startupResult = new PrivacyJournalStartupVerifier.Result(
                PrivacyJournalStartupVerifier.Availability.UNAVAILABLE, List.of("durability.flushLogAtTrxCommit"));
        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 503, "error.privacy.journalUnavailable");

        fixture.startupResult = new PrivacyJournalStartupVerifier.Result(
                PrivacyJournalStartupVerifier.Availability.NOT_CONFIGURED, List.of());
        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 503, "error.privacy.journalUnavailable");
        assertThat(fixture.journal.appendCalls.get()).isZero();
        assertThat(fixture.count("SELECT COUNT(*) FROM t_privacy_projection_state")).isZero();
    }

    @Test
    void Journalに届かなければ503でpendingもprojectionも残さない() {
        fixture.journal.unavailable = true;

        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 503, "error.privacy.journalUnavailable");
        assertThat(fixture.count("SELECT COUNT(*) FROM t_legal_hold")).isZero();
        assertThat(fixture.state()).isNull();
    }

    @Test
    void SUBJECT_holdを開始するとACKの後にprojectionを書きpendingを消す() {
        AtomicInteger projectedAtAppend = new AtomicInteger(-1);
        AtomicInteger pendingAtAppend = new AtomicInteger(-1);
        fixture.journal.beforeAppend = () -> {
            projectedAtAppend.set(fixture.count("SELECT COUNT(*) FROM t_legal_hold"));
            pendingAtAppend.set(fixture.count(
                    "SELECT COUNT(*) FROM t_privacy_projection_state WHERE pending_command_id IS NOT NULL"));
        };

        PrivacyHoldStartResult result = fixture.service.start(subject("ENGINEER:10"));

        assertThat(projectedAtAppend.get()).as("ACK 前に projection を書かない").isZero();
        assertThat(pendingAtAppend.get()).as("append の前に pending を記録する").isEqualTo(1);
        assertThat(result.holdId()).matches("ph-[0-9a-f]{32}");
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.journalRevision()).isEqualTo(1);
        Map<String, Object> row = fixture.jdbc.queryForMap("SELECT * FROM t_legal_hold WHERE hold_id = ?", result.holdId());
        assertThat(row.get("subject_ref")).isEqualTo("ENGINEER:10");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("hold_kind")).isEqualTo("UNKNOWN");
        assertThat(row.get("reason_hash")).isEqualTo(PrivacyHoldService.sha256("訴訟対応のため保全する"));
        PrivacyHoldProjectionStore.StateRow state = fixture.state();
        assertThat(state.pendingCommandId()).isNull();
        assertThat(state.lastAppliedSeq()).isEqualTo(1);
        assertThat(state.lastAppliedHash()).matches("[0-9a-f]{64}");
        assertThat(fixture.journal.eventCount(PrivacyHoldTestFixture.TENANT, "ENGINEER:10")).isEqualTo(1);
    }

    @Test
    void 監査本文にsubject識別子と理由本文を入れずhold_IDと選択を残す() {
        PrivacyHoldStartResult result = fixture.service.start(subject("ENGINEER:4242"));

        assertThat(fixture.auditUris).isNotEmpty();
        assertThat(fixture.auditUris).allSatisfy(uri -> {
            assertThat(uri).doesNotContain("ENGINEER").doesNotContain("4242").doesNotContain("hs-")
                    .doesNotContain("訴訟");
            assertThat(uri).startsWith(PrivacyHoldAudit.URI_PREFIX);
        });
        // 開始要求の監査から hold ID で、D-05 の決定ID・理由コード・ticket を引ける（Review P3-1）。理由 hash は残さない
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("#" + PrivacyHoldAudit.START_REQUESTED + "?")
                && uri.contains("hold=" + result.holdId()) && uri.contains("d05=D05-NF07-TEST-001"));
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("#" + PrivacyHoldAudit.START_REASON + "?")
                && uri.contains("hold=" + result.holdId()) && uri.contains("reason=LITIGATION")
                && uri.contains("ticket=TICKET-1001"));
        String reasonHash = PrivacyHoldService.sha256("訴訟対応のため保全する");
        assertThat(fixture.auditUris).allSatisfy(uri -> assertThat(uri).doesNotContain(reasonHash.substring(0, 16)));
        assertThat(fixture.auditUris).allSatisfy(uri -> assertThat(uri.length()).isLessThanOrEqualTo(500));
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains(PrivacyHoldAudit.CHOICE)
                && uri.contains("element=DOCUMENT%3AIMPORT") && uri.contains("choice=ACCEPT_RISK"));
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains(PrivacyHoldAudit.CHOICE)
                && uri.contains("element=INTEGRATION_HUB_AUDIT") && uri.contains("choice=ACCEPT_RISK"));
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains(PrivacyHoldAudit.STARTED)
                && uri.contains("hold=" + result.holdId()));
    }

    @Test
    void 開始要求の監査に失敗すればpendingを残さずJournalにも書かない() {
        doThrow(new IllegalStateException("監査DB停止")).when(fixture.auditLog).recordRequired(any(), any(),
                startsWith(PrivacyHoldAudit.URI_PREFIX + PrivacyHoldAudit.START_REQUESTED), anyInt(), any(), anyBoolean());

        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 503, "error.privacy.sourceUnavailable");
        assertThat(fixture.journal.appendCalls.get()).isZero();
        PrivacyHoldProjectionStore.StateRow state = fixture.state();
        assertThat(state == null || state.pendingCommandId() == null).isTrue();
    }

    @Test
    void 開始完了の監査に失敗すれば成功応答せずpendingを残してreconcilerに任せる() {
        doThrow(new IllegalStateException("監査DB停止")).when(fixture.auditLog).recordRequired(any(), any(),
                startsWith(PrivacyHoldAudit.URI_PREFIX + PrivacyHoldAudit.STARTED), anyInt(), any(), anyBoolean());

        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 503, "error.privacy.holdAckUnknown");
        assertThat(fixture.count("SELECT COUNT(*) FROM t_legal_hold")).isZero();
        assertThat(fixture.state().pendingCommandId()).startsWith("hs-");
        assertThat(fixture.gate.evaluate(PrivacyHoldTestFixture.TENANT).reason()).isEqualTo(PrivacyHoldGate.REASON_PENDING);
    }

    @Test
    void 帰属できないelementの選択が欠ければ既定値へ倒さず400で拒否する() {
        PrivacyHoldStartRequest missingOne = new PrivacyHoldStartRequest("SUBJECT", "ENGINEER:10", null, "LITIGATION",
                "理由", "TICKET-1", null,
                List.of(new PrivacyHoldStartRequest.ElementChoice("DOCUMENT:IMPORT", "ACCEPT_RISK")));
        assertRejected(() -> fixture.service.start(missingOne), 400, "error.privacy.holdChoiceRequired");

        PrivacyHoldStartRequest noChoice = new PrivacyHoldStartRequest("SUBJECT", "ENGINEER:10", null, "LITIGATION",
                "理由", "TICKET-1", null, null);
        assertRejected(() -> fixture.service.start(noChoice), 400, "error.privacy.holdChoiceRequired");

        assertRejected(() -> fixture.service.start(fixture.subjectRequest("ENGINEER:10", "accept_risk", "ACCEPT_RISK")),
                400, "error.privacy.holdChoiceRequired");
        assertThat(fixture.journal.appendCalls.get()).isZero();
    }

    @Test
    void DATA_ELEMENT_HOLDを選んだelementにholdが無ければ409で拒否しpendingを残さない() {
        assertRejected(() -> fixture.service.start(fixture.subjectRequest("ENGINEER:10", "DATA_ELEMENT_HOLD", "ACCEPT_RISK")),
                409, "error.privacy.holdChoicesStale");
        assertThat(fixture.journal.appendCalls.get()).isZero();
        // 予約の transaction ごと戻るため、初回なら追随位置の行も残らない（hold 機構を使ったことにならない）
        PrivacyHoldProjectionStore.StateRow state = fixture.state();
        assertThat(state == null || state.pendingCommandId() == null).isTrue();
    }

    @Test
    void DATA_ELEMENT_holdを先に開始すればSUBJECT_holdでその選択を受け付ける() {
        fixture.service.start(fixture.elementRequest("DOCUMENT:IMPORT"));

        PrivacyHoldStartResult subjectHold = fixture.service.start(
                fixture.subjectRequest("ENGINEER:10", "DATA_ELEMENT_HOLD", "ACCEPT_RISK"));

        assertThat(subjectHold.status()).isEqualTo("ACTIVE");
        assertThat(fixture.count("SELECT COUNT(*) FROM t_legal_hold WHERE status = 'ACTIVE'")).isEqualTo(2);
        assertThat(fixture.state().lastAppliedSeq()).isEqualTo(2);
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains(PrivacyHoldAudit.DATA_ELEMENT_ACKNOWLEDGED)
                && uri.contains("element=DOCUMENT%3AIMPORT"));
    }

    @Test
    void DATA_ELEMENT_holdはpurge停止の確認と分類済みのelementを要する() {
        PrivacyHoldStartRequest withoutAck = new PrivacyHoldStartRequest("DATA_ELEMENT", null, "DOCUMENT:IMPORT",
                "LITIGATION", "理由", "TICKET-1", false, null);
        assertRejected(() -> fixture.service.start(withoutAck), 400, "error.privacy.holdAcknowledgeRequired");
        assertRejected(() -> fixture.service.start(fixture.elementRequest("API_NONCE")), 400,
                "error.privacy.holdInvalidTarget");
        assertRejected(() -> fixture.service.start(fixture.elementRequest("NOT_IN_CATALOG")), 400,
                "error.privacy.holdInvalidTarget");
        assertThat(fixture.journal.appendCalls.get()).isZero();
    }

    @Test
    void 同じtenantでpendingが残っていれば409で拒否する() {
        fixture.store.ensureStateLocked(PrivacyHoldTestFixture.TENANT, PrivacyHoldTestFixture.JOURNAL_ID,
                PrivacyHoldTestFixture.T0);
        fixture.store.recordPending(PrivacyHoldTestFixture.TENANT, PrivacyHoldTestFixture.JOURNAL_ID,
                "hs-" + "a".repeat(32), PrivacyHoldTestFixture.T0);

        assertRejected(() -> fixture.service.start(subject("ENGINEER:11")), 409, "error.privacy.holdStartInProgress");
        assertThat(fixture.journal.appendCalls.get()).isZero();
    }

    @Test
    void 同じ対象のholdが有効なら409で拒否する() {
        fixture.service.start(subject("ENGINEER:10"));

        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 409, "error.privacy.holdAlreadyActive");
        assertThat(fixture.journal.eventCount(PrivacyHoldTestFixture.TENANT, "ENGINEER:10")).isEqualTo(1);
    }

    @Test
    void ACK不明の後の再送は元のACKを返し二重にholdを作らない() {
        fixture.journal.commitThenFail.set(1);

        PrivacyHoldStartResult result = fixture.service.start(subject("ENGINEER:10"));

        assertThat(fixture.journal.appendCalls.get()).isEqualTo(2);
        assertThat(fixture.journal.eventCount(PrivacyHoldTestFixture.TENANT, "ENGINEER:10")).isEqualTo(1);
        assertThat(fixture.count("SELECT COUNT(*) FROM t_legal_hold WHERE hold_id = ?", result.holdId())).isEqualTo(1);
        assertThat(fixture.state().pendingCommandId()).isNull();
    }

    @Test
    void ACKが確認できなければ503でpendingを残しprojectionを書かない() {
        fixture.journal.failWithoutCommit.set(2);

        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 503, "error.privacy.holdAckUnknown");
        assertThat(fixture.count("SELECT COUNT(*) FROM t_legal_hold")).isZero();
        assertThat(fixture.state().pendingCommandId()).startsWith("hs-");
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains(PrivacyHoldAudit.ACK_UNKNOWN));
    }

    @Test
    void 公開スイッチOFFと権限なしは入口で止まりJournalに触れない() {
        doThrow(new PrivacyAccessGuard.PrivacyEntryNotFoundException()).when(fixture.guard)
                .requireAllowed(PrivacyAccessGuard.Capability.RETENTION, PrivacyHoldService.MANAGE_ACTION);
        assertThatThrownBy(() -> fixture.service.start(subject("ENGINEER:10")))
                .isInstanceOf(PrivacyAccessGuard.PrivacyEntryNotFoundException.class);

        doThrow(BusinessException.of(403, "error.accessDenied")).when(fixture.guard)
                .requireAllowed(eq(PrivacyAccessGuard.Capability.RETENTION), eq(PrivacyHoldService.VIEW_ACTION));
        assertRejected(() -> fixture.service.overview(), 403, "error.accessDenied");
        assertThat(fixture.journal.appendCalls.get()).isZero();
    }

    @Test
    void 別tenantの操作者は403で拒否する() {
        fixture.actor = new PrivacyHoldService.Actor("other-tenant", 1L, "privacy-admin-a");

        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 403, "error.accessDenied");
    }

    @Test
    void application_transactionの中からは開始できない() {
        TransactionTemplate outer = new TransactionTemplate(new DataSourceTransactionManager(fixture.dataSource));

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> fixture.service.start(subject("ENGINEER:10"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fixture.journal.appendCalls.get()).isZero();
    }

    @Test
    void request_hashは選択とD05の決定IDを束縛する() {
        PrivacyHoldService.Validated validated = new PrivacyHoldService.Validated("ENGINEER:10", "ENGINEER:10",
                "LITIGATION", PrivacyHoldService.sha256("理由"), "TICKET-1");
        Instant at = PrivacyHoldTestFixture.T0;
        String accept = PrivacyHoldService.requestHash("default", "ph-x", PrivacyHoldTargetType.SUBJECT, validated, 1L,
                at, "D05-A", Map.of("DOCUMENT:IMPORT", PrivacyHoldChoice.ACCEPT_RISK));
        String add = PrivacyHoldService.requestHash("default", "ph-x", PrivacyHoldTargetType.SUBJECT, validated, 1L,
                at, "D05-A", Map.of("DOCUMENT:IMPORT", PrivacyHoldChoice.DATA_ELEMENT_HOLD));
        String otherDecision = PrivacyHoldService.requestHash("default", "ph-x", PrivacyHoldTargetType.SUBJECT, validated,
                1L, at, "D05-B", Map.of("DOCUMENT:IMPORT", PrivacyHoldChoice.ACCEPT_RISK));

        assertThat(accept).isNotEqualTo(add).isNotEqualTo(otherDecision).matches("[0-9a-f]{64}");
    }

    @Test
    void 一覧は帰属できないelementと件数と前提の不足を返す() {
        fixture.jdbc.update("INSERT INTO t_document (tenant_id, document_type) VALUES ('default', 'IMPORT'), "
                + "('default', 'IMPORT'), ('other', 'IMPORT')");
        fixture.approval.getD05().setDecisionId(null);

        PrivacyHoldOverview overview = fixture.service.overview();

        assertThat(overview.gateState()).isEqualTo("NO_HOLDS");
        assertThat(overview.startReady()).isFalse();
        assertThat(overview.missingPrerequisites()).containsExactly(PrivacyHoldDecisions.REASON_D05);
        assertThat(overview.unattributableElements()).extracting(PrivacyHoldOverview.ElementEntry::dataElementId)
                .containsExactly("DOCUMENT:IMPORT", "INTEGRATION_HUB_AUDIT");
        assertThat(overview.unattributableElements().get(0).tenantRowCount()).isEqualTo(2L);
        assertThat(overview.unattributableElements().get(1).tenantRowCount()).isNull();
        assertThat(overview.unclassifiedElements()).containsExactly("AUDIT_LOG");
        assertThat(overview.holdableElements()).doesNotContain("API_NONCE").contains("DOCUMENT:IMPORT", "AUDIT_LOG");
    }

    // ---- Review P2-1: pending_since と再送の在途時間 ----

    @Test
    void pending_sinceは予約transactionの中で取りJournalのhead照会の時間を含めない() {
        java.util.concurrent.atomic.AtomicBoolean slowHead = new java.util.concurrent.atomic.AtomicBoolean(true);
        fixture.journal.beforeHead = () -> {
            if (slowHead.getAndSet(false)) {
                fixture.clock.advance(java.time.Duration.ofMinutes(1));
            }
        };
        java.util.concurrent.atomic.AtomicReference<Instant> pendingAtAppend = new java.util.concurrent.atomic.AtomicReference<>();
        fixture.journal.beforeAppend = () -> pendingAtAppend.set(fixture.state().pendingSince());

        PrivacyHoldStartResult result = fixture.service.start(subject("ENGINEER:20"));

        assertThat(pendingAtAppend.get()).isEqualTo(PrivacyHoldTestFixture.T0.plus(java.time.Duration.ofMinutes(1)));
        // requestedAt（request hash と projection の値）は head 照会の前に取った開始の時刻のまま
        assertThat(fixture.jdbc.queryForObject("SELECT requested_at FROM t_legal_hold WHERE hold_id = ?",
                java.time.LocalDateTime.class, result.holdId()))
                .isEqualTo(java.time.LocalDateTime.ofInstant(PrivacyHoldTestFixture.T0, PrivacyHoldProjectionStore.ZONE));
    }

    @Test
    void 再送の直前にpending_sinceを進めるので再送の在途中にreconcilerが動いてもpendingを消さない() {
        fixture.journal.failWithoutCommit.set(1);
        AtomicInteger calls = new AtomicInteger();
        List<PrivacyHoldProjectionReconciler.Outcome> duringResend = new java.util.ArrayList<>();
        fixture.journal.beforeAppend = () -> {
            if (calls.incrementAndGet() == 1) {
                // 1回目の append が pending-timeout（2分）+ max-skew を超えて在途した後に ACK 不明になる
                fixture.clock.advance(java.time.Duration.ofMinutes(2).plusSeconds(30));
            } else {
                // 再送が在途の間に reconciler が動く（Journal にはまだ command が無い）
                duringResend.addAll(fixture.reconciler.reconcileTenant(PrivacyHoldTestFixture.TENANT));
            }
        };

        PrivacyHoldStartResult result = fixture.service.start(subject("ENGINEER:21"));

        assertThat(duringResend).containsExactly(PrivacyHoldProjectionReconciler.Outcome.PENDING_KEPT)
                .doesNotContain(PrivacyHoldProjectionReconciler.Outcome.PENDING_ABANDONED);
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(fixture.journal.appendCalls.get()).isEqualTo(2);
        assertThat(fixture.state().pendingCommandId()).isNull();
        assertThat(fixture.gate.evaluate(PrivacyHoldTestFixture.TENANT).heldSubjects()).containsExactly("ENGINEER:21");
    }

    @Test
    void 再送の直前のCASが0件ならreconcilerが消したpendingを信じて再送しない() {
        fixture.journal.failWithoutCommit.set(1);
        fixture.journal.beforeAppend = () -> {
            // 1回目の append の在途中に reconciler が pending を消した状態
            PrivacyHoldProjectionStore.StateRow state = fixture.state();
            fixture.store.clearPending(PrivacyHoldTestFixture.TENANT, PrivacyHoldTestFixture.JOURNAL_ID,
                    state.pendingCommandId());
        };

        assertRejected(() -> fixture.service.start(subject("ENGINEER:22")), 503, "error.privacy.holdAckUnknown");
        assertThat(fixture.journal.appendCalls.get()).as("再送しない").isEqualTo(1);
        assertThat(fixture.count("SELECT COUNT(*) FROM t_legal_hold")).isZero();
    }

    // ---- Review P3-7: revision の競合と開始済みの区別 ----

    @Test
    void revisionの競合はheldでなければ再試行の409でpendingを戻す() {
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean(true);
        fixture.journal.beforeAppend = () -> {
            if (once.getAndSet(false)) {
                // 同じ partition に hold 以外の event（将来の dispatch・lease 等）が先に入る
                PrivacyJournalPort.JournalPartition partition =
                        new PrivacyJournalPort.JournalPartition(PrivacyHoldTestFixture.TENANT, "ENGINEER:23");
                fixture.journal.append(PrivacyJournalPort.JournalCommand.create("lease-1", partition, 0,
                        PrivacyJournalPort.CommandType.LEASE_CLAIMED, null, null, null, "worker-1", 0,
                        PrivacyHoldTestFixture.T0.plus(java.time.Duration.ofMinutes(10))));
            }
        };

        assertRejected(() -> fixture.service.start(subject("ENGINEER:23")), 409, "error.privacy.holdStartInProgress");
        assertThat(fixture.state().pendingCommandId()).isNull();
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains(PrivacyHoldAudit.START_REJECTED));
    }

    @Test
    void revisionの競合で先にholdが開始されていれば開始済みの409() {
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean(true);
        fixture.journal.beforeAppend = () -> {
            if (once.getAndSet(false)) {
                PrivacyJournalPort.JournalPartition partition =
                        new PrivacyJournalPort.JournalPartition(PrivacyHoldTestFixture.TENANT, "ENGINEER:24");
                PrivacyHoldPartitions.Ids other = PrivacyHoldPartitions.newIds();
                fixture.journal.append(PrivacyJournalPort.JournalCommand.create(other.commandId(), partition, 0,
                        PrivacyJournalPort.CommandType.HOLD_STARTED, other.holdId(), null, "f".repeat(64), null, 0, null));
            }
        };

        assertRejected(() -> fixture.service.start(subject("ENGINEER:24")), 409, "error.privacy.holdAlreadyActive");
        assertThat(fixture.state().pendingCommandId()).isNull();
    }

    // ---- Review P3-2: reconciler が先に復元した行を開始者の値に置き換える ----

    @Test
    void ACKとprojectionの間にreconcilerが復元しても開始者の値で置き換える() {
        AtomicInteger heads = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
        fixture.journal.beforeHead = () -> {
            // 1回目は開始前の head、2回目は ACK 後の chain 検証。その間に reconciler が割り込む
            if (heads.incrementAndGet() == 2 && !ran.getAndSet(true)) {
                assertThat(fixture.reconciler.reconcileTenant(PrivacyHoldTestFixture.TENANT))
                        .contains(PrivacyHoldProjectionReconciler.Outcome.PENDING_PROJECTED);
            }
        };

        PrivacyHoldStartResult result = fixture.service.start(subject("ENGINEER:25"));

        Map<String, Object> row = fixture.jdbc.queryForMap("SELECT * FROM t_legal_hold WHERE hold_id = ?", result.holdId());
        assertThat(row.get("reason_code")).isEqualTo("LITIGATION");
        assertThat(row.get("ticket_ref")).isEqualTo("TICKET-1001");
        assertThat(((Number) row.get("requested_by")).longValue()).isEqualTo(1L);
        assertThat(row.get("reason_hash")).isEqualTo(PrivacyHoldService.sha256("訴訟対応のため保全する"));
        assertThat(fixture.count("SELECT COUNT(*) FROM t_legal_hold")).isEqualTo(1);
        assertThat(fixture.gate.evaluate(PrivacyHoldTestFixture.TENANT).state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
    }

    // ---- Review P3-1・P3-11 ----

    @Test
    void 最長の値でも監査の各行は500文字以内に収まる() {
        String element = "A".repeat(50) + ":" + "B".repeat(64);
        fixture.catalog(element, "UNKNOWN");
        fixture.approval.getD05().setDecisionId("D05:" + ":".repeat(59) + "X");
        String ticket = "T" + "#".repeat(99);
        PrivacyHoldStartRequest request = new PrivacyHoldStartRequest("DATA_ELEMENT", null, element,
                "R" + "_".repeat(49), "理由", ticket, true, null);

        fixture.service.start(request);

        assertThat(fixture.auditUris).isNotEmpty()
                .allSatisfy(uri -> assertThat(uri.length()).isLessThanOrEqualTo(500));
    }

    @Test
    void 一覧の開始日時はJSTで表示する() {
        fixture.service.start(fixture.elementRequest("DOCUMENT:IMPORT"));

        assertThat(fixture.service.overview().holds().get(0).requestedAt()).isEqualTo("2026-11-16 12:00:00");
    }

    private PrivacyHoldStartRequest subject(String subjectRef) {
        return fixture.subjectRequest(subjectRef, "ACCEPT_RISK", "ACCEPT_RISK");
    }

    private static void assertRejected(Runnable action, int code, String messageKey) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(code);
                    assertThat(e.getMessage()).isEqualTo(messageKey);
                });
    }
}
