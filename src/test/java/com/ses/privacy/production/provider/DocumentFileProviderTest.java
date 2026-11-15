package com.ses.privacy.production.provider;

import com.ses.service.impl.DocumentArchiveFileReferenceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.BASE_ID;
import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.OTHER_TENANT;
import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F2 文書 provider（t_document / t_document_version、t_document_link 経由）を H2 で確認する。
 * 人を指す link（直接・間接）と 1on1 相談メモの逆参照で本人へ導出し、論理削除済みの link・文書・版の保存名も解決する。
 * 導出できない文書種別（残余）は本人へ帰属しないことを固定する（plan §3.4.1 D1）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentFileProviderTest {

    private static final long ENGINEER_ID = BASE_ID + 301;
    private static final long CANDIDATE_ID = BASE_ID + 302;
    private static final long OTHER_ENGINEER_ID = BASE_ID + 303;

    @Autowired
    private DocumentFileProvider provider;
    @Autowired
    private DocumentArchiveFileReferenceProvider archiveFileReferenceProvider;
    @Autowired
    private JdbcTemplate jdbc;

    private PrivacyProviderH2Fixture fx;

    @BeforeEach
    void setUp() {
        fx = new PrivacyProviderH2Fixture(jdbc);
    }

    @Test
    void 要員へのlinkを持つ文書と版を返し保存名は論理削除済みも含める() {
        long contract = fx.document(BASE_ID + 311, TENANT, "SKILL_SHEET", false);
        fx.version(BASE_ID + 312, TENANT, contract, 1, "doc-v1.pdf", false);
        fx.version(BASE_ID + 313, TENANT, contract, 2, "doc-v2.pdf", true);
        fx.link(BASE_ID + 314, TENANT, contract, "ENGINEER", ENGINEER_ID, false);
        long deletedDoc = fx.document(BASE_ID + 315, TENANT, "RECEIPT", true);
        fx.version(BASE_ID + 316, TENANT, deletedDoc, 1, "sheet.pdf", false);
        fx.link(BASE_ID + 317, TENANT, deletedDoc, "ENGINEER", ENGINEER_ID, true);

        List<PrivacyRecord> records = provider.findBySubject(TENANT, engineer(ENGINEER_ID));

        assertThat(PrivacyProviderH2Fixture.rowIds(records, "t_document")).containsExactlyInAnyOrder(contract, deletedDoc);
        assertThat(PrivacyProviderH2Fixture.rowIds(records, "t_document_version"))
                .containsExactlyInAnyOrder(BASE_ID + 312, BASE_ID + 313, BASE_ID + 316);
        assertThat(records).filteredOn(r -> r.rowId() == BASE_ID + 313).singleElement()
                .satisfies(r -> {
                    assertThat(r.logicallyDeleted()).isTrue();
                    assertThat(r.dataElementIds()).containsExactly("DOCUMENT:SKILL_SHEET");
                });
        assertThat(records).filteredOn(r -> r.rowId() == BASE_ID + 316).singleElement()
                .satisfies(r -> assertThat(r.dataElementIds()).containsExactly("DOCUMENT:RECEIPT"));
        assertThat(provider.resolveStoredNames(TENANT, engineer(ENGINEER_ID)))
                .containsExactlyInAnyOrder("doc-v1.pdf", "doc-v2.pdf", "sheet.pdf");
        assertThat(records).allSatisfy(r -> assertThat(r.fields())
                .doesNotContainKeys("counterparty_name_snapshot", "amount", "created_by", "human_user_id"));
        PrivacyProviderH2Fixture.assertOnlyAllowListed(records, DocumentFileProvider.DOCUMENT, DocumentFileProvider.VERSION);
        PrivacyProviderH2Fixture.assertTenant(records, TENANT);
        // 論理削除されていない版の保存名は、孤児清理を止める既存の FileReferenceProvider も参照ありとする（P3-9）
        assertThat(archiveFileReferenceProvider.referencedFileNames()).contains("doc-v1.pdf", "sheet.pdf");
    }

    @Test
    void 契約と実績とBP支払と見積と資格と1on1相談メモから間接に要員へ導出する() {
        fx.engineer(ENGINEER_ID, TENANT, "要員E", null, false);
        fx.engineer(OTHER_ENGINEER_ID, TENANT, "要員O", null, false);
        long counterpart = fx.sysUser(BASE_ID + 399, TENANT, "nf07-f2-counterpart", "x");
        long contract = fx.customerProjectContract(BASE_ID + 321, ENGINEER_ID);
        long otherContract = fx.customerProjectContract(BASE_ID + 322, OTHER_ENGINEER_ID);
        long workRecord = fx.workRecord(BASE_ID + 323, contract);
        long otherWorkRecord = fx.workRecord(BASE_ID + 324, otherContract);
        long bpPayment = fx.bpPayment(BASE_ID + 325, workRecord);
        long quotation = fx.quotation(BASE_ID + 326, contract, ENGINEER_ID);
        long quotationWithoutEngineer = fx.quotation(BASE_ID + 327, contract, null);
        long certification = fx.engineerCertification(BASE_ID + 328, TENANT, ENGINEER_ID);

        long employment = linkedDocument(331, "EMPLOYMENT_CONDITIONS_STATEMENT", "CONTRACT", contract);
        long workReport = linkedDocument(333, "WORK_REPORT", "WORK_RECORD", workRecord);
        long bpSubmission = linkedDocument(335, "BP_SUBMISSION", "BP_PAYMENT", bpPayment);
        long quote = linkedDocument(337, "QUOTATION", "QUOTATION", quotation);
        long evidence = linkedDocument(339, "CERTIFICATION_EVIDENCE", "CERTIFICATION_RECORD", certification);
        long privateNote = fx.document(BASE_ID + 341, TENANT, "PRIVATE_NOTE", false);
        fx.version(BASE_ID + 342, TENANT, privateNote, 1, "note-341.bin", false);
        fx.privateNote(BASE_ID + 343, ENGINEER_ID, counterpart, privateNote);
        long otherWorkReport = linkedDocument(345, "WORK_REPORT", "WORK_RECORD", otherWorkRecord);
        long unassignedQuote = linkedDocument(347, "QUOTATION", "QUOTATION", quotationWithoutEngineer);

        List<PrivacyRecord> records = provider.findBySubject(TENANT, engineer(ENGINEER_ID));

        assertThat(PrivacyProviderH2Fixture.rowIds(records, "t_document"))
                .containsExactlyInAnyOrder(employment, workReport, bpSubmission, quote, evidence, privateNote);
        assertThat(provider.resolveStoredNames(TENANT, engineer(ENGINEER_ID))).containsExactlyInAnyOrder(
                "doc-331.pdf", "doc-333.pdf", "doc-335.pdf", "doc-337.pdf", "doc-339.pdf", "note-341.bin");
        assertThat(records).filteredOn(r -> r.rowId() == privateNote && r.table().equals("t_document")).singleElement()
                .satisfies(r -> assertThat(r.dataElementIds()).containsExactly("DOCUMENT:PRIVATE_NOTE"));
        assertThat(PrivacyProviderH2Fixture.rowIds(provider.findBySubject(TENANT, engineer(OTHER_ENGINEER_ID)),
                "t_document")).containsExactly(otherWorkReport);
        assertThat(PrivacyProviderH2Fixture.rowIds(records, "t_document")).doesNotContain(unassignedQuote);
        assertThat(provider.findBySubject(TENANT, PrivacySubjectRef.of(PrivacySubjectType.CANDIDATE, ENGINEER_ID)))
                .isEmpty();
    }

    @Test
    void 導出できない文書種別は要員と同じIDのtargetにlinkしても本人へ帰属しない() {
        // 残余の種別が通常付ける link（請求・注文・サービス依頼・取込job）と、link なし
        long invoice = linkedDocument(351, "INVOICE_OUT", "INVOICE", ENGINEER_ID);
        long order = linkedDocument(353, "ORDER_RECEIVED", "SALES_ORDER", ENGINEER_ID);
        long service = linkedDocument(355, "SERVICE_REQUEST_ATTACHMENT", "SERVICE_REQUEST", ENGINEER_ID);
        long imported = linkedDocument(357, "IMPORT", "IMPORT_JOB", ENGINEER_ID);
        long report = fx.document(BASE_ID + 359, TENANT, "MANAGEMENT_REPORT", false);

        Set<Long> attributed = PrivacyProviderH2Fixture.rowIds(provider.findBySubject(TENANT, engineer(ENGINEER_ID)),
                "t_document");

        assertThat(attributed).doesNotContain(invoice, order, service, imported, report);
        assertThat(DocumentFileProvider.UNATTRIBUTABLE_DOCUMENT_TYPES).containsKeys("INVOICE_OUT", "ORDER_RECEIVED",
                "SERVICE_REQUEST_ATTACHMENT", "IMPORT", "MANAGEMENT_REPORT");
    }

    @Test
    void m_document_typeの全種別は導出経路か残余のどちらか一方に宣言されている() {
        Set<String> codes = new HashSet<>(jdbc.queryForList("SELECT code FROM m_document_type", String.class));
        codes.add("RESUME_INGESTION");
        codes.add("IMPORT");
        Set<String> derivable = DocumentFileProvider.DERIVABLE_DOCUMENT_TYPES.keySet();
        Set<String> residual = DocumentFileProvider.UNATTRIBUTABLE_DOCUMENT_TYPES.keySet();

        assertThat(derivable).doesNotContainAnyElementsOf(residual);
        for (String code : codes) {
            assertThat(derivable.contains(code) || residual.contains(code))
                    .as("文書種別 %s の本人導出（経路か残余）を DocumentFileProvider に宣言すること", code).isTrue();
        }
    }

    @Test
    void 履歴取込へのlinkは取込の本人規則で要員と候補者の両方へ導出する() {
        long resume = fx.resume(BASE_ID + 361, TENANT, "resume-orig.pdf", ENGINEER_ID, CANDIDATE_ID, false);
        long document = fx.document(BASE_ID + 362, TENANT, "RESUME_INGESTION", false);
        fx.version(BASE_ID + 363, TENANT, document, 1, "resume-doc.pdf", false);
        fx.link(BASE_ID + 364, TENANT, document, "RESUME_INGESTION", resume, false);

        assertThat(provider.resolveStoredNames(TENANT, engineer(ENGINEER_ID))).containsExactly("resume-doc.pdf");
        assertThat(provider.resolveStoredNames(TENANT, PrivacySubjectRef.of(PrivacySubjectType.CANDIDATE, CANDIDATE_ID)))
                .containsExactly("resume-doc.pdf");
        assertThat(provider.findBySubject(TENANT, PrivacySubjectRef.of(PrivacySubjectType.SYS_USER, ENGINEER_ID))).isEmpty();
    }

    @Test
    void 他tenantのlink文書と版は返さない() {
        long otherDoc = fx.document(BASE_ID + 371, OTHER_TENANT, "SKILL_SHEET", false);
        fx.version(BASE_ID + 372, OTHER_TENANT, otherDoc, 1, "other.pdf", false);
        fx.link(BASE_ID + 373, OTHER_TENANT, otherDoc, "ENGINEER", ENGINEER_ID, false);

        assertThat(provider.findBySubject(TENANT, engineer(ENGINEER_ID))).isEmpty();
        assertThat(provider.resolveStoredNames(OTHER_TENANT, engineer(ENGINEER_ID))).containsExactly("other.pdf");
    }

    @Test
    void 照会は書込みを行わない() {
        long document = fx.document(BASE_ID + 381, TENANT, "SKILL_SHEET", false);
        fx.version(BASE_ID + 382, TENANT, document, 1, "w.pdf", false);
        fx.link(BASE_ID + 383, TENANT, document, "ENGINEER", ENGINEER_ID, false);
        List<?> documents = fx.snapshot("t_document", BASE_ID);
        List<?> versions = fx.snapshot("t_document_version", BASE_ID);
        List<?> links = fx.snapshot("t_document_link", BASE_ID);

        provider.findBySubject(TENANT, engineer(ENGINEER_ID));

        assertThat(fx.snapshot("t_document", BASE_ID)).isEqualTo(documents);
        assertThat(fx.snapshot("t_document_version", BASE_ID)).isEqualTo(versions);
        assertThat(fx.snapshot("t_document_link", BASE_ID)).isEqualTo(links);
    }

    /** 文書・版・link を作る（ID は BASE_ID + offset、版は +1、link は +1 の別表）。 */
    private long linkedDocument(int offset, String type, String targetType, long targetId) {
        long document = fx.document(BASE_ID + offset, TENANT, type, false);
        fx.version(BASE_ID + offset + 1, TENANT, document, 1, "doc-" + offset + ".pdf", false);
        fx.link(BASE_ID + offset, TENANT, document, targetType, targetId, false);
        return document;
    }

    private static PrivacySubjectRef engineer(long id) {
        return PrivacySubjectRef.of(PrivacySubjectType.ENGINEER, id);
    }
}
