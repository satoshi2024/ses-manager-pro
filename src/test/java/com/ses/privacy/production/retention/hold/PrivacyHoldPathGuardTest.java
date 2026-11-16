package com.ses.privacy.production.retention.hold;

import com.ses.common.exception.BusinessException;
import com.ses.privacy.production.provider.PrivacyRecord;
import com.ses.privacy.production.provider.PrivacySubjectRef;
import com.ses.privacy.production.provider.PrivacySubjectType;
import com.ses.privacy.production.provider.ProductionPrivacyProvider;
import com.ses.privacy.production.provider.ProviderDataElement;
import com.ses.privacy.production.provider.ProviderTable;
import com.ses.privacy.production.provider.SubjectLink;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NF07 A1H-2: 破壊的経路の入口（{@link PrivacyHoldPathGuard}）と D10 の {@link PrivacyHoldFileReferenceProvider} を、本物の gate
 * （専用の H2 と test 用 Journal、{@link PrivacyHoldTestFixture}）で確かめる。完了条件の「hold 機構を一度も使っていない tenant は
 * 増える SQL が gate の1回の照会だけで経路の挙動が変わらない（Journal の設定が無い prod でも同じ）」を statement の数で固定し、
 * SUBJECT hold・DATA_ELEMENT hold・分類 UNKNOWN・Journal 不達（Demo 4）・入口を閉じた後の判定（Demo 5）を確かめる。本人の導出は
 * provider の宣言（ここでは test 用の provider）を {@link HoldSubjectResolver} 経由で使う。
 */
class PrivacyHoldPathGuardTest {

    private static final String UNUSED_TENANT = "unused-tenant";
    private static final String HELD_ENGINEER = "ENGINEER:7";

    private FakeProvider documents;
    private FakeProvider ingestion;
    private PrivacyHoldTestFixture fixture;
    private SimpleMeterRegistry meters;
    private PrivacyHoldPathGuard guard;
    private PrivacyHoldFileReferenceProvider files;

    @BeforeEach
    void setUp() {
        documents = new FakeProvider("document-file", HoldSubjectResolver.DOCUMENT_TABLE,
                List.of(ProviderDataElement.exact("DOCUMENT:CONTRACT", SubjectAttribution.ATTRIBUTABLE, links())));
        ingestion = new FakeProvider("ingestion-text", HoldSubjectResolver.RESUME_INGESTION_TABLE, List.of(
                ProviderDataElement.exact("RESUME_INGESTION_ORIGINAL", SubjectAttribution.ATTRIBUTABLE, links()),
                ProviderDataElement.exact("RESUME_INGESTION_TEXT", SubjectAttribution.ATTRIBUTABLE, links())));
        fixture = new PrivacyHoldTestFixture("nf07-hold-path-" + UUID.randomUUID(), List.of(documents, ingestion));
        fixture.defaultCatalog();
        providerCatalog("DOCUMENT:CONTRACT", "document-file");
        providerCatalog("RESUME_INGESTION_ORIGINAL", "ingestion-text");
        providerCatalog("RESUME_INGESTION_TEXT", "ingestion-text");
        meters = new SimpleMeterRegistry();
        guard = new PrivacyHoldPathGuard(fixture.gate, fixture.resolver, metrics(meters));
        files = new PrivacyHoldFileReferenceProvider(fixture.gate, fixture.resolver);
        // held な要員の文書5（論理削除済みの版を含む）と履歴取込10
        documents.records(HELD_ENGINEER, record("document-file", HoldSubjectResolver.DOCUMENT_TABLE, 5L,
                "documents/2026/held-5.pdf"));
        ingestion.records(HELD_ENGINEER, record("ingestion-text", HoldSubjectResolver.RESUME_INGESTION_TABLE, 10L,
                "resume-held-10.pdf"));
    }

    @AfterEach
    void tearDown() {
        fixture.dropSchema();
    }

    // ---- 未使用 tenant: 増えるのは gate の照会1回だけ ----

