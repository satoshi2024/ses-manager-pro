package com.ses.privacy.production.provider;

import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 履歴取込と BP 空き取込の原本・抽出本文（pii-inventory.md DB-009 / DB-010、§3.4.1 D4・D5）。
 *
 * <p>本人の導出:
 * <ul>
 *   <li>{@code t_resume_ingestion}: {@code converted_engineer_id → ENGINEER} と {@code candidate_id → CANDIDATE} の和集合。
 *       どちらも NULL の行は帰属できない（§3.4.3 規則4）。変換済みの行は候補者としても要員としても返す
 *       （どちらの hold・請求でも見落とさないため）。</li>
 *   <li>{@code t_bp_availability_ingestion}: {@code converted_availability_id → BP_AVAILABILITY}。表に tenant 列が無いため、
 *       変換先 {@code t_bp_availability} の tenant で絞る。貼付け取込（{@code file_ext='PASTE'}）は実体 file を持たない
 *       （{@code BpAvailabilityFileReferenceProvider} と同じ規則）。</li>
 * </ul>
 * 案件メール取込（{@code t_project_ingestion}）は本人へ導出できないため本 provider では扱わない（catalog では未登録）。
 * AI の実行情報（provider・model）・エラー本文・作成者は読まない。
 */
@Component
public class IngestionTextProvider implements ProductionPrivacyProvider {

    public static final String PROVIDER_ID = "ingestion-text";
    public static final String RESUME_ORIGINAL = "RESUME_INGESTION_ORIGINAL";
    public static final String RESUME_TEXT = "RESUME_INGESTION_TEXT";
    public static final String BP_ORIGINAL = "BP_AVAILABILITY_INGESTION_ORIGINAL";
    public static final String BP_TEXT = "BP_AVAILABILITY_INGESTION_TEXT";

    private static final String PASTE = "PASTE";

    static final ProviderTable RESUME = new ProviderTable("t_resume_ingestion", List.of(
            "id", "tenant_id", "original_file_name", "stored_file_name", "file_ext", "status", "extracted_text",
            "parsed_json", "review_note", "converted_engineer_id", "candidate_id", "created_at", "updated_at",
            "deleted_flag"), List.of("stored_file_name"));

    static final ProviderTable BP = new ProviderTable("t_bp_availability_ingestion", List.of(
            "id", "original_file_name", "stored_file_name", "file_ext", "status", "extracted_text", "parsed_json",
            "review_note", "converted_availability_id", "created_at", "updated_at", "deleted_flag"),
            List.of("stored_file_name"));

    private final PrivacyReadOnlyQuery query;

    public IngestionTextProvider(PrivacyReadOnlyQuery query) {
        this.query = query;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<ProviderDataElement> dataElements() {
        List<SubjectLink> resume = List.of(
                new SubjectLink(PrivacySubjectType.ENGINEER, "t_resume_ingestion.converted_engineer_id"),
                new SubjectLink(PrivacySubjectType.CANDIDATE, "t_resume_ingestion.candidate_id"));
        List<SubjectLink> bp = List.of(new SubjectLink(PrivacySubjectType.BP_AVAILABILITY,
                "t_bp_availability_ingestion.converted_availability_id"));
        return List.of(ProviderDataElement.exact(RESUME_ORIGINAL, SubjectAttribution.ATTRIBUTABLE, resume),
                ProviderDataElement.exact(RESUME_TEXT, SubjectAttribution.ATTRIBUTABLE, resume),
                ProviderDataElement.exact(BP_ORIGINAL, SubjectAttribution.ATTRIBUTABLE, bp),
                ProviderDataElement.exact(BP_TEXT, SubjectAttribution.ATTRIBUTABLE, bp));
    }

    @Override
    public List<ProviderTable> tables() {
        return List.of(RESUME, BP);
    }

    @Override
    public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
        ProviderRows.requireTenant(tenantId);
        Map<String, Object> params = ProviderRows.params(tenantId, subject);
        List<PrivacyRecord> records = new ArrayList<>();
        switch (subject.type()) {
            case ENGINEER -> addResume(records, "tenant_id = :tenantId AND converted_engineer_id = :subjectId", params);
            case CANDIDATE -> addResume(records, "tenant_id = :tenantId AND candidate_id = :subjectId", params);
            case BP_AVAILABILITY -> {
                for (Map<String, Object> row : query.select(BP, "converted_availability_id = :subjectId "
                        + "AND converted_availability_id IN (SELECT a.id FROM t_bp_availability a "
                        + "WHERE a.tenant_id = :tenantId)", params)) {
                    boolean hasFile = ProviderRows.text(row.get("stored_file_name")) != null
                            && !PASTE.equalsIgnoreCase(ProviderRows.text(row.get("file_ext")));
                    records.add(ProviderRows.toRecord(PROVIDER_ID, BP, row,
                            hasFile ? List.of(BP_TEXT, BP_ORIGINAL) : List.of(BP_TEXT), hasFile));
                }
            }
            default -> {
                // 他の種別は扱わない
            }
        }
        return records;
    }

    private void addResume(List<PrivacyRecord> records, String where, Map<String, Object> params) {
        for (Map<String, Object> row : query.select(RESUME, where, params)) {
            boolean hasFile = ProviderRows.text(row.get("stored_file_name")) != null;
            records.add(ProviderRows.toRecord(PROVIDER_ID, RESUME, row,
                    hasFile ? List.of(RESUME_TEXT, RESUME_ORIGINAL) : List.of(RESUME_TEXT), true));
        }
    }
}
