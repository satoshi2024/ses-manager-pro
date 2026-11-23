package com.ses.privacy.production.provider;

import com.ses.privacy.production.retention.hold.HoldSubjectResolver;
import com.ses.privacy.production.retention.hold.PrivacyHoldFileReferenceProvider;
import com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards;
import com.ses.service.FileReferenceProvider;
import com.ses.service.impl.DocumentArchiveFileReferenceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.BASE_ID;
import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.OTHER_TENANT;
import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NF07 A1H-2 D10: {@link PrivacyHoldFileReferenceProvider} が、有効な hold の対象の実体の保存名を、既存の provider が参照に
 * 数えない行（論理削除済みの版・却下した履歴取込）も含めて「参照あり」として返すことを、実 provider と H2 の testdb で確かめる
 * （production-bridge-phased-plan.md §3.4.1 D10・D11）。F2 review P3-9 の対照として、held な本人の文書について
 * {@link DocumentArchiveFileReferenceProvider} の claim を全て含むことを固定する。gate の判定は差し替え（{@link PrivacyHoldTestGuards}）、
 * 保存名の解決は本物の {@link HoldSubjectResolver}。provider は Spring の {@code FileReferenceProvider} として登録される。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PrivacyHoldFileReferenceProviderTest {

    private static final long ENGINEER_ID = BASE_ID + 701;
    private static final long OTHER_ENGINEER_ID = BASE_ID + 702;

    @Autowired
    private HoldSubjectResolver resolver;
    @Autowired
    private DocumentArchiveFileReferenceProvider archiveFileReferenceProvider;
    @Autowired
    private List<FileReferenceProvider> fileReferenceProviders;
    @Autowired
    private JdbcTemplate jdbc;

    private PrivacyProviderH2Fixture fx;

    @BeforeEach
    void setUp() {
        fx = new PrivacyProviderH2Fixture(jdbc);
        fx.engineer(ENGINEER_ID, TENANT, "保全 太郎", null, false);
        fx.engineer(OTHER_ENGINEER_ID, TENANT, "第三者 花子", null, false);
    }

    @Test
    void FileReferenceProviderとして登録され孤児清掃とlegacy移行の両方が受け取る() {
        assertThat(fileReferenceProviders).anyMatch(PrivacyHoldFileReferenceProvider.class::isInstance);
    }

    @Test
    void heldな要員の文書の版はDocumentArchiveFileReferenceProviderの参照を全て含み論理削除済みの版も守る() {
        long held = fx.document(BASE_ID + 711, TENANT, "CONTRACT", false);
        fx.link(BASE_ID + 711, TENANT, held, "ENGINEER", ENGINEER_ID, false);
        fx.version(BASE_ID + 711, TENANT, held, 1, "nf07/a1h2/held-v1.pdf", false);
        fx.version(BASE_ID + 712, TENANT, held, 2, "nf07/a1h2/held-v2-deleted.pdf", true);
        long other = fx.document(BASE_ID + 713, TENANT, "CONTRACT", false);
        fx.link(BASE_ID + 713, TENANT, other, "ENGINEER", OTHER_ENGINEER_ID, false);
        fx.version(BASE_ID + 713, TENANT, other, 1, "nf07/a1h2/other-v1.pdf", false);

        Set<String> heldClaims = provider(PrivacyHoldTestGuards.verified(Set.of("ENGINEER:" + ENGINEER_ID), Set.of(),
                Map.of())).referencedFileNames();
        Set<String> archiveClaims = archiveFileReferenceProvider.referencedFileNames().stream()
                .filter(name -> name.contains("held-v")).collect(Collectors.toSet());

        assertThat(archiveClaims).as("既存 provider は論理削除済みの版を参照に数えない")
                .containsExactlyInAnyOrder("nf07/a1h2/held-v1.pdf", "held-v1.pdf");
        assertThat(heldClaims).containsAll(archiveClaims)
                .contains("nf07/a1h2/held-v2-deleted.pdf", "held-v2-deleted.pdf")
                .doesNotContain("nf07/a1h2/other-v1.pdf", "other-v1.pdf");
    }

    @Test
    void 却下した履歴取込の原本は既存providerが参照に数えなくてもheldな本人なら守る() {
        long rejected = fx.resume(BASE_ID + 721, TENANT, "a1h2-rejected-721.pdf", ENGINEER_ID, null, false);
        jdbc.update("UPDATE t_resume_ingestion SET status = '却下' WHERE id = ?", rejected);

        assertThat(provider(PrivacyHoldTestGuards.verified(Set.of("ENGINEER:" + ENGINEER_ID), Set.of(), Map.of()))
                .referencedFileNames()).contains("a1h2-rejected-721.pdf");
    }

    @Test
    void DATA_ELEMENT_holdは文書種別の全ての版と取込原本を論理削除済みも含めて守る() {
        catalog("DOCUMENT:IMPORT", "FILE_OBJECT", "t_document_version.storage_key");
        catalog("RESUME_INGESTION_ORIGINAL", "FILE_OBJECT", "t_resume_ingestion.stored_file_name");
        catalog("AUDIT_LOG", "DB_COLUMN", "t_audit_log.(username,uri)");
        long imported = fx.document(BASE_ID + 731, TENANT, "IMPORT", true);
        fx.version(BASE_ID + 731, TENANT, imported, 1, "nf07/a1h2/import-731.csv", true);
        long contract = fx.document(BASE_ID + 732, TENANT, "CONTRACT", false);
        fx.version(BASE_ID + 732, TENANT, contract, 1, "nf07/a1h2/contract-732.pdf", false);
        fx.resume(BASE_ID + 733, TENANT, "a1h2-unconverted-733.pdf", null, null, true);

        Set<String> claims = provider(PrivacyHoldTestGuards.verified(Set.of(),
                Set.of("DOCUMENT:IMPORT", "RESUME_INGESTION_ORIGINAL", "AUDIT_LOG"), Map.of())).referencedFileNames();

        assertThat(claims).contains("nf07/a1h2/import-731.csv", "import-731.csv", "a1h2-unconverted-733.pdf")
                .doesNotContain("nf07/a1h2/contract-732.pdf");
    }

    /**
     * A1H-2 review P2-1: 実体が外部 service 側にある FILE_OBJECT（storage_ref が application の表の列でない）の
     * DATA_ELEMENT hold は、upload root に守る実体が無いため清掃を止めず、WARN と metric で外部側が D10 の範囲外であることを示す。
     */
    @Test
    void 外部にあるFILE_OBJECTのDATA_ELEMENT_holdは清掃を止めずmetricに数える() {
        catalog("CLOUDSIGN_REMOTE_DOCUMENT", "FILE_OBJECT", "cloudsign.remote-document");
        catalog("FREEE_REMOTE_RECORD", "FILE_OBJECT", "freee.remote-record");
        long imported = fx.document(BASE_ID + 741, TENANT, "IMPORT", false);
        fx.version(BASE_ID + 741, TENANT, imported, 1, "nf07/a1h2/import-741.csv", false);
        catalog("DOCUMENT:IMPORT", "FILE_OBJECT", "t_document_version.storage_key");
        PrivacyHoldTestGuards.Stub stub = PrivacyHoldTestGuards.stub().tenant(TENANT, PrivacyHoldTestGuards.verified(
                Set.of(), Set.of("CLOUDSIGN_REMOTE_DOCUMENT", "FREEE_REMOTE_RECORD", "DOCUMENT:IMPORT"), Map.of()));

        Set<String> claims = provider(stub).referencedFileNames();

        assertThat(claims).as("同じ回の他の element の保存名は守る").contains("nf07/a1h2/import-741.csv");
        assertThat(stub.counter(PrivacyHoldFileReferenceProvider.UNPROTECTABLE_METRIC, "element",
                "CLOUDSIGN_REMOTE_DOCUMENT")).isEqualTo(1.0);
        assertThat(stub.counter(PrivacyHoldFileReferenceProvider.UNPROTECTABLE_METRIC, "element",
                "FREEE_REMOTE_RECORD")).isEqualTo(1.0);
        assertThat(stub.counter(PrivacyHoldFileReferenceProvider.ABORT_METRIC, "stage", "resolve")).isZero();
    }

    @Test
    void catalogに無いelementや表の列なのに解決できないelementのholdは清掃を中止させmetricに数える() {
        catalog("LOCAL_UNRESOLVED_FILE", "FILE_OBJECT", "t_unknown_table.stored_name");
        PrivacyHoldTestGuards.Stub missing = PrivacyHoldTestGuards.stub().tenant(TENANT,
                PrivacyHoldTestGuards.verified(Set.of(), Set.of("NOT_IN_CATALOG"), Map.of()));
        PrivacyHoldTestGuards.Stub unresolved = PrivacyHoldTestGuards.stub().tenant(TENANT,
                PrivacyHoldTestGuards.verified(Set.of(), Set.of("LOCAL_UNRESOLVED_FILE"), Map.of()));

        assertThatThrownBy(() -> provider(missing).referencedFileNames())
                .isInstanceOf(PrivacyHoldFileReferenceProvider.PrivacyHoldCleanupAbortedException.class);
        assertThatThrownBy(() -> provider(unresolved).referencedFileNames())
                .isInstanceOf(PrivacyHoldFileReferenceProvider.PrivacyHoldCleanupAbortedException.class);
        assertThat(missing.counter(PrivacyHoldFileReferenceProvider.ABORT_METRIC, "stage", "resolve")).isEqualTo(1.0);
        assertThat(unresolved.counter(PrivacyHoldFileReferenceProvider.ABORT_METRIC, "stage", "resolve")).isEqualTo(1.0);

        PrivacyHoldTestGuards.Stub unavailable = PrivacyHoldTestGuards.stub().tenant(TENANT,
                PrivacyHoldTestGuards.unavailable());
        assertThatThrownBy(() -> provider(unavailable).referencedFileNames())
                .isInstanceOf(PrivacyHoldFileReferenceProvider.PrivacyHoldCleanupAbortedException.class);
        assertThat(unavailable.counter(PrivacyHoldFileReferenceProvider.ABORT_METRIC, "stage", "gate")).isEqualTo(1.0);
    }

    /**
     * review rb-fe-a1 P2-1: 機能の開放の署名の文書（{@code FEATURE_SIGN_OFF_DOCUMENT}、{@code t_feature_sign_off.stored_name}）の
     * DATA_ELEMENT hold は、状態に依らず保存名の残る全ての署名の文書を守り、清掃を中止しない（他 tenant の行は含めない）。
     */
    @Test
    void 署名の文書のDATA_ELEMENT_holdは保存名の残る全ての署名の文書を守り清掃を中止しない() {
        catalog("FEATURE_SIGN_OFF_DOCUMENT", "FILE_OBJECT", "t_feature_sign_off.stored_name");
        signOff(TENANT, "REVOKED", "fe-hold-revoked-751.pdf");
        signOff(TENANT, "CONFIRMED", "fe-hold-confirmed-752.pdf");
        signOff(OTHER_TENANT, "REVOKED", "fe-hold-other-753.pdf");
        PrivacyHoldTestGuards.Stub stub = PrivacyHoldTestGuards.stub().tenant(TENANT, PrivacyHoldTestGuards.verified(
                Set.of(), Set.of("FEATURE_SIGN_OFF_DOCUMENT"), Map.of()));

        Set<String> claims = provider(stub).referencedFileNames();

        assertThat(claims).contains("fe-hold-revoked-751.pdf", "fe-hold-confirmed-752.pdf")
                .doesNotContain("fe-hold-other-753.pdf");
        assertThat(stub.counter(PrivacyHoldFileReferenceProvider.ABORT_METRIC, "stage", "resolve")).isZero();
    }

    /**
     * review rb-fe-a1 P2-1: SUBJECT hold だけの tenant では、帰属できない署名の文書（NOT_ATTRIBUTABLE）があっても清掃を中止せず、
     * held な本人の保存名は守る（署名の文書は SUBJECT hold では守られない。残余は D-05 の受理事項。RB §4.5）。
     */
    @Test
    void SUBJECT_holdは署名の文書があっても清掃を中止せずheldな本人の保存名を守る() {
        catalog("FEATURE_SIGN_OFF_DOCUMENT", "FILE_OBJECT", "t_feature_sign_off.stored_name");
        signOff(TENANT, "REVOKED", "fe-hold-subject-761.pdf");
        long held = fx.document(BASE_ID + 762, TENANT, "CONTRACT", false);
        fx.link(BASE_ID + 762, TENANT, held, "ENGINEER", ENGINEER_ID, false);
        fx.version(BASE_ID + 762, TENANT, held, 1, "nf07/a1h2/held-762.pdf", false);
        PrivacyHoldTestGuards.Stub stub = PrivacyHoldTestGuards.stub().tenant(TENANT, PrivacyHoldTestGuards.verified(
                Set.of("ENGINEER:" + ENGINEER_ID), Set.of(), Map.of()));

        Set<String> claims = provider(stub).referencedFileNames();

        assertThat(claims).contains("nf07/a1h2/held-762.pdf").doesNotContain("fe-hold-subject-761.pdf");
        assertThat(stub.counter(PrivacyHoldFileReferenceProvider.ABORT_METRIC, "stage", "resolve")).isZero();
    }

    private void signOff(String tenantId, String status, String storedName) {
        jdbc.update("INSERT INTO t_feature_sign_off (tenant_id, kind_code, reference, signer_role, signed_on, expires_on, "
                        + "kind_class, content_sha256, size_bytes, stored_name, status, registered_by, registered_at, "
                        + "confirmed_by, confirmed_at) VALUES (?, 'NF04_SRE', 'SRE-2026-11', 'SRE', DATE '2026-11-01', "
                        + "DATE '2027-11-01', 'EVIDENCE', ?, 10, ?, ?, 990001, TIMESTAMP '2026-11-01 10:00:00', 990002, "
                        + "TIMESTAMP '2026-11-01 11:00:00')", tenantId, "c".repeat(64), storedName, status);
    }

    private PrivacyHoldFileReferenceProvider provider(com.ses.privacy.production.retention.hold.PrivacyHoldGate.Decision decision) {
        return provider(PrivacyHoldTestGuards.stub().tenant(TENANT, decision));
    }

    /** stub の gate と metric、本物の resolver の provider。 */
    @SuppressWarnings("unchecked")
    private PrivacyHoldFileReferenceProvider provider(PrivacyHoldTestGuards.Stub stub) {
        org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(meters.getIfAvailable()).thenReturn(stub.meters);
        return new PrivacyHoldFileReferenceProvider(stub.gate, resolver, meters);
    }

    private void catalog(String dataElementId, String storageKind, String storageRef) {
        jdbc.update("INSERT INTO m_pii_data_element (tenant_id, data_element_id, storage_kind, storage_ref, "
                + "subject_attribution) VALUES (?, ?, ?, ?, 'NOT_ATTRIBUTABLE')", TENANT, dataElementId, storageKind,
                storageRef);
    }
}
