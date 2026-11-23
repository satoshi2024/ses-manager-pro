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

    /** review rb-fe-c P2-1: 副入口「hold 開始」の署名（D-05 の受理 {@code NF07_D05_OWNER_ACCEPTANCE} 等）が取消し・失効なら開始しない。 */
    @Test
    void hold開始の署名が有効でなければDECISION_UNKNOWNで拒否しJournalにも状態表にも触れない() {
        fixture.startSignOffsValid = false;

        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 409, "error.privacy.decisionUnknown");
        assertThat(fixture.journal.appendCalls.get()).isZero();
        assertThat(fixture.state()).isNull();
        assertThat(fixture.service.overview().missingPrerequisites()).contains("subgateSignOffs");

        fixture.startSignOffsValid = true;
        assertThat(fixture.service.start(subject("ENGINEER:10"))).as("新しい署名の確認の後は自動で再開する").isNotNull();
    }

    /** review rb-fe-c P3-10: reader の principal（開放の条件の結果が権限不足）では、hold の効力が読めても開始は 503 で拒否する。 */
    @Test
    void readerのprincipalでは開始を503で拒否しJournalに触れない() {
        fixture.enablementResult = new PrivacyJournalStartupVerifier.Result(
                PrivacyJournalStartupVerifier.Availability.UNAVAILABLE, List.of("grants.appendMissing"));

        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 503, "error.privacy.journalUnavailable");
        assertThat(fixture.journal.appendCalls.get()).isZero();
        assertThat(fixture.state()).isNull();
    }

    /**
     * review rb-fe-c P3-1: Journal が append の権限の拒否を返せば（確定の拒否。append は起きていない）、ACK 不明にせず 503 で返し、
     * pending を戻して gate を UNAVAILABLE にしない。
     */
    @Test
    void appendが権限の拒否なら503でpendingを戻しholdを作らない() {
        fixture.journal.beforeAppend = () -> {
            throw new com.ses.privacy.foundation.JournalAccessDeniedException("test: append denied", null);
        };

        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 503, "error.privacy.journalUnavailable");
        assertThat(fixture.journal.appendCalls.get()).as("再送しない").isEqualTo(1);
        assertThat(fixture.count("SELECT COUNT(*) FROM t_legal_hold")).isZero();
        assertThat(fixture.state() == null ? null : fixture.state().pendingCommandId()).as("pending を残さない").isNull();
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains(PrivacyHoldAudit.START_REJECTED)
                && uri.contains("JOURNAL_ACCESS_DENIED"));
    }

    /**
     * review rb-fe-c P3-N1: 先の append が ACK 不明（commit 済み・ACK 喪失）の後、再送が権限で拒否されても確定の拒否にしない。ACK 不明と
     * して pending を残し（gate は UNAVAILABLE のまま）、監査は拒否ではなく ACK 不明に理由を添えて区別する。
     */
    @Test
    void ACK不明の後の再送が権限で拒否されても拒否とせずACK不明としてpendingを残す() {
        AtomicInteger calls = new AtomicInteger();
        fixture.journal.commitThenFail.set(1);
        fixture.journal.beforeAppend = () -> {
            if (calls.incrementAndGet() == 2) {
                throw new com.ses.privacy.foundation.JournalAccessDeniedException("test: denied on retry", null);
            }
        };

        assertRejected(() -> fixture.service.start(subject("ENGINEER:10")), 503, "error.privacy.holdAckUnknown");
        assertThat(calls).hasValue(2);
        assertThat(fixture.state().pendingCommandId()).as("pending を残す").isNotNull();
        assertThat(fixture.gate.evaluate(PrivacyHoldTestFixture.TENANT).state())
                .isEqualTo(PrivacyHoldGate.State.UNAVAILABLE);
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains(PrivacyHoldAudit.ACK_UNKNOWN)
                && uri.contains("reason=" + JournalRetryDeniedAfterUnknown.AUDIT_REASON));
        assertThat(fixture.auditUris).noneMatch(uri -> uri.contains(PrivacyHoldAudit.START_REJECTED));
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
            // subject の ID は値の境界で判定する（乱数の hash の一部に同じ数字列が現れても誤検出しない）
            assertThat(java.net.URLDecoder.decode(uri, java.nio.charset.StandardCharsets.UTF_8))
                    .doesNotContain("ENGINEER").doesNotContainPattern("(^|[^0-9A-Za-z])4242([^0-9A-Za-z]|$)")
                    .doesNotContain("hs-").doesNotContain("訴訟");
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

    // ---- A1H-1 delta review P2-N1: pending_since から append の開始までに在途時間の外の時間を入れない ----

    @Test
    void appendの経路ではinstanceの比較をやり直さず比較の時間がpending_sinceとappendの開始の間に入らない() {
        java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger slowChecks = new AtomicInteger();
        fixture.applicationServerUuid = () -> {
            if (armed.get()) {
                // 間隔の切れた後の比較（Journal pool の接続取得と2つの照会）に時間が掛かる
                slowChecks.incrementAndGet();
                fixture.clock.advance(java.time.Duration.ofMinutes(3));
            }
            return "application-server-uuid";
        };
        java.util.concurrent.atomic.AtomicBoolean firstHead = new java.util.concurrent.atomic.AtomicBoolean(true);
        fixture.journal.beforeHead = () -> {
            if (firstHead.getAndSet(false)) {
                // head の照会の後に instance の比較の間隔（1分）が切れる。修正前は append の直前の port() で比較が走った
                fixture.clock.advance(java.time.Duration.ofSeconds(61));
                armed.set(true);
            }
        };
        java.util.concurrent.atomic.AtomicReference<Instant> pendingAtAppend = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Instant> appendStartedAt = new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger checksBeforeAppend = new AtomicInteger(-1);
        fixture.journal.beforeAppend = () -> {
            if (appendStartedAt.get() == null) {
                pendingAtAppend.set(fixture.state().pendingSince());
                appendStartedAt.set(fixture.clock.instant());
                checksBeforeAppend.set(slowChecks.get());
            }
        };

        PrivacyHoldStartResult result = fixture.service.start(subject("ENGINEER:23"));

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(checksBeforeAppend.get()).as("予約から append までに instance の比較をしない").isZero();
        assertThat(pendingAtAppend.get()).as("pending_since は append の開始時刻と一致する").isEqualTo(appendStartedAt.get());
    }

    @Test
    void 再送の直前は追随位置の行のlockを取った後に時計を読むのでlock待ちの時間がpending_sinceに含まれない() {
        java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean();
        fixture.dataSource.beforePrepare = sql -> {
            String normalized = sql.toLowerCase(java.util.Locale.ROOT);
            boolean locksStateRow = normalized.contains("t_privacy_projection_state")
                    && (normalized.contains("for update") || normalized.startsWith("update"));
            if (locksStateRow && refreshing.getAndSet(false)) {
                // 状態行の lock を最初に取る statement で、破壊的経路の evaluateLocked（tx の終了まで行 lock を持つ）を待つ。
                // 修正前は時計を読んだ後の UPDATE で待ち、修正後は FOR UPDATE で待ってから時計を読む（実 lock は MySQL test）
                fixture.clock.advance(java.time.Duration.ofMinutes(3));
            }
        };
        fixture.journal.failWithoutCommit.set(1);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Instant> pendingAtResend = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Instant> resendStartedAt = new java.util.concurrent.atomic.AtomicReference<>();
        fixture.journal.beforeAppend = () -> {
            if (calls.incrementAndGet() == 1) {
                refreshing.set(true);
            } else {
                pendingAtResend.set(fixture.state().pendingSince());
                resendStartedAt.set(fixture.clock.instant());
            }
        };

        PrivacyHoldStartResult result = fixture.service.start(subject("ENGINEER:24"));

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(fixture.journal.appendCalls.get()).isEqualTo(2);
        assertThat(pendingAtResend.get()).as("pending_since は再送の開始時刻（lock 待ちの後）と一致する")
                .isEqualTo(resendStartedAt.get());
    }

    @Test
    void 再送の直前にlockした行のpendingが自分のcommandでなければ時刻を書かず再送しない() {
        fixture.journal.failWithoutCommit.set(1);
        fixture.journal.beforeAppend = () -> {
            // 1回目の append の在途中に reconciler が pending を消し、別の開始が pending を取った状態
            PrivacyHoldProjectionStore.StateRow state = fixture.state();
            fixture.store.clearPending(PrivacyHoldTestFixture.TENANT, PrivacyHoldTestFixture.JOURNAL_ID,
                    state.pendingCommandId());
            fixture.store.recordPending(PrivacyHoldTestFixture.TENANT, PrivacyHoldTestFixture.JOURNAL_ID,
                    "hs-" + "9".repeat(32), PrivacyHoldTestFixture.T0);
        };

        assertRejected(() -> fixture.service.start(subject("ENGINEER:25")), 503, "error.privacy.holdAckUnknown");
        assertThat(fixture.journal.appendCalls.get()).as("再送しない").isEqualTo(1);
        assertThat(fixture.state().pendingCommandId()).isEqualTo("hs-" + "9".repeat(32));
        assertThat(fixture.state().pendingSince()).as("他の command の pending_since を進めない")
                .isEqualTo(PrivacyHoldTestFixture.T0);
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