    @Test
    void 未使用tenantのbatchはgateの照会1回だけで行の判定に照会を足さず本人導出も呼ばない() {
        fixture.dataSource.statements.set(0);

        PrivacyHoldPathGuard.Batch batch = guard.openBatch(PrivacyHoldPath.D4, UNUSED_TENANT);
        for (long id = 1; id <= 50; id++) {
            assertThat(batch.rowVerdict(List.of("RESUME_INGESTION_ORIGINAL", "RESUME_INGESTION_TEXT"),
                    HoldSubjectResolver.RESUME_INGESTION_TABLE, id)).isEqualTo(PrivacyHoldGate.Verdict.ALLOW);
        }
        assertThat(batch.elementVerdict("AUDIT_LOG")).isEqualTo(PrivacyHoldGate.Verdict.ALLOW);
        assertThat(batch.forPath(PrivacyHoldPath.D5).rowVerdict(List.of("BP_AVAILABILITY_INGESTION_TEXT"),
                HoldSubjectResolver.BP_INGESTION_TABLE, 1L)).isEqualTo(PrivacyHoldGate.Verdict.ALLOW);
        batch.close();

        assertThat(fixture.dataSource.statements.get()).isEqualTo(1);
        assertThat(batch.engaged()).isFalse();
        assertThat(documents.calls.get() + ingestion.calls.get()).isZero();
        assertThat(meters.find(PrivacyHoldPathGuard.SKIPPED_METRIC).counters()).isEmpty();
    }

    @Test
    void Journalの設定が無いprodでも未使用tenantは照会1回で全てALLOW() {
        fixture.startupResult = new PrivacyJournalStartupVerifier.Result(
                PrivacyJournalStartupVerifier.Availability.NOT_CONFIGURED, List.of());
        fixture.dataSource.statements.set(0);

        PrivacyHoldPathGuard.Batch batch = guard.openBatch(PrivacyHoldPath.D3, UNUSED_TENANT);
        assertThat(batch.rowVerdict(List.of("DOCUMENT:CONTRACT"), HoldSubjectResolver.DOCUMENT_TABLE, 5L))
                .isEqualTo(PrivacyHoldGate.Verdict.ALLOW);
        assertThat(fixture.dataSource.statements.get()).isEqualTo(1);

        fixture.dataSource.statements.set(0);
        assertThat(guard.openGlobalBatch(PrivacyHoldPath.D7).elementVerdict("INTEGRATION_HUB_AUDIT"))
                .isEqualTo(PrivacyHoldGate.Verdict.ALLOW);
        assertThat(fixture.dataSource.statements.get()).as("使った tenant の列挙1回").isEqualTo(1);

        fixture.dataSource.statements.set(0);
        assertThat(files.referencedFileNames()).isEmpty();
        assertThat(fixture.dataSource.statements.get()).isEqualTo(1);
    }

    @Test
    void 手動経路は未使用tenantなら同じtransactionの照会1回で通しtransactionの外からは呼べない() {
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(fixture.dataSource));
        fixture.dataSource.statements.set(0);

        transaction.executeWithoutResult(status ->
                guard.requireDocumentDisposable(PrivacyHoldPath.D1, UNUSED_TENANT, 5L, "CONTRACT"));

