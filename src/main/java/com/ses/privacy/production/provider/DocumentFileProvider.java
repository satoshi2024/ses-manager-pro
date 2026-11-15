package com.ses.privacy.production.provider;

import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文書台帳の文書と版（{@code t_document} / {@code t_document_version}、pii-inventory.md DB-017）。
 *
 * <p>data element は文書種別ごとの {@code DOCUMENT:<document_type>}（§3.4.1 D1〜D3）。本人は文書の link
 * （{@code t_document_link}）の target から、次の経路で導出する（どれか1つでも当たれば本人の文書。和集合）。
 * <ul>
 *   <li>{@code ENGINEER}: target がそのまま要員</li>
 *   <li>{@code RESUME_INGESTION}: 履歴取込の本人規則（{@link IngestionTextProvider} と同じ）で
 *       {@code converted_engineer_id → ENGINEER} と {@code candidate_id → CANDIDATE}</li>
 *   <li>{@code CERTIFICATION_RECORD}: {@code t_engineer_certification.engineer_id}</li>
 *   <li>{@code CONTRACT}: {@code t_contract.engineer_id}</li>
 *   <li>{@code WORK_RECORD}: {@code t_work_record.contract_id → t_contract.engineer_id}</li>
 *   <li>{@code BP_PAYMENT}: {@code t_bp_payment.work_record_id → t_work_record.contract_id → t_contract.engineer_id}</li>
 *   <li>{@code QUOTATION}: {@code t_quotation.engineer_id}（要員の無い見積は帰属できない）</li>
 * </ul>
 * link を持たない 1on1 相談メモ（{@code PRIVATE_NOTE}）は、{@code t_one_on_one_request.private_note_ref}（文書ID）から
 * {@code engineer_id} を導出する。どの経路にも当たらない文書は帰属できない行として扱う（§3.4.3 規則4）。
 * 文書種別ごとの導出経路と、導出できない種別（残余）は {@link #DERIVABLE_DOCUMENT_TYPES} /
 * {@link #UNATTRIBUTABLE_DOCUMENT_TYPES} に宣言し、{@code m_document_type} の全種別がどちらかに入ることを test で固定する
 * （plan §3.4.1 D1）。link・文書・版は論理削除済みも含める。保存名は {@code t_document_version.storage_key}
 * （{@code DocumentArchiveFileReferenceProvider}・{@code FileScopeValidationService} と同じ列）。
 * 取引先の名称・金額は第三者・業務の情報なので読まない。
 */
@Component
public class DocumentFileProvider implements ProductionPrivacyProvider {

    public static final String PROVIDER_ID = "document-file";
    public static final String ELEMENT_PREFIX = "DOCUMENT:";
    /** 版の親文書を同じ照会で読めなかった場合の data element（catalog に無いため coverage 上は未登録として扱われる）。 */
    public static final String UNRESOLVED_ELEMENT = ELEMENT_PREFIX + "UNRESOLVED";

    /** 本人を導出できる文書種別と、その登録経路が付ける link（plan §3.4.1 D1）。 */
    public static final Map<String, String> DERIVABLE_DOCUMENT_TYPES = Map.ofEntries(
            Map.entry("CONTRACT", "CONTRACT → t_contract.engineer_id"),
            Map.entry("SIGNED_PDF", "CONTRACT → t_contract.engineer_id"),
            Map.entry("ESIGN_CERT", "CONTRACT → t_contract.engineer_id"),
            Map.entry("ACCEPTANCE", "CONTRACT → t_contract.engineer_id"),
            Map.entry("DISPATCH_LEDGER", "CONTRACT → t_contract.engineer_id"),
            Map.entry("EMPLOYMENT_CONDITIONS_STATEMENT", "CONTRACT → t_contract.engineer_id"),
            Map.entry("DISPATCH_NOTICE", "CONTRACT → t_contract.engineer_id"),
            Map.entry("INDIVIDUAL_CONTRACT", "CONTRACT → t_contract.engineer_id"),
            Map.entry("INVOICE_IN", "CONTRACT / ENGINEER（受領請求の link が契約・要員のときだけ。他は帰属不能）"),
            Map.entry("WORK_REPORT", "WORK_RECORD → t_work_record.contract_id → t_contract.engineer_id"),
            Map.entry("QUOTATION", "QUOTATION → t_quotation.engineer_id（要員の無い見積は帰属不能）"),
            Map.entry("SKILL_SHEET", "ENGINEER"),
            Map.entry("CHANGE_REQUEST_ATTACHMENT", "ENGINEER"),
            Map.entry("RECEIPT", "ENGINEER"),
            Map.entry("CERTIFICATION_EVIDENCE", "CERTIFICATION_RECORD → t_engineer_certification.engineer_id"),
            Map.entry("PRIVATE_NOTE", "t_one_on_one_request.private_note_ref → engineer_id（link なし）"),
            Map.entry("BP_SUBMISSION", "BP_PAYMENT → t_bp_payment.work_record_id → t_work_record → t_contract.engineer_id"),
            Map.entry("RESUME_INGESTION", "RESUME_INGESTION → converted_engineer_id ∪ candidate_id"));

    /** 本人を導出できない文書種別と理由（plan §3.4.1 D1 の残余。A1H は hold 開始画面で件数を示し人が判断する）。 */
    public static final Map<String, String> UNATTRIBUTABLE_DOCUMENT_TYPES = Map.ofEntries(
            Map.entry("INVOICE_OUT", "INVOICE link。請求は顧客単位で複数の契約・要員を含み得る"),
            Map.entry("ORDER_RECEIVED", "SALES_ORDER link。注文は顧客・案件単位"),
            Map.entry("ORDER_ACKNOWLEDGEMENT", "SALES_ORDER link。注文は顧客・案件単位"),
            Map.entry("MANAGEMENT_REPORT", "link なし。経営レポートは集計で個人を対象としない"),
            Map.entry("SERVICE_REQUEST_ATTACHMENT", "SERVICE_REQUEST link。顧客 portal の依頼で本人種別に当たらない"),
            Map.entry("IMPORT", "IMPORT_JOB link。取込CSVは複数人の行を含み得る"));

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
            + "WHERE q.engineer_id = :subjectId)))";

    /** link を持たない 1on1 相談メモ（private_note_ref は文書IDの文字列）。 */
    private static final String ENGINEER_PRIVATE_NOTES =
            "SELECT o.private_note_ref FROM t_one_on_one_request o WHERE o.engineer_id = :subjectId";

    private static final String CANDIDATE_LINKED_DOCUMENTS = "SELECT l.document_id FROM t_document_link l "
            + "WHERE l.tenant_id = :tenantId AND l.target_type = 'RESUME_INGESTION' AND l.target_id IN "
            + "(SELECT r.id FROM t_resume_ingestion r WHERE r.tenant_id = :tenantId AND r.candidate_id = :subjectId)";

    private final PrivacyReadOnlyQuery query;

    public DocumentFileProvider(PrivacyReadOnlyQuery query) {
        this.query = query;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<ProviderDataElement> dataElements() {
        return List.of(ProviderDataElement.prefix(ELEMENT_PREFIX, SubjectAttribution.ATTRIBUTABLE, List.of(
                new SubjectLink(PrivacySubjectType.ENGINEER, "t_document_link.target_id(target_type=ENGINEER)"),
                new SubjectLink(PrivacySubjectType.ENGINEER,
                        "t_document_link(target_type=RESUME_INGESTION)→t_resume_ingestion.converted_engineer_id"),
                new SubjectLink(PrivacySubjectType.CANDIDATE,
                        "t_document_link(target_type=RESUME_INGESTION)→t_resume_ingestion.candidate_id"),
                new SubjectLink(PrivacySubjectType.ENGINEER,
                        "t_document_link(target_type=CERTIFICATION_RECORD)→t_engineer_certification.engineer_id"),
                new SubjectLink(PrivacySubjectType.ENGINEER,
                        "t_document_link(target_type=CONTRACT)→t_contract.engineer_id"),
                new SubjectLink(PrivacySubjectType.ENGINEER,
                        "t_document_link(target_type=WORK_RECORD)→t_work_record.contract_id→t_contract.engineer_id"),
                new SubjectLink(PrivacySubjectType.ENGINEER,
                        "t_document_link(target_type=BP_PAYMENT)→t_bp_payment.work_record_id→t_work_record→t_contract.engineer_id"),
                new SubjectLink(PrivacySubjectType.ENGINEER,
                        "t_document_link(target_type=QUOTATION)→t_quotation.engineer_id"),
                new SubjectLink(PrivacySubjectType.ENGINEER,
                        "t_one_on_one_request.private_note_ref(=t_document.id)→engineer_id"))));
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
                        + ENGINEER_PRIVATE_NOTES + "))";
                versionFilter = "tenant_id = :tenantId AND (document_id IN (" + ENGINEER_LINKED_DOCUMENTS
                        + ") OR document_id IN (" + ENGINEER_PRIVATE_NOTES + "))";
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
}
