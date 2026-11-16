package com.ses.privacy.production.retention.hold;

import com.ses.common.exception.BusinessException;
import com.ses.privacy.foundation.HoldReleaseAuthorityClaim;
import com.ses.privacy.foundation.HoldReleaseBinding;
import com.ses.privacy.foundation.HoldReleaseTestAuthorities;
import com.ses.privacy.foundation.HoldReleaseVerifier;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalHead;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalPartition;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.ses.privacy.foundation.RevocationStatus;
import com.ses.privacy.production.PrivacyAccessGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * NF07 hold の解除（NF07-REL）。専用の H2 と test 用 Journal、test-only 鍵（実行のたびに memory 上で生成）で、二者の署名付き
 * proof による解除、同じ command の再送が元の ACK を返すこと、head・hold revision の CAS、開始と同じ pending の直列化、
 * 開放の gate（D-03・D-05・trust anchor・verifier）、解除後に D1〜D10 の経路が従来どおり動くことを確かめる。
 */
class PrivacyHoldReleaseServiceTest {

    private static final String TENANT = PrivacyHoldTestFixture.TENANT;
    private static final String ISSUER = "issuer-a";
    private static final String APPROVER = "approver-b";
    private static final String REASON = "訴訟が終結したため保全を解除する";
    private static final String REASON_CODE = "LITIGATION_CLOSED";
    private static final String TICKET = "TICKET-2001";

    private PrivacyHoldTestFixture fixture;
    private HoldReleaseTestAuthorities authorities;
    private HoldReleaseVerifier verifier;
    private PrivacyHoldReleaseService release;

    @BeforeEach
    void setUp() {
        fixture = new PrivacyHoldTestFixture("nf07-hold-release-" + UUID.randomUUID());
        fixture.defaultCatalog();
        fixture.approval.getD03().setDecisionId("D03-NF07-TEST-001");
        fixture.approval.getD03().setSignedOn("2026-11-01");
        fixture.approval.getD03().setExpiresOn("2027-06-01");
        authorities = new HoldReleaseTestAuthorities();
        authorities.register(ISSUER, "sys_user:20", TENANT);
        authorities.register(APPROVER, "sys_user:30", TENANT);
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        verifier = ProductionPrivacyComponents.holdReleaseVerifier(prod, authorities.registry(),
                ref -> RevocationStatus.ACTIVE, fixture.clock);
        release = fixture.releaseService(verifier, authorities.registry());
    }

    @AfterEach
    void tearDown() {
        fixture.dropSchema();
    }

    // --- 解除と D1〜D10 の再開 ---

    @Test
    void 二者の署名付きproofで解除するとJournalのACKの後にprojectionがRELEASEDになる() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7041");
        String command = newCommandId();

        PrivacyHoldReleaseResult result = release.release(started.holdId(), request(started.holdId(), command, REASON));