        assertThat(fixture.dataSource.statements.get()).isEqualTo(1);
        assertThat(documents.calls.get()).isZero();
        assertThatThrownBy(() -> guard.requireDocumentDisposable(PrivacyHoldPath.D1, UNUSED_TENANT, 5L, "CONTRACT"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- hold を使った tenant ----

    @Test
    void SUBJECT_holdは本人に帰属する行だけを止め帰属しない行と帰属できないelementは止めない() {
        startSubjectHold();
        PrivacyHoldPathGuard.Batch batch = guard.openBatch(PrivacyHoldPath.D4, PrivacyHoldTestFixture.TENANT);

        assertThat(batch.rowVerdict(List.of("RESUME_INGESTION_ORIGINAL", "RESUME_INGESTION_TEXT"),
                HoldSubjectResolver.RESUME_INGESTION_TABLE, 10L)).isEqualTo(PrivacyHoldGate.Verdict.HELD);
        assertThat(batch.rowVerdict(List.of("RESUME_INGESTION_TEXT"), HoldSubjectResolver.RESUME_INGESTION_TABLE, 11L))
                .isEqualTo(PrivacyHoldGate.Verdict.ALLOW);
        assertThat(batch.elementVerdict("INTEGRATION_HUB_AUDIT")).as("NOT_ATTRIBUTABLE は SUBJECT hold で止めない")
                .isEqualTo(PrivacyHoldGate.Verdict.ALLOW);
        assertThat(batch.elementVerdict("AUDIT_LOG")).as("UNKNOWN は fail-closed")
                .isEqualTo(PrivacyHoldGate.Verdict.UNCLASSIFIED);
        assertThat(ingestion.calls.get()).as("本人の導出は batch ごとに1回").isEqualTo(1);
        batch.skip(PrivacyHoldGate.Verdict.HELD);
        batch.close();
        assertThat(meters.find(PrivacyHoldPathGuard.SKIPPED_METRIC).tag("path", "D4").tag("verdict", "HELD")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void 手動経路はSUBJECT_holdの本人の文書を409で拒否し他の文書は通す() {
        startSubjectHold();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(fixture.dataSource));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> guard.requireDocumentDisposable(
                PrivacyHoldPath.D1, PrivacyHoldTestFixture.TENANT, 5L, "CONTRACT")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getMessageKey()).isEqualTo(PrivacyHoldPathGuard.HOLD_ACTIVE);
                });
        transaction.executeWithoutResult(status -> guard.requireDocumentDisposable(
                PrivacyHoldPath.D2, PrivacyHoldTestFixture.TENANT, 6L, "CONTRACT"));
    }

    @Test
    void DATA_ELEMENT_holdはそのelementの全行を本人に関係なく止める() {
        fixture.service.start(fixture.elementRequest("DOCUMENT:IMPORT"));
        PrivacyHoldPathGuard.Batch batch = guard.openBatch(PrivacyHoldPath.D3, PrivacyHoldTestFixture.TENANT);

        assertThat(batch.rowVerdict(List.of("DOCUMENT:IMPORT"), HoldSubjectResolver.DOCUMENT_TABLE, 77L))
                .isEqualTo(PrivacyHoldGate.Verdict.HELD);
        assertThat(batch.rowVerdict(List.of("DOCUMENT:CONTRACT"), HoldSubjectResolver.DOCUMENT_TABLE, 5L))
                .isEqualTo(PrivacyHoldGate.Verdict.ALLOW);
        assertThat(documents.calls.get()).as("SUBJECT hold が無ければ本人を導出しない").isZero();
    }

