package com.ses.privacy.production.retention.hold;

import com.ses.config.feature.FeatureSwitches;
import com.ses.privacy.foundation.PrivacyJournalPort;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyFeatureProperties;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

/**
 * NF07 hold の gate（A1H-1、production-bridge-phased-plan.md §3.4.4・§3.4.3）。未使用 tenant は照会1回で「hold なし」
 * （Journal 不達でも）、追随遅れ・pending 残り・不達・journal 設定なし・projection の改変で UNAVAILABLE、flag に依存しない、
 * 行ごとの判定（SUBJECT・DATA_ELEMENT・帰属できない行・UNKNOWN の fail-closed）を確かめる。
 */
class PrivacyHoldGateTest {

    private static final String TENANT = PrivacyHoldTestFixture.TENANT;

    private PrivacyHoldTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new PrivacyHoldTestFixture("nf07-hold-gate-" + UUID.randomUUID());
        fixture.defaultCatalog();
        fixture.catalog("DOCUMENT:WORK_REPORT", "ATTRIBUTABLE");
    }

    @AfterEach
    void tearDown() {
        fixture.dropSchema();
    }

    @Test
    void hold機構を使っていないtenantは照会1回でholdなしとしJournal不達でも削除を止めない() {
        fixture.journal.unavailable = true;
        fixture.startupResult = new PrivacyJournalStartupVerifier.Result(
                PrivacyJournalStartupVerifier.Availability.UNAVAILABLE, List.of("connection.failed"));
        fixture.dataSource.statements.set(0);

        PrivacyHoldGate.Decision decision = fixture.gate.evaluate(TENANT);

        assertThat(decision.state()).isEqualTo(PrivacyHoldGate.State.NO_HOLDS);
        assertThat(fixture.dataSource.statements.get()).as("追加の照会は1回だけ").isEqualTo(1);
        assertThat(decision.verdict("DOCUMENT:WORK_REPORT", List.of("ENGINEER:10")))
                .isEqualTo(PrivacyHoldGate.Verdict.ALLOW);
        assertThat(decision.verdict("AUDIT_LOG", List.of())).as("未使用tenantではUNKNOWNでも止めない")
                .isEqualTo(PrivacyHoldGate.Verdict.ALLOW);
    }

    @Test
    void Journalと一致すればhold集合を返し行ごとの判定に使える() {
        fixture.service.start(fixture.elementRequest("INTEGRATION_HUB_AUDIT"));
        fixture.service.start(fixture.subjectRequest("ENGINEER:10", "ACCEPT_RISK", "DATA_ELEMENT_HOLD"));

        PrivacyHoldGate.Decision decision = fixture.gate.evaluate(TENANT);

        assertThat(decision.state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
        assertThat(decision.reason()).isNull();
        assertThat(decision.heldSubjects()).containsExactly("ENGINEER:10");
        assertThat(decision.heldDataElements()).containsExactly("INTEGRATION_HUB_AUDIT");
        assertThat(decision.catalog()).containsKeys("DOCUMENT:IMPORT", "INTEGRATION_HUB_AUDIT", "API_NONCE", "AUDIT_LOG");
        assertThat(decision.verdict("INTEGRATION_HUB_AUDIT", List.of())).isEqualTo(PrivacyHoldGate.Verdict.HELD);
        assertThat(decision.verdict("DOCUMENT:IMPORT", List.of())).isEqualTo(PrivacyHoldGate.Verdict.ALLOW);
    }

    @Test
    void 行ごとの判定はSUBJECTとDATA_ELEMENTと帰属できない行とUNKNOWNを区別する() {
        PrivacyHoldGate.Decision decision = new PrivacyHoldGate.Decision(PrivacyHoldGate.State.VERIFIED, null,
                Set.of("ENGINEER:10"), Set.of("INTEGRATION_HUB_AUDIT"), java.util.Map.of(
                "DOCUMENT:WORK_REPORT", SubjectAttribution.ATTRIBUTABLE,
                "DOCUMENT:IMPORT", SubjectAttribution.NOT_ATTRIBUTABLE,
                "INTEGRATION_HUB_AUDIT", SubjectAttribution.NOT_ATTRIBUTABLE,
                "API_NONCE", SubjectAttribution.NO_PERSONAL_DATA,
                "AUDIT_LOG", SubjectAttribution.UNKNOWN));
        // 帰属できる行: SUBJECT hold に当たる行だけ止める（和集合の本人のどれかが当たれば止める）
        assertThat(decision.verdict("DOCUMENT:WORK_REPORT", List.of("ENGINEER:10"))).isEqualTo(PrivacyHoldGate.Verdict.HELD);
        assertThat(decision.verdict("DOCUMENT:WORK_REPORT", List.of("CANDIDATE:3", "ENGINEER:10")))
                .isEqualTo(PrivacyHoldGate.Verdict.HELD);
        assertThat(decision.verdict("DOCUMENT:WORK_REPORT", List.of("ENGINEER:11"))).isEqualTo(PrivacyHoldGate.Verdict.ALLOW);
        // 帰属できない行: SUBJECT hold では止めず、DATA_ELEMENT hold のときだけ止める
        assertThat(decision.verdict("DOCUMENT:WORK_REPORT", List.of())).isEqualTo(PrivacyHoldGate.Verdict.ALLOW);
        assertThat(decision.verdict("DOCUMENT:IMPORT", List.of())).isEqualTo(PrivacyHoldGate.Verdict.ALLOW);
        assertThat(decision.verdict("INTEGRATION_HUB_AUDIT", List.of())).isEqualTo(PrivacyHoldGate.Verdict.HELD);
        // UNKNOWN と catalog に無い element は fail-closed（C-2・P3-6）、個人データなしは止めない
        assertThat(decision.verdict("AUDIT_LOG", List.of())).isEqualTo(PrivacyHoldGate.Verdict.UNCLASSIFIED);
        assertThat(decision.verdict("DOCUMENT:NEW_TYPE", List.of())).isEqualTo(PrivacyHoldGate.Verdict.UNCLASSIFIED);
        assertThat(decision.verdict("API_NONCE", List.of())).isEqualTo(PrivacyHoldGate.Verdict.ALLOW);
    }

    @Test
    void ATTRIBUTABLEでもproviderの宣言が無いelementはUNKNOWNとして止める() {
        fixture.service.start(fixture.subjectRequest("ENGINEER:10", "ACCEPT_RISK", "ACCEPT_RISK"));

        PrivacyHoldGate.Decision decision = fixture.gate.evaluate(TENANT);

        // fixture の registry は provider を持たないため、ATTRIBUTABLE の catalog 行は導出できず fail-closed
        assertThat(decision.catalog().get("DOCUMENT:WORK_REPORT").name()).isEqualTo("UNKNOWN");
        assertThat(decision.verdict("DOCUMENT:WORK_REPORT", List.of("ENGINEER:99")))
                .isEqualTo(PrivacyHoldGate.Verdict.UNCLASSIFIED);
    }

    @Test
    void projectionに無いholdがJournalにあれば追随遅れとしてUNAVAILABLE() {
        fixture.service.start(fixture.subjectRequest("ENGINEER:10", "ACCEPT_RISK", "ACCEPT_RISK"));
        appendOutsideService("ENGINEER:12");

        PrivacyHoldGate.Decision decision = fixture.gate.evaluate(TENANT);

        assertThat(decision.state()).isEqualTo(PrivacyHoldGate.State.UNAVAILABLE);
        assertThat(decision.reason()).isEqualTo(PrivacyHoldGate.REASON_PROJECTION_LAG);
        assertThat(decision.verdict("DOCUMENT:WORK_REPORT", List.of())).isEqualTo(PrivacyHoldGate.Verdict.UNAVAILABLE);
    }

    @Test
    void pendingが残ればUNAVAILABLE() {
        fixture.store.ensureStateLocked(TENANT, PrivacyHoldTestFixture.JOURNAL_ID, PrivacyHoldTestFixture.T0);
        fixture.store.recordPending(TENANT, PrivacyHoldTestFixture.JOURNAL_ID, "hs-" + "b".repeat(32),
                PrivacyHoldTestFixture.T0);

        assertThat(fixture.gate.evaluate(TENANT).reason()).isEqualTo(PrivacyHoldGate.REASON_PENDING);
    }

    @Test
    void hold機構を使ったtenantではJournal不達と起動時検査の不適合と設定なしでUNAVAILABLE() {
        fixture.service.start(fixture.subjectRequest("ENGINEER:10", "ACCEPT_RISK", "ACCEPT_RISK"));

        fixture.journal.unavailable = true;
        assertThat(fixture.gate.evaluate(TENANT).reason()).isEqualTo(PrivacyHoldGate.REASON_JOURNAL_UNAVAILABLE);
        fixture.journal.unavailable = false;

        fixture.startupResult = new PrivacyJournalStartupVerifier.Result(
                PrivacyJournalStartupVerifier.Availability.UNAVAILABLE, List.of("grants.excess"));
        assertThat(fixture.gate.evaluate(TENANT).reason()).isEqualTo(PrivacyHoldGate.REASON_JOURNAL_UNAVAILABLE);

        fixture.startupResult = new PrivacyJournalStartupVerifier.Result(
                PrivacyJournalStartupVerifier.Availability.NOT_CONFIGURED, List.of());
        assertThat(fixture.gate.evaluate(TENANT).reason()).isEqualTo(PrivacyHoldGate.REASON_JOURNAL_NOT_CONFIGURED);
    }

    @Test
    void projectionの件数やhashが追随位置と食い違えばUNAVAILABLE() {
        fixture.service.start(fixture.subjectRequest("ENGINEER:10", "ACCEPT_RISK", "ACCEPT_RISK"));
        fixture.jdbc.update("UPDATE t_legal_hold SET subject_ref = 'ENGINEER:99'");

        assertThat(fixture.gate.evaluate(TENANT).reason()).isEqualTo(PrivacyHoldGate.REASON_PROJECTION_MISMATCH);

        fixture.jdbc.update("UPDATE t_legal_hold SET subject_ref = 'ENGINEER:10'");
        fixture.jdbc.update("UPDATE t_privacy_projection_state SET last_applied_seq = 0");
        assertThat(fixture.gate.evaluate(TENANT).reason()).isEqualTo(PrivacyHoldGate.REASON_PROJECTION_MISMATCH);
    }

    @Test
    void 別のjournalの行があればUNAVAILABLE() {
        fixture.store.ensureStateLocked(TENANT, "other-journal", PrivacyHoldTestFixture.T0);

        assertThat(fixture.gate.evaluate(TENANT).reason()).isEqualTo(PrivacyHoldGate.REASON_JOURNAL_MISMATCH);
    }

    @Test
    void 公開スイッチをOFFにしても掛けたholdは守る() {
        fixture.service.start(fixture.subjectRequest("ENGINEER:10", "ACCEPT_RISK", "ACCEPT_RISK"));
        // OFF にした後は入口（guard）が閉じるが、gate は guard・flag を見ない
        doThrow(new PrivacyAccessGuard.PrivacyEntryNotFoundException()).when(fixture.guard)
                .requireAllowed(PrivacyAccessGuard.Capability.RETENTION, PrivacyHoldService.MANAGE_ACTION);
        assertThatThrownBy(() -> fixture.service.start(fixture.subjectRequest("ENGINEER:11", "ACCEPT_RISK", "ACCEPT_RISK")))
                .isInstanceOf(PrivacyAccessGuard.PrivacyEntryNotFoundException.class);

        PrivacyHoldGate.Decision decision = fixture.gate.evaluate(TENANT);

        assertThat(decision.state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
        assertThat(decision.heldSubjects()).containsExactly("ENGINEER:10");
    }

    @Test
    void gateは機能スイッチと入口のguardに依存しない() {
        Set<Class<?>> forbidden = Set.of(FeatureSwitches.class, PrivacyFeatureProperties.class, PrivacyAccessGuard.class);
        for (Constructor<?> constructor : PrivacyHoldGate.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterTypes()).doesNotContainAnyElementsOf(forbidden);
        }
        for (Field field : PrivacyHoldGate.class.getDeclaredFields()) {
            assertThat(forbidden).doesNotContain(field.getType());
        }
        for (Field field : PrivacyHoldProjectionReconciler.class.getDeclaredFields()) {
            assertThat(forbidden).doesNotContain(field.getType());
        }
    }

    @Test
    void 手動経路用の判定はtransactionの中でだけ使え同じ結果を返す() {
        fixture.service.start(fixture.subjectRequest("ENGINEER:10", "ACCEPT_RISK", "ACCEPT_RISK"));

        assertThatThrownBy(() -> fixture.gate.evaluateLocked(TENANT)).isInstanceOf(IllegalStateException.class);

        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(fixture.dataSource));
        PrivacyHoldGate.Decision decision = transaction.execute(status -> fixture.gate.evaluateLocked(TENANT));

        assertThat(decision.state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
        assertThat(decision.heldSubjects()).containsExactly("ENGINEER:10");
    }

    @Test
    void 画面の表示は破壊的経路のUNAVAILABLEとして数えない() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        PrivacyHoldGate counted = fixture.gateWith(registry);
        fixture.store.ensureStateLocked(TENANT, PrivacyHoldTestFixture.JOURNAL_ID, PrivacyHoldTestFixture.T0);
        fixture.store.recordPending(TENANT, PrivacyHoldTestFixture.JOURNAL_ID, "hs-" + "c".repeat(32),
                PrivacyHoldTestFixture.T0);

        assertThat(counted.inspect(TENANT).reason()).isEqualTo(PrivacyHoldGate.REASON_PENDING);
        assertThat(registry.find(PrivacyHoldGate.UNAVAILABLE_METRIC).counter()).isNull();

        assertThat(counted.evaluate(TENANT).reason()).isEqualTo(PrivacyHoldGate.REASON_PENDING);
        assertThat(registry.get(PrivacyHoldGate.UNAVAILABLE_METRIC).tag("reason", PrivacyHoldGate.REASON_PENDING)
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void Journalとapplication_DBが同じinstanceならreconcilerの実行でUNAVAILABLEになり開始も拒否する() {
        fixture.service.start(fixture.subjectRequest("ENGINEER:10", "ACCEPT_RISK", "ACCEPT_RISK"));
        fixture.applicationServerUuid = () -> fixture.journal.serverUuid;

        assertThat(fixture.reconciler.reconcileAll())
                .containsEntry(PrivacyHoldProjectionReconciler.Outcome.JOURNAL_UNAVAILABLE, 1);
        assertThat(fixture.gate.evaluate(TENANT).reason()).isEqualTo(PrivacyHoldGate.REASON_JOURNAL_SAME_INSTANCE);
        assertThatThrownBy(() -> fixture.service.start(
                fixture.subjectRequest("ENGINEER:11", "ACCEPT_RISK", "ACCEPT_RISK")))
                .isInstanceOfSatisfying(com.ses.common.exception.BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(503));
        // 未使用の tenant は instance の判定にも触れず「hold なし」のまま
        assertThat(fixture.gate.evaluate("unused-tenant").state()).isEqualTo(PrivacyHoldGate.State.NO_HOLDS);

        fixture.applicationServerUuid = () -> "application-server-uuid";
        fixture.reconciler.reconcileAll();
        assertThat(fixture.gate.evaluate(TENANT).state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
    }

    @Test
    void reconcilerを動かさないprocessでも間隔を過ぎた最初の判定でinstanceを比べ直す() {
        fixture.service.start(fixture.subjectRequest("ENGINEER:10", "ACCEPT_RISK", "ACCEPT_RISK"));
        fixture.applicationServerUuid = () -> fixture.journal.serverUuid;

        // 前回の比較から1分以内は比べ直さない（毎回の照会を増やさない）
        assertThat(fixture.gate.evaluate(TENANT).state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
        fixture.clock.advance(PrivacyHoldJournal.INSTANCE_CHECK_INTERVAL);
        assertThat(fixture.gate.evaluate(TENANT).reason()).isEqualTo(PrivacyHoldGate.REASON_JOURNAL_SAME_INSTANCE);
    }

    @Test
    void server_uuidを比べられなければUNAVAILABLE() {
        fixture.service.start(fixture.subjectRequest("ENGINEER:10", "ACCEPT_RISK", "ACCEPT_RISK"));
        fixture.applicationServerUuid = () -> {
            throw new IllegalStateException("application DB unreachable");
        };

        fixture.reconciler.reconcileAll();

        assertThat(fixture.gate.evaluate(TENANT).reason()).isEqualTo(PrivacyHoldGate.REASON_JOURNAL_UNAVAILABLE);
    }

    /** service を通さずに Journal へ hold を入れる（ACK 後に projection が失敗した状態の再現）。 */
    private void appendOutsideService(String subjectRef) {
        PrivacyJournalPort.JournalPartition partition = new PrivacyJournalPort.JournalPartition(TENANT, subjectRef);
        PrivacyHoldPartitions.Ids ids = PrivacyHoldPartitions.newIds();
        fixture.journal.append(PrivacyJournalPort.JournalCommand.create(ids.commandId(), partition, 0,
                PrivacyJournalPort.CommandType.HOLD_STARTED, ids.holdId(), null, "c".repeat(64), null, 0, null));
    }
}