        assertThat(result.status()).isEqualTo("RELEASED");
        assertThat(result.releaseRevision()).isEqualTo(2);
        assertThat(result.replayed()).isFalse();
        assertThat(fixture.journal.eventCount(TENANT, "ENGINEER:7041")).isEqualTo(2);
        assertThat(fixture.journal.head(new JournalPartition(TENANT, "ENGINEER:7041")).held()).isFalse();
        PrivacyHoldProjectionStore.HoldRow row = fixture.store.hold(TENANT, started.holdId()).orElseThrow();
        assertThat(row.status()).isEqualTo("RELEASED");
        assertThat(row.releasedAt()).isEqualTo(fixture.journal.events(new JournalPartition(TENANT, "ENGINEER:7041"))
                .get(1).createdAt());
        assertThat(fixture.state().pendingCommandId()).isNull();
        assertThat(fixture.state().lastAppliedSeq()).isEqualTo(2);
        PrivacyHoldGate.Decision decision = fixture.gate.evaluate(TENANT);
        assertThat(decision.state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
        assertThat(decision.heldSubjects()).isEmpty();
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("RELEASE_REQUESTED")
                && uri.contains("issuer=" + ISSUER) && uri.contains("approver=" + APPROVER));
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("RELEASE_DECISIONS") && uri.contains("d03=D03-NF07-TEST-001"));
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("#RELEASED?"));
        // 判定に使った D-05 の規則と許容ずれを残す（delta review P3-5）
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("#RELEASE_RULES?") && uri.contains("separation=REQUIRED")
                && uri.contains("unknownStarter=REJECT") && uri.contains("skewSeconds=1"));
        // subject の ID は値の境界で判定する（乱数の hash の一部に同じ数字列が現れても誤検出しない）
        assertThat(fixture.auditUris).allSatisfy(uri -> assertThat(java.net.URLDecoder.decode(uri,
                java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("ENGINEER")
                .doesNotContainPattern("(^|[^0-9A-Za-z])7041([^0-9A-Za-z]|$)").doesNotContain(command));
    }

    @Test
    void 解除の後はD1からD10の経路がholdに止められず従来どおり動く() {
        PrivacyHoldStartResult element = fixture.service.start(fixture.elementRequest("INTEGRATION_HUB_AUDIT"));
        PrivacyHoldStartResult subject = startSubject("ENGINEER:7042");
        @SuppressWarnings("unchecked")
        PrivacyHoldPathGuard paths = new PrivacyHoldPathGuard(fixture.gate, fixture.resolver, mock(ObjectProvider.class));
        assertThat(paths.openGlobalBatch(PrivacyHoldPath.D7).elementVerdict("INTEGRATION_HUB_AUDIT"))
                .isEqualTo(PrivacyHoldGate.Verdict.HELD);
        assertThat(fixture.gate.evaluate(TENANT).heldSubjects()).containsExactly("ENGINEER:7042");

        release.release(subject.holdId(), request(subject.holdId(), newCommandId(), REASON));
        release.release(element.holdId(), request(element.holdId(), newCommandId(), REASON));

        PrivacyHoldGate.Decision decision = fixture.gate.evaluate(TENANT);
        assertThat(decision.state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
        assertThat(decision.heldSubjects()).isEmpty();
        assertThat(decision.heldDataElements()).isEmpty();
        assertThat(paths.openGlobalBatch(PrivacyHoldPath.D7).elementVerdict("INTEGRATION_HUB_AUDIT"))
                .isEqualTo(PrivacyHoldGate.Verdict.ALLOW);
        assertThat(fixture.state().lastAppliedSeq()).isEqualTo(4);
    }

    @Test
    void 解除の後に同じ対象のholdを開始し直し再び解除できる() {
        PrivacyHoldStartResult first = startSubject("ENGINEER:7043");
        release.release(first.holdId(), request(first.holdId(), newCommandId(), REASON));

        PrivacyHoldStartResult second = startSubject("ENGINEER:7043");

        assertThat(second.journalRevision()).isEqualTo(3);
        assertThat(fixture.gate.evaluate(TENANT).heldSubjects()).containsExactly("ENGINEER:7043");
        PrivacyHoldReleaseResult again = release.release(second.holdId(), request(second.holdId(), newCommandId(), REASON));
        assertThat(again.releaseRevision()).isEqualTo(4);
        assertThat(fixture.gate.evaluate(TENANT).state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
        assertThat(fixture.count("SELECT COUNT(*) FROM t_legal_hold WHERE tenant_id = ? AND status = 'RELEASED'", TENANT))
                .isEqualTo(2);
    }

    // --- 同じ command の再送 ---

    @Test
    void 同じcommandの再送は元のACKを返しJournalにもprojectionにも重ねない() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7044");
        PrivacyHoldReleaseRequest request = request(started.holdId(), newCommandId(), REASON);
        PrivacyHoldReleaseResult first = release.release(started.holdId(), request);
        int appends = fixture.journal.appendCalls.get();

        PrivacyHoldReleaseResult resent = release.release(started.holdId(), request);

        assertThat(resent.replayed()).isTrue();
        assertThat(resent.releaseRevision()).isEqualTo(first.releaseRevision());
        assertThat(fixture.journal.appendCalls.get()).as("再送は append しない").isEqualTo(appends);
        assertThat(fixture.journal.eventCount(TENANT, "ENGINEER:7044")).isEqualTo(2);
        assertThat(fixture.state().lastAppliedSeq()).isEqualTo(2);

        // proof の期限を過ぎた後の再送も、新しい状態変更を起こさず元の結果を返す
        fixture.clock.advance(Duration.ofHours(3));
        assertThat(release.release(started.holdId(), request).releaseRevision()).isEqualTo(first.releaseRevision());
    }

    @Test
    void 同じcommand_IDで内容の違う要求は409で拒否する() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7045");
        String command = newCommandId();
        release.release(started.holdId(), request(started.holdId(), command, REASON));

        PrivacyHoldReleaseRequest different = request(started.holdId(), command, "別の理由で解除する");

        assertBusiness(() -> release.release(started.holdId(), different), 409, "error.privacy.holdReleaseConflict");
        assertThat(fixture.journal.eventCount(TENANT, "ENGINEER:7045")).isEqualTo(2);
    }

    @Test
    void ACK不明の後の再送は元のACKを受け取り二重に解除しない() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7046");
        int before = fixture.journal.appendCalls.get();
        fixture.journal.commitThenFail.set(1);

        PrivacyHoldReleaseResult result = release.release(started.holdId(), request(started.holdId(), newCommandId(),
                REASON));

        assertThat(fixture.journal.appendCalls.get() - before).isEqualTo(2);
        assertThat(result.replayed()).isTrue();
        assertThat(fixture.journal.eventCount(TENANT, "ENGINEER:7046")).isEqualTo(2);
        assertThat(fixture.store.hold(TENANT, started.holdId()).orElseThrow().status()).isEqualTo("RELEASED");
        assertThat(fixture.state().pendingCommandId()).isNull();
    }

    @Test
    void ACK不明が続けばpendingを残して503とし_reconcilerが解除をprojectionへ補う() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7047");
        String command = newCommandId();
        fixture.journal.commitThenFail.set(2);

        assertBusiness(() -> release.release(started.holdId(), request(started.holdId(), command, REASON)), 503,
                "error.privacy.holdReleaseAckUnknown");
        assertThat(fixture.state().pendingCommandId()).isEqualTo(command);
        assertThat(fixture.store.hold(TENANT, started.holdId()).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(fixture.gate.evaluate(TENANT).state()).isEqualTo(PrivacyHoldGate.State.UNAVAILABLE);

        Map<PrivacyHoldProjectionReconciler.Outcome, Integer> outcomes = fixture.reconciler.reconcileAll();

        assertThat(outcomes).containsKey(PrivacyHoldProjectionReconciler.Outcome.PENDING_PROJECTED);
        assertThat(fixture.store.hold(TENANT, started.holdId()).orElseThrow().status()).isEqualTo("RELEASED");
        assertThat(fixture.state().pendingCommandId()).isNull();
        assertThat(fixture.gate.evaluate(TENANT).state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
    }

    // --- gate と reconciler が解除後の状態を扱う ---

    @Test
    void Journalにだけ解除があればgateは追随遅れとし_reconcilerが追い付かせる() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7048");
        PrivacyHoldReleaseRequest request = request(started.holdId(), newCommandId(), REASON);
        HoldReleaseBinding binding = bindingOf(request);
        // service を通さず Journal にだけ解除を入れる（ACK 後に projection を書けなかった状態）
        fixture.journal.appendHoldRelease(verifier.verify(binding, binding, "SUBJECT", fixture.decisions.releaseRules(), claimOf(request.issuer(),
                HoldReleaseAuthorityClaim.Role.ISSUER), claimOf(request.approver(), HoldReleaseAuthorityClaim.Role.APPROVER)));

        PrivacyHoldGate.Decision lagging = fixture.gate.evaluate(TENANT);
        assertThat(lagging.state()).isEqualTo(PrivacyHoldGate.State.UNAVAILABLE);
        assertThat(lagging.reason()).isEqualTo(PrivacyHoldGate.REASON_PROJECTION_LAG);

        Map<PrivacyHoldProjectionReconciler.Outcome, Integer> outcomes = fixture.reconciler.reconcileAll();

        assertThat(outcomes).containsKey(PrivacyHoldProjectionReconciler.Outcome.CAUGHT_UP);
        assertThat(fixture.store.hold(TENANT, started.holdId()).orElseThrow().status()).isEqualTo("RELEASED");
        assertThat(fixture.gate.evaluate(TENANT).state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
        assertThat(fixture.reconciler.reconcileAll()).containsKey(PrivacyHoldProjectionReconciler.Outcome.CONSISTENT);
    }

    @Test
    void projectionだけをRELEASEDに書き換えてもJournalに解除が無ければgateはUNAVAILABLE() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7049");
        fixture.jdbc.update("UPDATE t_legal_hold SET status = 'RELEASED', released_at = ? WHERE hold_id = ?",
                LocalDateTime.of(2026, 11, 16, 12, 0), started.holdId());
        List<PrivacyHoldProjectionStore.HoldRow> holds = fixture.store.holds(TENANT, false);
        // projection の内部の要約も書き換え、projection 単体では整合して見える状態にする
        fixture.jdbc.update("UPDATE t_privacy_projection_state SET last_applied_seq = ?, last_applied_hash = ?",
                PrivacyHoldProjectionStore.appliedEventCount(holds), PrivacyHoldProjectionStore.projectionDigest(holds));

        PrivacyHoldGate.Decision decision = fixture.gate.evaluate(TENANT);

        assertThat(decision.state()).isEqualTo(PrivacyHoldGate.State.UNAVAILABLE);
        assertThat(decision.reason()).isEqualTo(PrivacyHoldGate.REASON_PROJECTION_LAG);
        assertThat(PrivacyHoldGate.projectionMismatch(fixture.state(), holds)).isNull();
    }

    @Test
    void ACTIVEのholdだけのtenantの要約はNF07_A1Hのときと同じ値のまま() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7050");
        PrivacyHoldProjectionStore.HoldRow row = fixture.store.hold(TENANT, started.holdId()).orElseThrow();

        String legacy = PrivacyHoldProjectionStore.digest(List.of(new PrivacyHoldProjectionStore.DigestEntry(
                row.partitionRef(), row.journalRevision(), row.journalEventHash(), row.holdId())));

        assertThat(fixture.state().lastAppliedHash()).isEqualTo(legacy);
        assertThat(fixture.state().lastAppliedSeq()).isEqualTo(1);
    }

    // --- head・hold revision の CAS と §11.1 の拒否 ---

    @Test
    void 署名した時点のjournal_headやhold_revisionが現在と違えば409で拒否しJournalに触れない() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7051");
        PrivacyHoldReleaseRequest current = request(started.holdId(), newCommandId(), REASON);
        HoldReleaseBinding signed = bindingOf(current);
        int appends = fixture.journal.appendCalls.get();

        HoldReleaseBinding staleHead = new HoldReleaseBinding(signed.tenantId(), signed.subjectRef(), signed.holdId(),
                signed.expectedHoldRevision(), signed.reasonHash(), signed.commandId(), signed.expectedJournalRevision(),
                "e".repeat(64), signed.requesterRef(), signed.holdStarterRef());
        assertBusiness(() -> release.release(started.holdId(), signedRequest(staleHead, REASON)), 409,
                "error.privacy.holdReleaseStale");

        HoldReleaseBinding staleHold = new HoldReleaseBinding(signed.tenantId(), signed.subjectRef(), signed.holdId(),
                2, signed.reasonHash(), signed.commandId(), 2, signed.expectedJournalHash(), signed.requesterRef(), signed.holdStarterRef());
        assertBusiness(() -> release.release(started.holdId(), signedRequest(staleHold, REASON)), 409,
                "error.privacy.holdReleaseStale");

        assertThat(fixture.journal.appendCalls.get()).isEqualTo(appends);
        assertThat(fixture.state().pendingCommandId()).isNull();
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("RELEASE_REJECTED")
                && uri.contains("reason=JOURNAL_HEAD_MISMATCH"));
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("RELEASE_REJECTED")
                && uri.contains("reason=HOLD_REVISION_MISMATCH"));
    }

    @Test
    void 署名した後にheadが動けばJournalのCASで拒否しpendingを戻す() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7052");
        PrivacyHoldReleaseRequest first = request(started.holdId(), newCommandId(), REASON);
        PrivacyHoldReleaseRequest racing = request(started.holdId(), newCommandId(), REASON);
        AtomicReference<PrivacyHoldReleaseResult> winner = new AtomicReference<>();
        // 2つ目の解除の予約の後・append の直前に、1つ目の解除が別の経路で Journal に確定した状態を作る
        fixture.journal.beforeAppend = () -> {
            fixture.journal.beforeAppend = () -> { };
            HoldReleaseBinding binding = bindingOf(first);
            winner.set(new PrivacyHoldReleaseResult(started.holdId(), "RELEASED", fixture.journal.appendHoldRelease(
                    verifier.verify(binding, binding, "SUBJECT", fixture.decisions.releaseRules(), claimOf(first.issuer(),
                            HoldReleaseAuthorityClaim.Role.ISSUER), claimOf(first.approver(),
                            HoldReleaseAuthorityClaim.Role.APPROVER))).revision(), false));
        };

        assertBusiness(() -> release.release(started.holdId(), racing), 409, "error.privacy.holdReleaseStale");

        assertThat(winner.get().releaseRevision()).isEqualTo(2);
        assertThat(fixture.journal.eventCount(TENANT, "ENGINEER:7052")).isEqualTo(2);
        assertThat(fixture.state().pendingCommandId()).as("拒否された append の pending は戻す").isNull();
    }

    @Test
    void 署名が正しくなければ403で拒否し状態を変えない() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7053");
        PrivacyHoldReleaseRequest good = request(started.holdId(), newCommandId(), REASON);
        byte[] signature = Base64.getDecoder().decode(good.approver().signature());
        signature[3] ^= 0x10;
        PrivacyHoldReleaseRequest forged = new PrivacyHoldReleaseRequest(good.reasonCode(), good.reasonText(),
                good.ticketRef(), good.binding(), good.issuer(), new PrivacyHoldReleaseRequest.Claim(
                good.approver().authorityId(), good.approver().keyId(), good.approver().issuedAt(),
                good.approver().expiresAt(), Base64.getEncoder().encodeToString(signature)));

        assertBusiness(() -> release.release(started.holdId(), forged), 403, "error.privacy.holdReleaseProofRejected");
        assertThat(fixture.journal.eventCount(TENANT, "ENGINEER:7053")).isEqualTo(1);
        assertThat(fixture.store.hold(TENANT, started.holdId()).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("reason=SIGNATURE_INVALID"));
    }

    @Test
    void 理由本文が署名した理由hashと違えば拒否する() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7054");
        PrivacyHoldReleaseRequest signed = request(started.holdId(), newCommandId(), REASON);
        PrivacyHoldReleaseRequest edited = new PrivacyHoldReleaseRequest(signed.reasonCode(), REASON + "（追記）",
                signed.ticketRef(), signed.binding(), signed.issuer(), signed.approver());

        assertBusiness(() -> release.release(started.holdId(), edited), 403, "error.privacy.holdReleaseProofRejected");
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("reason=REASON_HASH_MISMATCH"));
    }

    @Test
    void 理由コードやticketを署名の後に変えれば拒否する() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7072");
        PrivacyHoldReleaseRequest signed = request(started.holdId(), newCommandId(), REASON);

        assertBusiness(() -> release.release(started.holdId(), new PrivacyHoldReleaseRequest(signed.reasonCode(),
                signed.reasonText(), "TICKET-9999", signed.binding(), signed.issuer(), signed.approver())), 403,
                "error.privacy.holdReleaseProofRejected");
        assertBusiness(() -> release.release(started.holdId(), new PrivacyHoldReleaseRequest("OTHER_REASON",
                signed.reasonText(), signed.ticketRef(), signed.binding(), signed.issuer(), signed.approver())), 403,
                "error.privacy.holdReleaseProofRejected");
        assertThat(fixture.auditUris.stream().filter(uri -> uri.contains("reason=REASON_HASH_MISMATCH")).count())
                .isEqualTo(2);
        assertThat(fixture.journal.eventCount(TENANT, "ENGINEER:7072")).isEqualTo(1);
    }

    @Test
    void reconcilerは開始要求の監査から復元行の開始者を戻し既定の規則のまま解除できる() {
        fixture.persistAudit = true;
        fixture.user(1L, "privacy-admin-a");

        String holdId = reconciledHold("ENGINEER:7073");

        PrivacyHoldProjectionStore.HoldRow row = fixture.store.hold(TENANT, holdId).orElseThrow();
        assertThat(row.reasonCode()).isEqualTo(PrivacyHoldService.RECONCILED);
        assertThat(row.requestedBy()).as("開始要求の監査の username から sys_user.id を引いた").isEqualTo(1L);
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains(PrivacyHoldAudit.RECONCILE_PROJECTED)
                && uri.contains("starter=AUDIT"));
        PrivacyHoldReleaseResult released = release.release(holdId, request(holdId, newCommandId(), REASON));
        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("RELEASE_REQUESTED") && uri.contains("starter=KNOWN"));
    }

    @Test
    void 開始者が一意に定まらない復元行は不明のまま解除を拒否し後で定まればreconcilerが戻す() {
        fixture.persistAudit = true;
        String holdId = reconciledHold("ENGINEER:7077");
        assertThat(PrivacyHoldReleaseService.starterRef(fixture.store.hold(TENANT, holdId).orElseThrow()))
                .as("sys_user に開始者の行が無い").isEqualTo(HoldReleaseBinding.UNKNOWN_STARTER);

        assertBusiness(() -> release.release(holdId, request(holdId, newCommandId(), REASON)), 409,
                "error.privacy.holdReleaseStarterUnknown");
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("reason=HOLD_STARTER_UNKNOWN"));
        assertThat(fixture.journal.eventCount(TENANT, "ENGINEER:7077")).isEqualTo(1);
        assertThat(fixture.reconciler.reconcileAll()).doesNotContainKey(
                PrivacyHoldProjectionReconciler.Outcome.STARTER_RESTORED);

        // 後の回で開始者が一意に定まれば戻す（以前の回で照会できなかった行も同じ）
        fixture.user(1L, "privacy-admin-a");
        assertThat(fixture.reconciler.reconcileAll()).containsEntry(
                PrivacyHoldProjectionReconciler.Outcome.STARTER_RESTORED, 1);
        assertThat(fixture.store.hold(TENANT, holdId).orElseThrow().requestedBy()).isEqualTo(1L);
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains(PrivacyHoldAudit.RECONCILE_STARTER_RESTORED));
        assertThat(release.release(holdId, request(holdId, newCommandId(), REASON)).status()).isEqualTo("RELEASED");
    }

    @Test
    void 開始要求の監査が別人を指す_実行者idが食い違う_他のtenantの利用者なら開始者は定めない() {
        fixture.persistAudit = true;
        fixture.user(1L, "privacy-admin-a");
        fixture.user(2L, "privacy-admin-b");
        PrivacyHoldStarterLookup lookup = new PrivacyHoldStarterLookup(fixture.jdbc);
        String holdId = startSubject("ENGINEER:7078").holdId();
        assertThat(lookup.starterOf(TENANT, holdId)).hasValue(1L);
        assertThat(lookup.starterOf("other-tenant", holdId)).as("sys_user の tenant が違う").isEmpty();
        assertThat(lookup.starterOf(TENANT, "ph-" + "0".repeat(32))).as("監査が無い").isEmpty();

        // 同じ hold の開始要求の監査が別の username を指す
        fixture.jdbc.update("INSERT INTO t_audit_log (username, method, uri, status, application_code, success_flag) "
                + "SELECT 'privacy-admin-b', method, uri, status, application_code, success_flag FROM t_audit_log "
                + "WHERE application_code = 'NF07_HOLD_START_REQUESTED'");
        assertThat(lookup.starterOf(TENANT, holdId)).isEmpty();

        String other = startSubject("ENGINEER:7079").holdId();
        // 監査の実行者 id が username の id と食い違う
        fixture.jdbc.update("UPDATE t_audit_log SET human_user_id = 2 WHERE uri LIKE ?", "%hold=" + other + "&%");
        assertThat(lookup.starterOf(TENANT, other)).isEmpty();
    }

    @Test
    void 検証の後に開始者の値が変われば予約で409にして署名し直させる() {
        String holdId = reconciledHold("ENGINEER:7080");
        fixture.holdProperties.getRelease().setUnknownStarter("ACCEPT");
        PrivacyHoldProjectionStore.HoldRow row = fixture.store.hold(TENANT, holdId).orElseThrow();
        java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean(true);
        // 解除の予約の最初の statement（追随位置の行の作成・lock）の直前に、開始の service が開始者の値を戻した状態を作る
        fixture.dataSource.beforePrepare = sql -> {
            if (sql.toLowerCase(java.util.Locale.ROOT).startsWith("insert into t_privacy_projection_state")
                    && armed.getAndSet(false)) {
                fixture.store.adoptReconciledMetadata(TENANT, new PrivacyHoldProjectionStore.NewHold(holdId,
                        PrivacyHoldTargetType.SUBJECT, "ENGINEER:7080", "LITIGATION", "1".repeat(64), "TICKET-1001", 1L,
                        PrivacyHoldTestFixture.T0, row.journalRevision(), row.journalEventHash()));
            }
        };

        assertBusiness(() -> release.release(holdId, request(holdId, newCommandId(), REASON)), 409,
                "error.privacy.holdReleaseStale");
        assertThat(armed.get()).as("予約の前に開始者が変わった").isFalse();
        assertThat(fixture.journal.eventCount(TENANT, "ENGINEER:7080")).isEqualTo(1);
        assertThat(fixture.state().pendingCommandId()).isNull();
    }

    @Test
    void 解除のpendingの間はreconcilerが開始者を書き換えない() {
        fixture.persistAudit = true;
        String holdId = reconciledHold("ENGINEER:7081");
        fixture.holdProperties.getRelease().setUnknownStarter("ACCEPT");
        PrivacyHoldReleaseRequest request = request(holdId, newCommandId(), REASON);
        java.util.concurrent.atomic.AtomicLong starterDuringAppend = new java.util.concurrent.atomic.AtomicLong(-1);
        fixture.journal.beforeAppend = () -> {
            fixture.journal.beforeAppend = () -> { };
            // 解除の予約の後・append の前に開始者が一意に定まるようになり、reconciler が走る
            fixture.user(1L, "privacy-admin-a");
            fixture.reconciler.reconcileAll();
            starterDuringAppend.set(fixture.store.hold(TENANT, holdId).orElseThrow().requestedBy());
        };

        assertThat(release.release(holdId, request).status()).isEqualTo("RELEASED");

        assertThat(starterDuringAppend.get()).as("pending の間は戻さない").isZero();
        assertThat(fixture.reconciler.reconcileAll()).containsEntry(
                PrivacyHoldProjectionReconciler.Outcome.STARTER_RESTORED, 1);
        assertThat(fixture.store.hold(TENANT, holdId).orElseThrow().requestedBy()).isEqualTo(1L);
    }

    @Test
    void D05が不明の開始者を認めれば開始者との分離を判定せず解除できる() {
        String holdId = reconciledHold("ENGINEER:7074");
        fixture.holdProperties.getRelease().setUnknownStarter("ACCEPT");

        assertThat(release.release(holdId, request(holdId, newCommandId(), REASON)).status()).isEqualTo("RELEASED");
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("RELEASE_REQUESTED")
                && uri.contains("starter=UNKNOWN_ACCEPTED"));
    }

    @Test
    void 発行者がholdの開始者なら既定で拒否しD05が分離を求めなければ解除できる() {
        fixture.actor = new PrivacyHoldService.Actor(TENANT, 20L, "privacy-admin-issuer");
        PrivacyHoldStartResult started = startSubject("ENGINEER:7075");
        fixture.actor = new PrivacyHoldService.Actor(TENANT, 1L, "privacy-admin-a");

        assertBusiness(() -> release.release(started.holdId(), request(started.holdId(), newCommandId(), REASON)), 403,
                "error.privacy.holdReleaseSeparation");

        fixture.holdProperties.getRelease().setStarterSeparation("NOT_REQUIRED");
        assertThat(release.release(started.holdId(), request(started.holdId(), newCommandId(), REASON)).status())
                .isEqualTo("RELEASED");
    }

    @Test
    void 画面の一覧は束縛の全項目を組み立てられる値を返す() {
        PrivacyHoldStartResult subject = startSubject("ENGINEER:7076");
        PrivacyHoldStartResult element = fixture.service.start(fixture.elementRequest("INTEGRATION_HUB_AUDIT"));

        PrivacyHoldOverview overview = fixture.service.overview();

        assertThat(overview.tenantId()).isEqualTo(TENANT);
        assertThat(overview.requesterRef()).isEqualTo("sys_user:1");
        assertThat(overview.holds()).filteredOn(entry -> entry.holdId().equals(subject.holdId())).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.partitionRef()).isEqualTo("ENGINEER:7076");
                    assertThat(entry.holdStarterRef()).isEqualTo("sys_user:1");
                    assertThat(entry.journalEventHash()).isEqualTo(
                            fixture.journal.head(new JournalPartition(TENANT, "ENGINEER:7076")).eventHash());
                });
        assertThat(overview.holds()).filteredOn(entry -> entry.holdId().equals(element.holdId())).singleElement()
                .satisfies(entry -> assertThat(entry.partitionRef()).isEqualTo("DATA_ELEMENT#INTEGRATION_HUB_AUDIT"));
    }

    @Test
    void 要求者が発行者と同じ主体なら三方の分離に反し403() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7055");
        fixture.actor = new PrivacyHoldService.Actor(TENANT, 20L, "issuer-as-requester");

        assertBusiness(() -> release.release(started.holdId(), request(started.holdId(), newCommandId(), REASON)), 403,
                "error.privacy.holdReleaseSeparation");
        assertThat(fixture.journal.eventCount(TENANT, "ENGINEER:7055")).isEqualTo(1);
    }

    @Test
    void 別のprivacy_adminが他人の束縛で解除を要求すると要求者の不一致で拒否する() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7056");
        PrivacyHoldReleaseRequest requestedByA = request(started.holdId(), newCommandId(), REASON);
        fixture.actor = new PrivacyHoldService.Actor(TENANT, 2L, "privacy-admin-b");

        assertBusiness(() -> release.release(started.holdId(), requestedByA), 403,
                "error.privacy.holdReleaseProofRejected");
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("reason=REQUESTER_MISMATCH"));
    }

    @Test
    void 束縛のtenantが呼出し元と違えば拒否し呼出し元のtenantが権限評価のtenantと違えば403() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7057");
        HoldReleaseBinding signed = bindingOf(request(started.holdId(), newCommandId(), REASON));
        HoldReleaseBinding otherTenant = new HoldReleaseBinding("tenant-b", signed.subjectRef(), signed.holdId(),
                signed.expectedHoldRevision(), signed.reasonHash(), signed.commandId(), signed.expectedJournalRevision(),
                signed.expectedJournalHash(), signed.requesterRef(), signed.holdStarterRef());
        assertBusiness(() -> release.release(started.holdId(), signedRequest(otherTenant, REASON)), 403,
                "error.privacy.holdReleaseProofRejected");
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("reason=TENANT_MISMATCH"));

        fixture.actor = new PrivacyHoldService.Actor("tenant-b", 1L, "privacy-admin-a");
        assertBusiness(() -> release.release(started.holdId(), request(started.holdId(), newCommandId(), REASON)), 403,
                "error.accessDenied");
    }

    @Test
    void 別のholdへの署名は流用できない() {
        PrivacyHoldStartResult a = startSubject("ENGINEER:7058");
        PrivacyHoldStartResult b = startSubject("ENGINEER:7059");

        assertBusiness(() -> release.release(b.holdId(), request(a.holdId(), newCommandId(), REASON)), 403,
                "error.privacy.holdReleaseProofRejected");
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("reason=SUBJECT_MISMATCH"));
        assertThat(fixture.journal.eventCount(TENANT, "ENGINEER:7059")).isEqualTo(1);
    }

    @Test
    void append時点でproofの期限が切れていれば拒否しpendingを戻す() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7060");
        PrivacyHoldReleaseRequest request = request(started.holdId(), newCommandId(), REASON);
        fixture.journal.beforeAppend = () -> fixture.clock.advance(Duration.ofHours(1));

        assertBusiness(() -> release.release(started.holdId(), request), 403, "error.privacy.holdReleaseProofRejected");
        assertThat(fixture.journal.eventCount(TENANT, "ENGINEER:7060")).isEqualTo(1);
        assertThat(fixture.state().pendingCommandId()).isNull();
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("reason=EXPIRED"));
    }

    // --- pending の直列化（hold 開始と同じ追随位置の行） ---

    @Test
    void 同じtenantで別のcommandがpendingなら解除は409で待たせる() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7061");
        fixture.store.recordPending(TENANT, PrivacyHoldTestFixture.JOURNAL_ID, "hs-" + "1".repeat(32), Instant.now());
        int appends = fixture.journal.appendCalls.get();

        assertBusiness(() -> release.release(started.holdId(), request(started.holdId(), newCommandId(), REASON)), 409,
                "error.privacy.holdReleaseInProgress");
        assertThat(fixture.journal.appendCalls.get()).isEqualTo(appends);
    }

    @Test
    void 解除のappend中に同じtenantのhold開始は409で待たされる() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7062");
        AtomicReference<Throwable> concurrentStart = new AtomicReference<>();
        fixture.journal.beforeAppend = () -> {
            fixture.journal.beforeAppend = () -> { };
            try {
                fixture.service.start(fixture.subjectRequest("ENGINEER:7063", "ACCEPT_RISK", "ACCEPT_RISK"));
            } catch (Throwable e) {
                concurrentStart.set(e);
            }
        };

        release.release(started.holdId(), request(started.holdId(), newCommandId(), REASON));

        assertThat(concurrentStart.get()).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(409);
            assertThat(e.getMessageKey()).isEqualTo("error.privacy.holdStartInProgress");
        });
        assertThat(fixture.journal.eventCount(TENANT, "ENGINEER:7063")).isZero();
    }

    @Test
    void 帰属できないelementのDATA_ELEMENT_holdはSUBJECT_holdが有効な間は解除しない() {
        PrivacyHoldStartResult element = fixture.service.start(fixture.elementRequest("DOCUMENT:IMPORT"));
        PrivacyHoldStartResult subject = startSubject("ENGINEER:7064", "DATA_ELEMENT_HOLD");

        assertBusiness(() -> release.release(element.holdId(), request(element.holdId(), newCommandId(), REASON)), 409,
                "error.privacy.holdReleaseDependency");
        assertThat(fixture.state().pendingCommandId()).isNull();

        release.release(subject.holdId(), request(subject.holdId(), newCommandId(), REASON));
        PrivacyHoldReleaseResult released = release.release(element.holdId(),
                request(element.holdId(), newCommandId(), REASON));
        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(fixture.gate.evaluate(TENANT).heldDataElements()).isEmpty();
    }

    // --- 入口・開放の gate・要求の検査 ---

    @Test
    void D03とD05が有効でtrust_anchorとverifierが揃うまで解除は404でJournalに触れない() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7065");
        PrivacyHoldReleaseRequest request = request(started.holdId(), newCommandId(), REASON);
        int appends = fixture.journal.appendCalls.get();
        assertThat(release.isOpen()).isTrue();
        assertThat(fixture.service.overview().releaseReady()).as("overview は既定で解除の状態を出さない").isFalse();

        fixture.approval.getD03().setDecisionId(null);
        assertNotOpen(release, started.holdId(), request);
        fixture.approval.getD03().setDecisionId("D03-PLACEHOLDER-001");
        assertNotOpen(release, started.holdId(), request);
        fixture.approval.getD03().setDecisionId("D03-NF07-TEST-001");
        fixture.approval.getD03().setExpiresOn("2026-11-16");
        assertNotOpen(release, started.holdId(), request);
        fixture.approval.getD03().setExpiresOn("2027-06-01");
        fixture.approval.getD05().setDecisionId("D05-TBD-0001");
        assertNotOpen(release, started.holdId(), request);
        fixture.approval.getD05().setDecisionId("D05-NF07-TEST-001");

        assertNotOpen(fixture.releaseService(verifier, ConfiguredHoldReleaseTrustRegistry.empty()), started.holdId(),
                request);
        assertNotOpen(fixture.releaseService(null, authorities.registry()), started.holdId(), request);

        assertThat(fixture.journal.appendCalls.get()).isEqualTo(appends);
        assertThat(fixture.store.hold(TENANT, started.holdId()).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(release.release(started.holdId(), request).status()).isEqualTo("RELEASED");
    }

    @Test
    void 本番構成の空のregistryと常にUNKNOWNの失効providerでは解除は開かず検証も通らない() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7066");
        PrivacyHoldReleaseRequest request = request(started.holdId(), newCommandId(), REASON);
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        HoldReleaseVerifier phaseOne = ProductionPrivacyComponents.holdReleaseVerifier(prod,
                ConfiguredHoldReleaseTrustRegistry.empty(),
                new com.ses.privacy.production.retention.policy.UnknownRevocationStatusProvider(), fixture.clock);
        assertNotOpen(fixture.releaseService(phaseOne, ConfiguredHoldReleaseTrustRegistry.empty()), started.holdId(),
                request);

        // trust anchor が登録されても（NF07-TRUST の前に誤って登録された場合）、失効状態が UNKNOWN の間は拒否する
        HoldReleaseVerifier unknownRevocation = ProductionPrivacyComponents.holdReleaseVerifier(prod,
                authorities.registry(), new com.ses.privacy.production.retention.policy.UnknownRevocationStatusProvider(),
                fixture.clock);
        assertBusiness(() -> fixture.releaseService(unknownRevocation, authorities.registry())
                .release(started.holdId(), request), 403, "error.privacy.holdReleaseProofRejected");
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains("reason=REVOCATION_UNKNOWN"));
        assertThat(fixture.journal.eventCount(TENANT, "ENGINEER:7066")).isEqualTo(1);
    }

    @Test
    void privacy_hold_releaseの権限が無ければ403で何も読まない() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7067");
        PrivacyHoldReleaseRequest request = request(started.holdId(), newCommandId(), REASON);
        doThrow(BusinessException.of(403, "error.accessDenied")).when(fixture.guard)
                .requireAllowed(any(), eq("privacy.hold.release"));
        int statements = fixture.dataSource.statements.get();

        assertBusiness(() -> release.release(started.holdId(), request), 403, "error.accessDenied");
        assertThat(fixture.dataSource.statements.get()).isEqualTo(statements);
    }

    @Test
    void 公開スイッチOFFは404() {
        doThrow(new PrivacyAccessGuard.PrivacyEntryNotFoundException()).when(fixture.guard)
                .requireAllowed(any(), eq("privacy.hold.release"));

        assertThatThrownBy(() -> release.release("ph-" + "1".repeat(32), null))
                .isInstanceOf(PrivacyAccessGuard.PrivacyEntryNotFoundException.class);
    }

    @Test
    void 形式の不正な要求は400で拒否する() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7068");
        PrivacyHoldReleaseRequest good = request(started.holdId(), newCommandId(), REASON);
        PrivacyHoldReleaseRequest.Binding b = good.binding();

        assertInvalid("not-a-hold", good);
        assertInvalid(started.holdId(), null);
        assertInvalid(started.holdId(), new PrivacyHoldReleaseRequest("lower", REASON, "TICKET-1", b, good.issuer(),
                good.approver()));
        assertInvalid(started.holdId(), new PrivacyHoldReleaseRequest("RECONCILED", REASON, "TICKET-1", b, good.issuer(),
                good.approver()));
        assertInvalid(started.holdId(), new PrivacyHoldReleaseRequest(good.reasonCode(), " ", "TICKET-1", b,
                good.issuer(), good.approver()));
        // 対になっていない surrogate は Java と画面で正規形の byte 列が一致しないため 400（delta review P3-1）
        assertInvalid(started.holdId(), new PrivacyHoldReleaseRequest(good.reasonCode(), "理由\uD800", "TICKET-1", b,
                good.issuer(), good.approver()));
        assertInvalid(started.holdId(), new PrivacyHoldReleaseRequest(good.reasonCode(), REASON, "TICKET-1",
                new PrivacyHoldReleaseRequest.Binding(b.tenantId(), b.subjectRef(), b.holdId(), b.expectedHoldRevision(),
                        b.reasonHash(), "hs-" + "1".repeat(32), b.expectedJournalRevision(), b.expectedJournalHash(),
                        b.requesterRef(), b.holdStarterRef()), good.issuer(), good.approver()));
        assertInvalid(started.holdId(), new PrivacyHoldReleaseRequest(good.reasonCode(), REASON, "TICKET-1",
                new PrivacyHoldReleaseRequest.Binding(b.tenantId(), b.subjectRef(), b.holdId(), null, b.reasonHash(),
                        b.commandId(), b.expectedJournalRevision(), b.expectedJournalHash(), b.requesterRef(),
                        b.holdStarterRef()), good.issuer(), good.approver()));
        // 主体の正規形でない開始者（大文字・先頭の 0）は束縛として受け付けない
        assertInvalid(started.holdId(), new PrivacyHoldReleaseRequest(good.reasonCode(), REASON, "TICKET-1",
                new PrivacyHoldReleaseRequest.Binding(b.tenantId(), b.subjectRef(), b.holdId(), b.expectedHoldRevision(),
                        b.reasonHash(), b.commandId(), b.expectedJournalRevision(), b.expectedJournalHash(),
                        b.requesterRef(), "SYS_USER:01"), good.issuer(), good.approver()));
        assertInvalid(started.holdId(), new PrivacyHoldReleaseRequest(good.reasonCode(), REASON, "TICKET-1", b, null,
                good.approver()));
        assertInvalid(started.holdId(), new PrivacyHoldReleaseRequest(good.reasonCode(), REASON, "TICKET-1", b,
                new PrivacyHoldReleaseRequest.Claim(ISSUER, "k1", "not-a-time", good.issuer().expiresAt(),
                        good.issuer().signature()), good.approver()));
        assertInvalid(started.holdId(), new PrivacyHoldReleaseRequest(good.reasonCode(), REASON, "TICKET-1", b,
                new PrivacyHoldReleaseRequest.Claim(ISSUER, "k1", good.issuer().issuedAt(), good.issuer().expiresAt(),
                        "%%%"), good.approver()));
        assertInvalid(started.holdId(), new PrivacyHoldReleaseRequest(good.reasonCode(), REASON, "TICKET-1", b,
                new PrivacyHoldReleaseRequest.Claim("issuer a", "k1", good.issuer().issuedAt(),
                        good.issuer().expiresAt(), good.issuer().signature()), good.approver()));
        assertThat(fixture.journal.eventCount(TENANT, "ENGINEER:7068")).isEqualTo(1);
    }

    @Test
    void holdが無ければ404_解除済みのholdを別のcommandで解除すれば409() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7069");
        PrivacyHoldReleaseRequest request = request(started.holdId(), newCommandId(), REASON);
        String missing = "ph-" + "0".repeat(32);
        assertBusiness(() -> release.release(missing, request), 404, "error.privacy.holdNotFound");

        release.release(started.holdId(), request);
        assertBusiness(() -> release.release(started.holdId(), request(started.holdId(), newCommandId(), REASON)), 409,
                "error.privacy.holdNotActive");
    }

    @Test
    void Journalに届かなければ503で状態を変えない() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7070");
        PrivacyHoldReleaseRequest request = request(started.holdId(), newCommandId(), REASON);
        fixture.journal.unavailable = true;

        assertBusiness(() -> release.release(started.holdId(), request), 503, "error.privacy.journalUnavailable");
        fixture.journal.unavailable = false;
        assertThat(fixture.state().pendingCommandId()).isNull();
        assertThat(fixture.store.hold(TENANT, started.holdId()).orElseThrow().status()).isEqualTo("ACTIVE");
    }

    @Test
    void 汎用のappendではHOLD_RELEASEDを受け付けない() {
        PrivacyHoldStartResult started = startSubject("ENGINEER:7071");
        JournalHead head = fixture.journal.head(new JournalPartition(TENANT, "ENGINEER:7071"));

        assertThatThrownBy(() -> fixture.journal.append(com.ses.privacy.foundation.PrivacyJournalPort.JournalCommand.create(
                newCommandId(), head.partition(), head.revision(),
                com.ses.privacy.foundation.PrivacyJournalPort.CommandType.HOLD_RELEASED, started.holdId(), null,
                "1".repeat(64), null, 0, null))).isInstanceOf(SecurityException.class);
        assertThat(fixture.journal.eventCount(TENANT, "ENGINEER:7071")).isEqualTo(1);
    }

    // --- helpers ---

    private PrivacyHoldStartResult startSubject(String subjectRef) {
        return startSubject(subjectRef, "ACCEPT_RISK");
    }

    private PrivacyHoldStartResult startSubject(String subjectRef, String importChoice) {
        return fixture.service.start(fixture.subjectRequest(subjectRef, importChoice, "ACCEPT_RISK"));
    }

    private static String newCommandId() {
        return "hr-" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * ACK 不明が続いた hold 開始を reconciler が Journal から復元した行を作る（reconciler の実際の経路。開始者は、
     * {@code persistAudit} と {@code sys_user} の行があれば開始要求の監査から戻り、無ければ不明の 0）。
     */
    private String reconciledHold(String subjectRef) {
        fixture.journal.commitThenFail.set(2);
        assertThatThrownBy(() -> fixture.service.start(fixture.subjectRequest(subjectRef, "ACCEPT_RISK", "ACCEPT_RISK")))
                .isInstanceOf(BusinessException.class);
        fixture.reconciler.reconcileAll();
        PrivacyHoldProjectionStore.HoldRow row = fixture.store.holds(TENANT, false).stream()
                .filter(hold -> subjectRef.equals(hold.subjectRef())).findFirst().orElseThrow();
        assertThat(row.reasonCode()).isEqualTo(PrivacyHoldService.RECONCILED);
        assertThat(fixture.state().pendingCommandId()).isNull();
        return row.holdId();
    }

    /** 現在の projection・Journal から束縛を組み立て、発行者・承認者が署名した要求を作る（署名は test-only 鍵）。 */
    private PrivacyHoldReleaseRequest request(String holdId, String commandId, String reasonText) {
        PrivacyHoldProjectionStore.HoldRow hold = fixture.store.hold(TENANT, holdId).orElseThrow();
        JournalHead head = fixture.journal.head(new JournalPartition(TENANT, hold.partitionRef()));
        HoldReleaseBinding binding = new HoldReleaseBinding(TENANT, hold.partitionRef(), holdId, hold.journalRevision(),
                HoldReleaseBinding.reasonHash(REASON_CODE, TICKET, reasonText), commandId, head.revision(),
                head.eventHash(), "sys_user:" + fixture.actor.userId(), PrivacyHoldReleaseService.starterRef(hold));
        return signedRequest(binding, reasonText);
    }

    private PrivacyHoldReleaseRequest signedRequest(HoldReleaseBinding binding, String reasonText) {
        Instant now = fixture.clock.instant();
        HoldReleaseAuthorityClaim issuer = authorities.sign(ISSUER, HoldReleaseAuthorityClaim.Role.ISSUER, binding,
                now.minusSeconds(60), now.plus(Duration.ofMinutes(30)));
        HoldReleaseAuthorityClaim approver = authorities.sign(APPROVER, HoldReleaseAuthorityClaim.Role.APPROVER, binding,
                now.minusSeconds(60), now.plus(Duration.ofHours(2)));
        return new PrivacyHoldReleaseRequest(REASON_CODE, reasonText, TICKET,
                new PrivacyHoldReleaseRequest.Binding(binding.tenantId(), binding.subjectRef(), binding.holdId(),
                        binding.expectedHoldRevision(), binding.reasonHash(), binding.commandId(),
                        binding.expectedJournalRevision(), binding.expectedJournalHash(), binding.requesterRef(),
                        binding.holdStarterRef()),
                claim(issuer), claim(approver));
    }

    private static PrivacyHoldReleaseRequest.Claim claim(HoldReleaseAuthorityClaim claim) {
        return new PrivacyHoldReleaseRequest.Claim(claim.authorityId(), claim.keyId(), claim.issuedAt().toString(),
                claim.expiresAt().toString(), Base64.getEncoder().encodeToString(claim.signature()));
    }

    private static HoldReleaseAuthorityClaim claimOf(PrivacyHoldReleaseRequest.Claim claim,
                                                     HoldReleaseAuthorityClaim.Role role) {
        return new HoldReleaseAuthorityClaim(role, claim.authorityId(), claim.keyId(), Instant.parse(claim.issuedAt()),
                Instant.parse(claim.expiresAt()), Base64.getDecoder().decode(claim.signature()));
    }

    private static HoldReleaseBinding bindingOf(PrivacyHoldReleaseRequest request) {
        PrivacyHoldReleaseRequest.Binding b = request.binding();
        return new HoldReleaseBinding(b.tenantId(), b.subjectRef(), b.holdId(), b.expectedHoldRevision(), b.reasonHash(),
                b.commandId(), b.expectedJournalRevision(), b.expectedJournalHash(), b.requesterRef(),
                b.holdStarterRef());
    }

    private void assertNotOpen(PrivacyHoldReleaseService service, String holdId, PrivacyHoldReleaseRequest request) {
        assertThat(service.isOpen()).isFalse();
        assertThatThrownBy(() -> service.release(holdId, request))
                .isInstanceOf(PrivacyAccessGuard.PrivacyEntryNotFoundException.class);
    }

    private void assertInvalid(String holdId, PrivacyHoldReleaseRequest request) {
        assertBusiness(() -> release.release(holdId, request), 400, "error.privacy.holdReleaseInvalidRequest");
    }

    private static void assertBusiness(Runnable call, int code, String key) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(code);
            assertThat(e.getMessageKey()).isEqualTo(key);
        });
    }
}