    @Test
    void Journalに届かなければholdを使ったtenantの経路は全て止まり未使用のtenantは従来どおり() {
        startSubjectHold();
        fixture.startupResult = new PrivacyJournalStartupVerifier.Result(
                PrivacyJournalStartupVerifier.Availability.UNAVAILABLE, List.of());

        PrivacyHoldPathGuard.Batch held = guard.openBatch(PrivacyHoldPath.D4, PrivacyHoldTestFixture.TENANT);
        assertThat(held.rowVerdict(List.of("RESUME_INGESTION_TEXT"), HoldSubjectResolver.RESUME_INGESTION_TABLE, 11L))
                .isEqualTo(PrivacyHoldGate.Verdict.UNAVAILABLE);
        assertThat(guard.openGlobalBatch(PrivacyHoldPath.D9).elementVerdict("EXTERNAL_API_READ_SNAPSHOT"))
                .isEqualTo(PrivacyHoldGate.Verdict.UNAVAILABLE);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(fixture.dataSource));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> guard.requireDocumentDisposable(
                PrivacyHoldPath.D1, PrivacyHoldTestFixture.TENANT, 6L, "CONTRACT")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409));
        assertThatThrownBy(files::referencedFileNames)
                .isInstanceOf(PrivacyHoldFileReferenceProvider.PrivacyHoldCleanupAbortedException.class);

        fixture.dataSource.statements.set(0);
        assertThat(guard.openBatch(PrivacyHoldPath.D4, UNUSED_TENANT).rowVerdict(List.of("RESUME_INGESTION_TEXT"),
                HoldSubjectResolver.RESUME_INGESTION_TABLE, 11L)).isEqualTo(PrivacyHoldGate.Verdict.ALLOW);
        assertThat(fixture.dataSource.statements.get()).isEqualTo(1);
    }

    @Test
    void 入口を閉じても掛けたholdは経路で守られる() {
        startSubjectHold();
        // 機能スイッチ OFF・権限なしで入口（PrivacyAccessGuard）が閉じた状態。gate と経路は入口に依存しない
        doThrow(BusinessException.of(404, "error.notFound")).when(fixture.guard).requireAllowed(any(), any());

        assertThat(guard.openBatch(PrivacyHoldPath.D4, PrivacyHoldTestFixture.TENANT).rowVerdict(
                List.of("RESUME_INGESTION_TEXT"), HoldSubjectResolver.RESUME_INGESTION_TABLE, 10L))
                .isEqualTo(PrivacyHoldGate.Verdict.HELD);
        assertThat(files.referencedFileNames()).contains("resume-held-10.pdf");
    }

    @Test
    void D10はheldな本人の保存名を論理削除済みの行も含め末尾の名前と合わせて返す() {
        startSubjectHold();

        assertThat(files.referencedFileNames()).containsExactlyInAnyOrder(
                "documents/2026/held-5.pdf", "held-5.pdf", "resume-held-10.pdf");
    }

    @Test
    void 本人の導出に失敗した回はその回の行を全て止める() {
        startSubjectHold();
        ingestion.failing = true;
        PrivacyHoldPathGuard.Batch batch = guard.openBatch(PrivacyHoldPath.D4, PrivacyHoldTestFixture.TENANT);

        assertThat(batch.rowVerdict(List.of("RESUME_INGESTION_TEXT"), HoldSubjectResolver.RESUME_INGESTION_TABLE, 11L))
                .isEqualTo(PrivacyHoldGate.Verdict.UNAVAILABLE);
        assertThat(batch.subjectHeldRowIds("RESUME_INGESTION_TEXT", HoldSubjectResolver.RESUME_INGESTION_TABLE)).isNull();
    }

    private void startSubjectHold() {
        fixture.service.start(fixture.subjectRequest(HELD_ENGINEER, "ACCEPT_RISK", "ACCEPT_RISK"));
        assertThat(fixture.gate.evaluate(PrivacyHoldTestFixture.TENANT).heldSubjects()).containsExactly(HELD_ENGINEER);
    }

    private void providerCatalog(String dataElementId, String providerId) {
        fixture.jdbc.update("INSERT INTO m_pii_data_element (tenant_id, data_element_id, provider_id, subject_attribution) "
                + "VALUES (?, ?, ?, 'ATTRIBUTABLE')", PrivacyHoldTestFixture.TENANT, dataElementId, providerId);
    }

    private static List<SubjectLink> links() {
        return List.of(new SubjectLink(PrivacySubjectType.ENGINEER, "test"));
    }

    private static PrivacyRecord record(String providerId, String table, long rowId, String storedName) {
        return new PrivacyRecord(providerId, table, rowId, List.of(), true, Map.of(), Set.of(storedName));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> metrics(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    /** subject ごとの行を返す test 用の provider（SELECT はしない）。 */
    static final class FakeProvider implements ProductionPrivacyProvider {
        private final String providerId;
        private final ProviderTable table;
        private final List<ProviderDataElement> elements;
        private final Map<String, List<PrivacyRecord>> bySubject = new HashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failing;

        FakeProvider(String providerId, String table, List<ProviderDataElement> elements) {
            this.providerId = providerId;
            this.table = new ProviderTable(table, List.of("id", "stored_name"), List.of("stored_name"));
            this.elements = elements;
        }

        void records(String subjectRef, PrivacyRecord... records) {
            bySubject.put(subjectRef, List.of(records));
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public List<ProviderDataElement> dataElements() {
            return elements;
        }

        @Override
        public List<ProviderTable> tables() {
            return List.of(table);
        }

        @Override
        public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
            calls.incrementAndGet();
            if (failing) {
                throw new IllegalStateException("test: provider failure");
            }
            return bySubject.getOrDefault(subject.ref(), List.of());
        }
    }
}
