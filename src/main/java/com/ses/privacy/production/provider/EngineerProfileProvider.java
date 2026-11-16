package com.ses.privacy.production.provider;

import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 要員の基本情報と顔写真（{@code t_engineer}、pii-inventory.md DB-004）。
 *
 * <p>本人の導出: {@code ENGINEER ← t_engineer.id}。顔写真の保存名は {@code photo_url}
 * （{@code EngineerFileReferenceProvider}・{@code FileScopeValidationService} と同じ列）。
 * 単価・原価・組織の内部ID・作成者・version 列は読まない。
 */
@Component
public class EngineerProfileProvider implements ProductionPrivacyProvider {

    public static final String PROVIDER_ID = "engineer-profile";
    public static final String PROFILE = "ENGINEER_PROFILE";
    public static final String PHOTO = "ENGINEER_PHOTO";

    static final ProviderTable ENGINEER = new ProviderTable("t_engineer", List.of(
            "id", "tenant_id", "full_name", "full_name_kana", "initial_name", "gender", "birth_date", "nationality",
            "nearest_station", "prefecture", "railway_company", "phone", "employment_type", "status",
            "available_date", "experience_years", "japanese_level", "resume_summary", "photo_url", "remarks",
            "created_at", "updated_at", "deleted_flag"), List.of("photo_url"));

    private static final List<SubjectLink> LINKS = List.of(new SubjectLink(PrivacySubjectType.ENGINEER, "t_engineer.id"));

    private final PrivacyReadOnlyQuery query;

    public EngineerProfileProvider(PrivacyReadOnlyQuery query) {
        this.query = query;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<ProviderDataElement> dataElements() {
        return List.of(ProviderDataElement.exact(PROFILE, SubjectAttribution.ATTRIBUTABLE, LINKS),
                ProviderDataElement.exact(PHOTO, SubjectAttribution.ATTRIBUTABLE, LINKS));
    }

    @Override
    public List<ProviderTable> tables() {
        return List.of(ENGINEER);
    }

    @Override
    public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
        ProviderRows.requireTenant(tenantId);
        if (subject.type() != PrivacySubjectType.ENGINEER) {
            return List.of();
        }
        List<PrivacyRecord> records = new ArrayList<>();
        for (Map<String, Object> row : query.select(ENGINEER, "tenant_id = :tenantId AND id = :subjectId",
                ProviderRows.params(tenantId, subject))) {
            List<String> elements = ProviderRows.text(row.get("photo_url")) == null
                    ? List.of(PROFILE) : List.of(PROFILE, PHOTO);
            records.add(ProviderRows.toRecord(PROVIDER_ID, ENGINEER, row, elements, true));
        }
        return records;
    }
}
