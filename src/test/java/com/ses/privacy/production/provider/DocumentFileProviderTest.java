package com.ses.privacy.production.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.BASE_ID;
import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.OTHER_TENANT;
import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F2 文書 provider（t_document / t_document_version、t_document_link 経由）を H2 で確認する。
 * 人を指す link だけで本人へ導出し、論理削除済みの link・文書・版の保存名も解決する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentFileProviderTest {

    private static final long ENGINEER_ID = BASE_ID + 301;
    private static final long CANDIDATE_ID = BASE_ID + 302;

    @Autowired
    private DocumentFileProvider provider;
    @Autowired
    private JdbcTemplate jdbc;

    private PrivacyProviderH2Fixture fx;

    @BeforeEach
    void setUp() {
        fx = new PrivacyProviderH2Fixture(jdbc);
    }

    @Test
    void 要員へのlinkを持つ文書と版を返し保存名は論理削除済みも含める() {
        long contract = fx.document(BASE_ID + 311, TENANT, "CONTRACT", false);
        fx.version(BASE_ID + 312, TENANT, contract, 1, "doc-v1.pdf", false);
        fx.version(BASE_ID + 313, TENANT, contract, 2, "doc-v2.pdf", true);
        fx.link(BASE_ID + 314, TENANT, contract, "ENGINEER", ENGINEER_ID, false);
        long deletedDoc = fx.document(BASE_ID + 315, TENANT, "SKILL_SHEET", true);
        fx.version(BASE_ID + 316, TENANT, deletedDoc, 1, "sheet.pdf", false);
        fx.link(BASE_ID + 317, TENANT, deletedDoc, "ENGINEER", ENGINEER_ID, true);
        // 契約にだけ link する文書は人へ帰属しない
        long invoice = fx.document(BASE_ID + 318, TENANT, "INVOICE_OUT", false);
        fx.version(BASE_ID + 319, TENANT, invoice, 1, "invoice.pdf", false);
        fx.link(BASE_ID + 320, TENANT, invoice, "CONTRACT", ENGINEER_ID, false);

        List<PrivacyRecord> records = provider.findBySubject(TENANT, engineer(ENGINEER_ID));

        assertThat(PrivacyProviderH2Fixture.rowIds(records, "t_document")).containsExactlyInAnyOrder(contract, deletedDoc);
        assertThat(PrivacyProviderH2Fixture.rowIds(records, "t_document_version"))
                .containsExactlyInAnyOrder(BASE_ID + 312, BASE_ID + 313, BASE_ID + 316);
        assertThat(records).filteredOn(r -> r.rowId() == BASE_ID + 313).singleElement()
                .satisfies(r -> {
                    assertThat(r.logicallyDeleted()).isTrue();
                    assertThat(r.dataElementIds()).containsExactly("DOCUMENT:CONTRACT");
                });
        assertThat(records).filteredOn(r -> r.rowId() == BASE_ID + 316).singleElement()
                .satisfies(r -> assertThat(r.dataElementIds()).containsExactly("DOCUMENT:SKILL_SHEET"));
        assertThat(provider.resolveStoredNames(TENANT, engineer(ENGINEER_ID)))
                .containsExactlyInAnyOrder("doc-v1.pdf", "doc-v2.pdf", "sheet.pdf");
        assertThat(records).allSatisfy(r -> assertThat(r.fields())
                .doesNotContainKeys("counterparty_name_snapshot", "amount", "created_by", "human_user_id"));
        PrivacyProviderH2Fixture.assertOnlyAllowListed(records, DocumentFileProvider.DOCUMENT, DocumentFileProvider.VERSION);
        PrivacyProviderH2Fixture.assertTenant(records, TENANT);
    }

    @Test
    void 履歴取込へのlinkは取込の本人規則で要員と候補者の両方へ導出する() {
        long resume = fx.resume(BASE_ID + 331, TENANT, "resume-orig.pdf", ENGINEER_ID, CANDIDATE_ID, false);
        long document = fx.document(BASE_ID + 332, TENANT, "RESUME_INGESTION", false);
        fx.version(BASE_ID + 333, TENANT, document, 1, "resume-doc.pdf", false);
        fx.link(BASE_ID + 334, TENANT, document, "RESUME_INGESTION", resume, false);

        assertThat(provider.resolveStoredNames(TENANT, engineer(ENGINEER_ID))).containsExactly("resume-doc.pdf");
        assertThat(provider.resolveStoredNames(TENANT, PrivacySubjectRef.of(PrivacySubjectType.CANDIDATE, CANDIDATE_ID)))
                .containsExactly("resume-doc.pdf");
        assertThat(provider.findBySubject(TENANT, PrivacySubjectRef.of(PrivacySubjectType.SYS_USER, ENGINEER_ID))).isEmpty();
    }

    @Test
    void 他tenantのlink文書と版は返さない() {
        long otherDoc = fx.document(BASE_ID + 341, OTHER_TENANT, "CONTRACT", false);
        fx.version(BASE_ID + 342, OTHER_TENANT, otherDoc, 1, "other.pdf", false);
        fx.link(BASE_ID + 343, OTHER_TENANT, otherDoc, "ENGINEER", ENGINEER_ID, false);

        assertThat(provider.findBySubject(TENANT, engineer(ENGINEER_ID))).isEmpty();
        assertThat(provider.resolveStoredNames(OTHER_TENANT, engineer(ENGINEER_ID))).containsExactly("other.pdf");
    }

    @Test
    void 照会は書込みを行わない() {
        long document = fx.document(BASE_ID + 351, TENANT, "CONTRACT", false);
        fx.version(BASE_ID + 352, TENANT, document, 1, "w.pdf", false);
        fx.link(BASE_ID + 353, TENANT, document, "ENGINEER", ENGINEER_ID, false);
        List<?> documents = fx.snapshot("t_document", BASE_ID);
        List<?> versions = fx.snapshot("t_document_version", BASE_ID);
        List<?> links = fx.snapshot("t_document_link", BASE_ID);

        provider.findBySubject(TENANT, engineer(ENGINEER_ID));

        assertThat(fx.snapshot("t_document", BASE_ID)).isEqualTo(documents);
        assertThat(fx.snapshot("t_document_version", BASE_ID)).isEqualTo(versions);
        assertThat(fx.snapshot("t_document_link", BASE_ID)).isEqualTo(links);
    }

    private static PrivacySubjectRef engineer(long id) {
        return PrivacySubjectRef.of(PrivacySubjectType.ENGINEER, id);
    }
}
