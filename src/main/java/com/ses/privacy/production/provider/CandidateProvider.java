package com.ses.privacy.production.provider;

import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 候補者と選考ステージ履歴（{@code t_candidate} / {@code t_candidate_activity}、pii-inventory.md DB-008）。
 *
 * <p>本人の導出: {@code CANDIDATE ← t_candidate.id}、履歴は {@code t_candidate_activity.candidate_id}。
 * 履歴表は tenant 列を持たないため、親の候補者の tenant で絞る。変更者（{@code changed_by}）・作成者は読まない。
 * 要員へ変換済みの候補者でも要員側の行は返さない（種別をまたいだ名寄せをしない）。
 */
@Component
public class CandidateProvider implements ProductionPrivacyProvider {

    public static final String PROVIDER_ID = "candidate";
    public static final String PROFILE = "CANDIDATE_PROFILE";

    static final ProviderTable CANDIDATE = new ProviderTable("t_candidate", List.of(
            "id", "tenant_id", "name", "contact_email", "contact_phone", "skill_summary", "desired_rate", "source",
            "current_stage", "next_action_date", "converted_engineer_id", "remarks", "created_at", "updated_at",
            "deleted_flag"), List.of());

    static final ProviderTable ACTIVITY = new ProviderTable("t_candidate_activity", List.of(
            "id", "candidate_id", "stage", "reason", "changed_at", "remarks"), List.of());

    private final PrivacyReadOnlyQuery query;

    public CandidateProvider(PrivacyReadOnlyQuery query) {
        this.query = query;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<ProviderDataElement> dataElements() {
        return List.of(ProviderDataElement.exact(PROFILE, SubjectAttribution.ATTRIBUTABLE, List.of(
                new SubjectLink(PrivacySubjectType.CANDIDATE, "t_candidate.id"),
                new SubjectLink(PrivacySubjectType.CANDIDATE, "t_candidate_activity.candidate_id"))));
    }

    @Override
    public List<ProviderTable> tables() {
        return List.of(CANDIDATE, ACTIVITY);
    }

    @Override
    public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
        ProviderRows.requireTenant(tenantId);
        if (subject.type() != PrivacySubjectType.CANDIDATE) {
            return List.of();
        }
        Map<String, Object> params = ProviderRows.params(tenantId, subject);
        List<PrivacyRecord> records = new ArrayList<>();
        for (Map<String, Object> row : query.select(CANDIDATE, "tenant_id = :tenantId AND id = :subjectId", params)) {
            records.add(ProviderRows.toRecord(PROVIDER_ID, CANDIDATE, row, List.of(PROFILE), true));
        }
        for (Map<String, Object> row : query.select(ACTIVITY, "candidate_id = :subjectId AND candidate_id IN "
                + "(SELECT c.id FROM t_candidate c WHERE c.tenant_id = :tenantId)", params)) {
            records.add(ProviderRows.toRecord(PROVIDER_ID, ACTIVITY, row, List.of(PROFILE), true));
        }
        return records;
    }
}
