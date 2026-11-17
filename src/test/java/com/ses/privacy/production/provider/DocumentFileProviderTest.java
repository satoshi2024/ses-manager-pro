package com.ses.privacy.production.provider;

import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import com.ses.service.impl.DocumentArchiveFileReferenceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

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
        // 論理削除されていない版の保存名は、孤児清掃を止める既存の FileReferenceProvider も参照ありとする（P3-9）
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
    void 請求と電子請求書XMLと注文は明細の要員へ和集合で導出する() {
        fx.engineer(ENGINEER_ID, TENANT, "要員E", null, false);
        fx.engineer(OTHER_ENGINEER_ID, TENANT, "要員O", null, false);
        long contract = fx.customerProjectContract(BASE_ID + 381, ENGINEER_ID);
        long otherContract = fx.customerProjectContract(BASE_ID + 382, OTHER_ENGINEER_ID);
        long workRecord = fx.workRecord(BASE_ID + 383, contract);
        long otherWorkRecord = fx.workRecord(BASE_ID + 384, otherContract);
        // 2名の実績を含む請求（PDF は INVOICE link、XML は t_digital_invoice の逆参照）
        long invoiceId = fx.invoice(BASE_ID + 385, contract);
        fx.invoiceItem(BASE_ID + 386, invoiceId, workRecord);
        fx.invoiceItem(BASE_ID + 387, invoiceId, otherWorkRecord);
        long invoicePdf = linkedDocument(391, "INVOICE_OUT", "INVOICE", invoiceId);
        long invoiceXml = fx.document(BASE_ID + 393, TENANT, "INVOICE_OUT", false);
        fx.version(BASE_ID + 394, TENANT, invoiceXml, 1, "invoice-393.xml", false);
        fx.digitalInvoice(BASE_ID + 395, TENANT, invoiceId, invoiceXml);
        // 2名を明細に持つ注文（注文書・注文請書とも SALES_ORDER link）
        long orderId = fx.salesOrder(BASE_ID + 396, contract);
        fx.salesOrderLine(BASE_ID + 397, orderId, 1, ENGINEER_ID);
        fx.salesOrderLine(BASE_ID + 398, orderId, 2, OTHER_ENGINEER_ID);
        long orderReceived = linkedDocument(401, "ORDER_RECEIVED", "SALES_ORDER", orderId);
        long orderAck = linkedDocument(403, "ORDER_ACKNOWLEDGEMENT", "SALES_ORDER", orderId);
        // 他の要員だけの注文は本人の文書にしない
        long otherOrder = fx.salesOrder(BASE_ID + 405, otherContract);
        fx.salesOrderLine(BASE_ID + 406, otherOrder, 1, OTHER_ENGINEER_ID);
        long otherOrderDoc = linkedDocument(407, "ORDER_RECEIVED", "SALES_ORDER", otherOrder);

        Set<Long> self = PrivacyProviderH2Fixture.rowIds(provider.findBySubject(TENANT, engineer(ENGINEER_ID)),
                "t_document");
        Set<Long> other = PrivacyProviderH2Fixture.rowIds(provider.findBySubject(TENANT, engineer(OTHER_ENGINEER_ID)),
                "t_document");

        assertThat(self).containsExactlyInAnyOrder(invoicePdf, invoiceXml, orderReceived, orderAck);
        assertThat(other).containsExactlyInAnyOrder(invoicePdf, invoiceXml, orderReceived, orderAck, otherOrderDoc);
        assertThat(provider.resolveStoredNames(TENANT, engineer(ENGINEER_ID)))
                .contains("doc-391.pdf", "invoice-393.xml", "doc-401.pdf", "doc-403.pdf").doesNotContain("doc-407.pdf");
    }

    @Test
    void 導出できない文書種別は要員と同じIDのtargetにlinkしても本人へ帰属しない() {
        // 残余の種別が通常付ける link（サービス依頼・取込job）と、link なし
        long service = linkedDocument(351, "SERVICE_REQUEST_ATTACHMENT", "SERVICE_REQUEST", ENGINEER_ID);
        long imported = linkedDocument(357, "IMPORT", "IMPORT_JOB", ENGINEER_ID);
        long report = fx.document(BASE_ID + 359, TENANT, "MANAGEMENT_REPORT", false);

        Set<Long> attributed = PrivacyProviderH2Fixture.rowIds(provider.findBySubject(TENANT, engineer(ENGINEER_ID)),
                "t_document");

        assertThat(attributed).doesNotContain(service, imported, report);
        assertThat(DocumentFileProvider.DOCUMENT_TYPE_RULES).allSatisfy((code, rule) -> {
            boolean residual = Set.of("MANAGEMENT_REPORT", "SERVICE_REQUEST_ATTACHMENT", "IMPORT").contains(code);
            assertThat(rule.attribution()).as(code)
                    .isEqualTo(residual ? SubjectAttribution.NOT_ATTRIBUTABLE : SubjectAttribution.ATTRIBUTABLE);
        });
    }

    @Test
    void 文書種別は全て個別に宣言し未知の種別だけを除外付きの接頭辞で受け交付区分を種別ごとに持つ() {
        List<ProviderDataElement> elements = provider.dataElements();
        ProviderDataElement fallback = elements.stream().filter(ProviderDataElement::prefixMatch).findFirst().orElseThrow();
        Set<String> exact = elements.stream().filter(e -> !e.prefixMatch()).map(ProviderDataElement::dataElementId)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(elements.stream().filter(ProviderDataElement::prefixMatch)).hasSize(1);
        assertThat(exact).hasSize(DocumentFileProvider.DOCUMENT_TYPE_RULES.size());
        assertThat(fallback.excludedIds()).isEqualTo(exact);
        assertThat(fallback.matches("DOCUMENT:IMPORT")).isFalse();
        assertThat(fallback.matches("DOCUMENT:NEW_TYPE")).isTrue();
        assertThat(fallback.deliveryClass()).isEqualTo(DsarDeliveryClass.MIXED_THIRD_PARTY);
        assertThat(elements).filteredOn(e -> e.deliveryClass() == DsarDeliveryClass.SELF)
                .extracting(ProviderDataElement::dataElementId).containsExactlyInAnyOrder("DOCUMENT:WORK_REPORT",
                        "DOCUMENT:RECEIPT", "DOCUMENT:SKILL_SHEET", "DOCUMENT:CHANGE_REQUEST_ATTACHMENT",
                        "DOCUMENT:CERTIFICATION_EVIDENCE", "DOCUMENT:RESUME_INGESTION");
        assertThat(elements).filteredOn(e -> e.attribution() == SubjectAttribution.NOT_ATTRIBUTABLE)
                .allSatisfy(e -> {
                    assertThat(e.subjectLinks()).isEmpty();
                    assertThat(e.deliveryClass()).isEqualTo(DsarDeliveryClass.MIXED_THIRD_PARTY);
                });
    }

    /**
     * migration（classpath の {@code db/migration/*.sql}）が {@code m_document_type} に入れる全種別が宣言と一致する
     * （共有 H2 の状態・実行順に依存しない。宣言に残った古い種別も検出する。NF07-F2 delta review P3-6）。
     */
    @Test
    void migrationが入れる全文書種別と宣言は過不足なく一致する() throws Exception {
        assertThat(DocumentFileProvider.DOCUMENT_TYPE_RULES.keySet()).isEqualTo(migrationDocumentTypes());
    }

    /** migration の {@code INSERT … INTO m_document_type} 文から (code, name, direction) の code を取り出す。 */
    static Set<String> migrationDocumentTypes() throws Exception {
        java.util.regex.Pattern row = java.util.regex.Pattern.compile(
                "'([A-Z][A-Z0-9_]*)'\\s*,\\s*'[^']*'\\s*,\\s*'(INCOMING|OUTGOING|INTERNAL)'");
        Set<String> codes = new java.util.TreeSet<>();
        for (org.springframework.core.io.Resource resource : new org.springframework.core.io.support
                .PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*.sql")) {
            String sql = new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String statement : sql.split(";")) {
                if (statement.matches("(?is).*\\bINSERT\\b[^;]*\\bINTO\\s+m_document_type\\b.*")) {
                    java.util.regex.Matcher matcher = row.matcher(statement);
                    while (matcher.find()) {
                        codes.add(matcher.group(1));
                    }
                }
            }
        }
        assertThat(codes).as("migration から文書種別を読めること").hasSizeGreaterThan(20);
        return codes;
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
