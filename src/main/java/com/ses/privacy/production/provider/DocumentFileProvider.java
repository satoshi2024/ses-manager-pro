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
 * <p>data element は文書種別ごとの {@code DOCUMENT:<document_type>}（§3.4.1 D1〜D3）。本人の導出は
 * {@code t_document_link} の target のうち人を指すものから行う。
 * <ul>
 *   <li>{@code ENGINEER ← t_document_link.target_id}（{@code target_type='ENGINEER'}）</li>
 *   <li>{@code RESUME_INGESTION} の link は履歴取込の本人規則（{@link IngestionTextProvider} と同じ）で
 *       {@code converted_engineer_id → ENGINEER}・{@code candidate_id → CANDIDATE} に導出する</li>
 * </ul>
 * 人を指す link を持たない文書（契約・請求等だけに link する文書）は帰属できない行として扱う（§3.4.3 規則4）。
 * link・文書・版は論理削除済みも含める。保存名は {@code t_document_version.storage_key}
 * （{@code DocumentArchiveFileReferenceProvider}・{@code FileScopeValidationService} と同じ列）。
 * 取引先の名称・金額は第三者・業務の情報なので読まない。
 */
@Component
public class DocumentFileProvider implements ProductionPrivacyProvider {

    public static final String PROVIDER_ID = "document-file";
    public static final String ELEMENT_PREFIX = "DOCUMENT:";
    /** 版の親文書を同じ照会で読めなかった場合の data element（catalog に無いため coverage 上は未登録として扱われる）。 */
    public static final String UNRESOLVED_ELEMENT = ELEMENT_PREFIX + "UNRESOLVED";

    static final ProviderTable DOCUMENT = new ProviderTable("t_document", List.of(
            "id", "tenant_id", "document_type", "document_no", "title", "transaction_date", "status",
            "retention_until", "legal_hold_flag", "created_at", "updated_at", "deleted_flag"), List.of());

    static final ProviderTable VERSION = new ProviderTable("t_document_version", List.of(
            "id", "tenant_id", "document_id", "version_no", "storage_key", "original_name", "content_type",
            "size_bytes", "sha256", "scan_status", "created_at", "deleted_flag"), List.of("storage_key"));

    private static final String ENGINEER_LINKED_DOCUMENTS = "SELECT l.document_id FROM t_document_link l "
            + "WHERE l.tenant_id = :tenantId AND ((l.target_type = 'ENGINEER' AND l.target_id = :subjectId) "
            + "OR (l.target_type = 'RESUME_INGESTION' AND l.target_id IN (SELECT r.id FROM t_resume_ingestion r "
            + "WHERE r.tenant_id = :tenantId AND r.converted_engineer_id = :subjectId)))";

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
                        "t_document_link(target_type=RESUME_INGESTION)→t_resume_ingestion.candidate_id"))));
    }

    @Override
    public List<ProviderTable> tables() {
        return List.of(DOCUMENT, VERSION);
    }

    @Override
    public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
        ProviderRows.requireTenant(tenantId);
        String linkedDocuments = switch (subject.type()) {
            case ENGINEER -> ENGINEER_LINKED_DOCUMENTS;
            case CANDIDATE -> CANDIDATE_LINKED_DOCUMENTS;
            default -> null;
        };
        if (linkedDocuments == null) {
            return List.of();
        }
        Map<String, Object> params = ProviderRows.params(tenantId, subject);
        List<PrivacyRecord> records = new ArrayList<>();
        Map<Long, String> elementByDocument = new HashMap<>();
        for (Map<String, Object> row : query.select(DOCUMENT,
                "tenant_id = :tenantId AND id IN (" + linkedDocuments + ")", params)) {
            String type = ProviderRows.text(row.get("document_type"));
            String element = type == null ? UNRESOLVED_ELEMENT : ELEMENT_PREFIX + type;
            PrivacyRecord record = ProviderRows.toRecord(PROVIDER_ID, DOCUMENT, row, List.of(element), true);
            elementByDocument.put(record.rowId(), element);
            records.add(record);
        }
        for (Map<String, Object> row : query.select(VERSION,
                "tenant_id = :tenantId AND document_id IN (" + linkedDocuments + ")", params)) {
            Long documentId = ProviderRows.longValue(row.get("document_id"));
            String element = documentId == null ? UNRESOLVED_ELEMENT
                    : elementByDocument.getOrDefault(documentId, UNRESOLVED_ELEMENT);
            records.add(ProviderRows.toRecord(PROVIDER_ID, VERSION, row, List.of(element), true));
        }
        return records;
    }
}
