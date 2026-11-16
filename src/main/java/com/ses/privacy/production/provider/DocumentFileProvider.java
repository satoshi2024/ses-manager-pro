package com.ses.privacy.production.provider;

import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 文書台帳の文書と版（{@code t_document} / {@code t_document_version}、pii-inventory.md DB-017）。
 *
 * <p>data element は文書種別ごとの {@code DOCUMENT:<document_type>}（§3.4.1 D1〜D3）。本人は文書の link
 * （{@code t_document_link}）の target から、次の経路で導出する（どれか1つでも当たれば本人の文書。和集合で、
 * 複数の要員を含む請求・注文は含まれる全員の文書になる）。
 * <ul>
 *   <li>{@code ENGINEER}: target がそのまま要員</li>
 *   <li>{@code RESUME_INGESTION}: 履歴取込の本人規則（{@link IngestionTextProvider} と同じ）で
 *       {@code converted_engineer_id → ENGINEER} と {@code candidate_id → CANDIDATE}</li>
 *   <li>{@code CERTIFICATION_RECORD}: {@code t_engineer_certification.engineer_id}</li>
 *   <li>{@code CONTRACT}: {@code t_contract.engineer_id}</li>
 *   <li>{@code WORK_RECORD}: {@code t_work_record.contract_id → t_contract.engineer_id}</li>
 *   <li>{@code BP_PAYMENT}: {@code t_bp_payment.work_record_id → t_work_record.contract_id → t_contract.engineer_id}</li>
 *   <li>{@code QUOTATION}: {@code t_quotation.engineer_id}（要員の無い見積は帰属できない）</li>
 *   <li>{@code INVOICE}: {@code t_invoice_item.work_record_id → t_work_record.contract_id → t_contract.engineer_id}</li>
 *   <li>{@code SALES_ORDER}: {@code t_sales_order_line.engineer_id}</li>
 * </ul>
 * link を持たない文書は逆参照で導出する: 1on1 相談メモ（{@code t_one_on_one_request.private_note_ref} は文書IDの
 * 文字列。H2・MySQL とも数値との暗黙比較で一致する。書込みは常に {@code String.valueOf(documentId)}）と、電子請求書
 * XML（{@code t_digital_invoice.xml_document_id} → 請求の明細 → 実績 → 契約 → 要員）。
 * どの経路にも当たらない文書は帰属できない行として扱う（§3.4.3 規則4）。
 *
 * <p>中間の表（契約・実績・BP支払・見積・請求明細・注文明細・1on1）は tenant で絞らない。文書と link は tenant で
 * 絞り、各表の ID は tenant をまたいで一意なので他 tenant の行は混ざらない。絞らないことで、tenant を解決できて
 * いない契約（V177 で NULL のまま残る行）も hold の範囲に入る（保全の側に倒す）。
 *
 * <p>文書種別ごとの宣言（本人導出の有無・DSAR の交付区分）は {@link #DOCUMENT_TYPE_RULES} が唯一の正本で、
 * {@link #dataElements()} は全種別を個別の宣言にし、未知の種別だけを接頭辞の宣言（除外付き）で受ける。catalog（V196・V197）
 * の種別ごとの分類はこの宣言と一致させ、{@code m_document_type} の全種別（migration を解析）がこの表にあることを
 * test で固定する（plan §3.4.1 D1）。link・文書・版は論理削除済みも含める。保存名は {@code t_document_version.storage_key}
 * （{@code DocumentArchiveFileReferenceProvider}・{@code FileScopeValidationService} と同じ列）。
 * 取引先の名称・金額は第三者・業務の情報なので読まない。
 */
@Component
public class DocumentFileProvider implements ProductionPrivacyProvider {

    public static final String PROVIDER_ID = "document-file";
    public static final String ELEMENT_PREFIX = "DOCUMENT:";
    /** 版の親文書を同じ照会で読めなかった場合の data element（catalog に無いため coverage 上は未登録として扱われる）。 */
    public static final String UNRESOLVED_ELEMENT = ELEMENT_PREFIX + "UNRESOLVED";

    /**
     * 文書種別ごとの宣言（plan §3.4.1 D1 の表と一致させる。種別を足す batch はここへ足す）。
     * 交付区分 {@code MIXED_THIRD_PARTY} の種別は D-06 の規則があっても自動 package に入らない。
     */
    public static final Map<String, DocumentTypeRule> DOCUMENT_TYPE_RULES = Map.ofEntries(
            mixed("CONTRACT", "CONTRACT → t_contract.engineer_id"),
            mixed("SIGNED_PDF", "CONTRACT → t_contract.engineer_id"),
            mixed("ESIGN_CERT", "CONTRACT → t_contract.engineer_id"),
            mixed("ACCEPTANCE", "CONTRACT → t_contract.engineer_id"),
            mixed("DISPATCH_LEDGER", "CONTRACT → t_contract.engineer_id"),
            mixed("EMPLOYMENT_CONDITIONS_STATEMENT", "CONTRACT → t_contract.engineer_id"),
            mixed("DISPATCH_NOTICE", "CONTRACT → t_contract.engineer_id"),
            mixed("INDIVIDUAL_CONTRACT", "CONTRACT → t_contract.engineer_id"),
            mixed("INVOICE_OUT", "INVOICE → t_invoice_item.work_record_id → 契約 → 要員、電子請求書XMLは "
                    + "t_digital_invoice.xml_document_id の逆参照（複数要員は和集合）"),
            mixed("INVOICE_IN", "CONTRACT / SALES_ORDER / ENGINEER（受領請求の link がそれ以外なら帰属不能）"),
            mixed("ORDER_RECEIVED", "SALES_ORDER → t_sales_order_line.engineer_id（複数要員は和集合）"),
            mixed("ORDER_ACKNOWLEDGEMENT", "SALES_ORDER → t_sales_order_line.engineer_id（複数要員は和集合）"),
            mixed("QUOTATION", "QUOTATION → t_quotation.engineer_id（要員の無い見積は帰属不能）"),
            mixed("BP_SUBMISSION", "BP_PAYMENT → t_bp_payment.work_record_id → 実績 → 契約 → 要員"),
            mixed("PRIVATE_NOTE", "t_one_on_one_request.private_note_ref → engineer_id（link なし。作成者の記録を含む）"),
            self("WORK_REPORT", "WORK_RECORD → t_work_record.contract_id → t_contract.engineer_id"),
            self("SKILL_SHEET", "ENGINEER"),
            self("CHANGE_REQUEST_ATTACHMENT", "ENGINEER"),
            self("RECEIPT", "ENGINEER"),
            self("CERTIFICATION_EVIDENCE", "CERTIFICATION_RECORD → t_engineer_certification.engineer_id"),
            self("RESUME_INGESTION", "RESUME_INGESTION → converted_engineer_id ∪ candidate_id"),
            residual("MANAGEMENT_REPORT", "link なし。経営レポートは集計で個人を対象としない"),
            residual("SERVICE_REQUEST_ATTACHMENT", "SERVICE_REQUEST link。顧客 portal の依頼で本人種別に当たらない"),
            residual("IMPORT", "IMPORT_JOB link。取込CSVは複数人の行を含み、行と本人を対応付けられない"));

    static final ProviderTable DOCUMENT = new ProviderTable("t_document", List.of(
            "id", "tenant_id", "document_type", "document_no", "title", "transaction_date", "status",
            "retention_until", "legal_hold_flag", "created_at", "updated_at", "deleted_flag"), List.of());

    static final ProviderTable VERSION = new ProviderTable("t_document_version", List.of(
            "id", "tenant_id", "document_id", "version_no", "storage_key", "original_name", "content_type",
            "size_bytes", "sha256", "scan_status", "created_at", "deleted_flag"), List.of("storage_key"));

    private static final String CONTRACTS_OF_ENGINEER =
            "SELECT k.id FROM t_contract k WHERE k.engineer_id = :subjectId";

    private static final String WORK_RECORDS_OF_ENGINEER =
            "SELECT w.id FROM t_work_record w WHERE w.contract_id IN (" + CONTRACTS_OF_ENGINEER + ")";

    private static final String INVOICES_OF_ENGINEER =
            "SELECT n.invoice_id FROM t_invoice_item n WHERE n.work_record_id IN (" + WORK_RECORDS_OF_ENGINEER + ")";

    private static final String ENGINEER_LINKED_DOCUMENTS = "SELECT l.document_id FROM t_document_link l "
            + "WHERE l.tenant_id = :tenantId AND ((l.target_type = 'ENGINEER' AND l.target_id = :subjectId) "
            + "OR (l.target_type = 'RESUME_INGESTION' AND l.target_id IN (SELECT r.id FROM t_resume_ingestion r "
            + "WHERE r.tenant_id = :tenantId AND r.converted_engineer_id = :subjectId)) "
            + "OR (l.target_type = 'CERTIFICATION_RECORD' AND l.target_id IN (SELECT c.id FROM t_engineer_certification c "
            + "WHERE c.tenant_id = :tenantId AND c.engineer_id = :subjectId)) "
            + "OR (l.target_type = 'CONTRACT' AND l.target_id IN (" + CONTRACTS_OF_ENGINEER + ")) "
            + "OR (l.target_type = 'WORK_RECORD' AND l.target_id IN (" + WORK_RECORDS_OF_ENGINEER + ")) "
            + "OR (l.target_type = 'BP_PAYMENT' AND l.target_id IN (SELECT p.id FROM t_bp_payment p "
            + "WHERE p.work_record_id IN (" + WORK_RECORDS_OF_ENGINEER + "))) "
            + "OR (l.target_type = 'QUOTATION' AND l.target_id IN (SELECT q.id FROM t_quotation q "
            + "WHERE q.engineer_id = :subjectId)) "
            + "OR (l.target_type = 'INVOICE' AND l.target_id IN (" + INVOICES_OF_ENGINEER + ")) "
            + "OR (l.target_type = 'SALES_ORDER' AND l.target_id IN (SELECT s.order_id FROM t_sales_order_line s "
            + "WHERE s.engineer_id = :subjectId)))";

    /** link を持たない 1on1 相談メモ（private_note_ref は文書IDの文字列）。 */
    private static final String ENGINEER_PRIVATE_NOTES =
            "SELECT o.private_note_ref FROM t_one_on_one_request o WHERE o.engineer_id = :subjectId";

    /** link を持たない電子請求書 XML（請求の明細に要員の実績を含むもの）。 */
    private static final String ENGINEER_DIGITAL_INVOICES = "SELECT x.xml_document_id FROM t_digital_invoice x "
            + "WHERE x.invoice_id IN (" + INVOICES_OF_ENGINEER + ")";

    private static final String CANDIDATE_LINKED_DOCUMENTS = "SELECT l.document_id FROM t_document_link l "
            + "WHERE l.tenant_id = :tenantId AND l.target_type = 'RESUME_INGESTION' AND l.target_id IN "
            + "(SELECT r.id FROM t_resume_ingestion r WHERE r.tenant_id = :tenantId AND r.candidate_id = :subjectId)";

    private static final List<SubjectLink> LINKS = List.of(
            new SubjectLink(PrivacySubjectType.ENGINEER, "t_document_link.target_id(target_type=ENGINEER)"),
            new SubjectLink(PrivacySubjectType.ENGINEER,
                    "t_document_link(target_type=RESUME_INGESTION)→t_resume_ingestion.converted_engineer_id"),
            new SubjectLink(PrivacySubjectType.CANDIDATE,
                    "t_document_link(target_type=RESUME_INGESTION)→t_resume_ingestion.candidate_id"),
            new SubjectLink(PrivacySubjectType.ENGINEER,
                    "t_document_link(target_type=CERTIFICATION_RECORD)→t_engineer_certification.engineer_id"),
            new SubjectLink(PrivacySubjectType.ENGINEER, "t_document_link(target_type=CONTRACT)→t_contract.engineer_id"),
            new SubjectLink(PrivacySubjectType.ENGINEER,
                    "t_document_link(target_type=WORK_RECORD)→t_work_record.contract_id→t_contract.engineer_id"),
            new SubjectLink(PrivacySubjectType.ENGINEER,
                    "t_document_link(target_type=BP_PAYMENT)→t_bp_payment.work_record_id→t_work_record→t_contract.engineer_id"),
            new SubjectLink(PrivacySubjectType.ENGINEER, "t_document_link(target_type=QUOTATION)→t_quotation.engineer_id"),
            new SubjectLink(PrivacySubjectType.ENGINEER,
                    "t_document_link(target_type=INVOICE)→t_invoice_item.work_record_id→t_work_record→t_contract.engineer_id"),
            new SubjectLink(PrivacySubjectType.ENGINEER,
                    "t_document_link(target_type=SALES_ORDER)→t_sales_order_line.engineer_id"),
            new SubjectLink(PrivacySubjectType.ENGINEER,
                    "t_one_on_one_request.private_note_ref(=t_document.id)→engineer_id"),
            new SubjectLink(PrivacySubjectType.ENGINEER,
                    "t_digital_invoice.xml_document_id(=t_document.id)→t_invoice_item→t_work_record→t_contract.engineer_id"));

    private final PrivacyReadOnlyQuery query;

    public DocumentFileProvider(PrivacyReadOnlyQuery query) {
        this.query = query;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /**
     * 全ての文書種別を個別に宣言し（導出できる種別は ATTRIBUTABLE、残余は NOT_ATTRIBUTABLE）、宣言に無い未知の種別だけを
     * 接頭辞の宣言で受ける（ATTRIBUTABLE・第三者混在。catalog に行が無ければ coverage 上は未登録として報告される）。
     */
    @Override
    public List<ProviderDataElement> dataElements() {
        List<ProviderDataElement> elements = new ArrayList<>();
        TreeSet<String> declared = new TreeSet<>();
        for (Map.Entry<String, DocumentTypeRule> entry : new TreeMap<>(DOCUMENT_TYPE_RULES).entrySet()) {
            String id = ELEMENT_PREFIX + entry.getKey();
            DocumentTypeRule rule = entry.getValue();
            declared.add(id);
            elements.add(rule.attribution() == SubjectAttribution.ATTRIBUTABLE
                    ? ProviderDataElement.exact(id, SubjectAttribution.ATTRIBUTABLE, rule.deliveryClass(), LINKS)
                    : ProviderDataElement.exact(id, rule.attribution(), rule.deliveryClass(), List.of()));
        }
        elements.add(ProviderDataElement.prefix(ELEMENT_PREFIX, declared, SubjectAttribution.ATTRIBUTABLE,
                DsarDeliveryClass.MIXED_THIRD_PARTY, LINKS));
        return elements;
    }

    @Override
    public List<ProviderTable> tables() {
        return List.of(DOCUMENT, VERSION);
    }

    @Override
    public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
        ProviderRows.requireTenant(tenantId);
        String documentFilter;
        String versionFilter;
        switch (subject.type()) {
            case ENGINEER -> {
                documentFilter = "tenant_id = :tenantId AND (id IN (" + ENGINEER_LINKED_DOCUMENTS + ") OR id IN ("
                        + ENGINEER_PRIVATE_NOTES + ") OR id IN (" + ENGINEER_DIGITAL_INVOICES + "))";
                versionFilter = "tenant_id = :tenantId AND (document_id IN (" + ENGINEER_LINKED_DOCUMENTS
                        + ") OR document_id IN (" + ENGINEER_PRIVATE_NOTES + ") OR document_id IN ("
                        + ENGINEER_DIGITAL_INVOICES + "))";
            }
            case CANDIDATE -> {
                documentFilter = "tenant_id = :tenantId AND id IN (" + CANDIDATE_LINKED_DOCUMENTS + ")";
                versionFilter = "tenant_id = :tenantId AND document_id IN (" + CANDIDATE_LINKED_DOCUMENTS + ")";
            }
            default -> {
                return List.of();
            }
        }
        Map<String, Object> params = ProviderRows.params(tenantId, subject);
        List<PrivacyRecord> records = new ArrayList<>();
        Map<Long, String> elementByDocument = new HashMap<>();
        for (Map<String, Object> row : query.select(DOCUMENT, documentFilter, params)) {
            String type = ProviderRows.text(row.get("document_type"));
            String element = type == null ? UNRESOLVED_ELEMENT : ELEMENT_PREFIX + type;
            PrivacyRecord record = ProviderRows.toRecord(PROVIDER_ID, DOCUMENT, row, List.of(element), true);
            elementByDocument.put(record.rowId(), element);
            records.add(record);
        }
        for (Map<String, Object> row : query.select(VERSION, versionFilter, params)) {
            Long documentId = ProviderRows.longValue(row.get("document_id"));
            String element = documentId == null ? UNRESOLVED_ELEMENT
                    : elementByDocument.getOrDefault(documentId, UNRESOLVED_ELEMENT);
            records.add(ProviderRows.toRecord(PROVIDER_ID, VERSION, row, List.of(element), true));
        }
        return records;
    }

    private static Map.Entry<String, DocumentTypeRule> mixed(String code, String basis) {
        return Map.entry(code, new DocumentTypeRule(SubjectAttribution.ATTRIBUTABLE,
                DsarDeliveryClass.MIXED_THIRD_PARTY, basis));
    }

    private static Map.Entry<String, DocumentTypeRule> self(String code, String basis) {
        return Map.entry(code, new DocumentTypeRule(SubjectAttribution.ATTRIBUTABLE, DsarDeliveryClass.SELF, basis));
    }

    private static Map.Entry<String, DocumentTypeRule> residual(String code, String reason) {
        return Map.entry(code, new DocumentTypeRule(SubjectAttribution.NOT_ATTRIBUTABLE,
                DsarDeliveryClass.MIXED_THIRD_PARTY, reason));
    }

    /**
     * 文書種別1つの宣言。
     *
     * @param attribution   本人帰属（導出できる種別は ATTRIBUTABLE、残余は NOT_ATTRIBUTABLE）
     * @param deliveryClass DSAR の交付区分
     * @param basis         導出経路、または導出できない理由
     */
    public record DocumentTypeRule(SubjectAttribution attribution, DsarDeliveryClass deliveryClass, String basis) {
    }
}
