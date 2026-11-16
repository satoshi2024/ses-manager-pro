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
        catalog("DOCUMENT:IMPORT", "FILE_OBJECT");
        catalog("RESUME_INGESTION_ORIGINAL", "FILE_OBJECT");
        catalog("AUDIT_LOG", "DB_COLUMN");
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

    @Test
    void 保存先を解決できないFILE_OBJECTのelementやcatalogに無いelementのholdは清掃を中止させる() {
        catalog("CLOUDSIGN_REMOTE_DOCUMENT", "FILE_OBJECT");

        assertThatThrownBy(() -> provider(PrivacyHoldTestGuards.verified(Set.of(), Set.of("CLOUDSIGN_REMOTE_DOCUMENT"),
                Map.of())).referencedFileNames())
                .isInstanceOf(PrivacyHoldFileReferenceProvider.PrivacyHoldCleanupAbortedException.class);
        assertThatThrownBy(() -> provider(PrivacyHoldTestGuards.verified(Set.of(), Set.of("NOT_IN_CATALOG"),
                Map.of())).referencedFileNames())
                .isInstanceOf(PrivacyHoldFileReferenceProvider.PrivacyHoldCleanupAbortedException.class);
    }

    private PrivacyHoldFileReferenceProvider provider(com.ses.privacy.production.retention.hold.PrivacyHoldGate.Decision decision) {
        PrivacyHoldTestGuards.Stub stub = PrivacyHoldTestGuards.stub().tenant(TENANT, decision);
        return new PrivacyHoldFileReferenceProvider(stub.gate, resolver);
    }

    private void catalog(String dataElementId, String storageKind) {
        jdbc.update("INSERT INTO m_pii_data_element (tenant_id, data_element_id, storage_kind, storage_ref, "
                + "subject_attribution) VALUES (?, ?, ?, 'test', 'NOT_ATTRIBUTABLE')", TENANT, dataElementId, storageKind);
    }
}
