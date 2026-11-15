package com.ses.privacy.production.provider;

import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 推薦の redacted summary（{@code t_ai_recommendation_run.redacted_summary_json}、pii-inventory.md DB-029、§3.4.1 D6）。
 *
 * <p>本人の導出: run は対象の列を持たないため、推薦候補 {@code t_ai_recommendation_item} の
 * {@code target_type='ENGINEER'} の {@code target_id → ENGINEER} から導出する。要員の候補を持たない run は
 * 帰属できない行として扱う（§3.4.3 規則4）。実行者（{@code actor_user_id}）は操作の主体であって内容の本人ではないため
 * 導出に使わない。hash・費用・token 数・外部送信の claim 列は読まない。
 *
 * <p>legacy の {@code t_ai_log} は tenant 列・論理削除列を持たず、{@code request_params} の本人も導出できないため
 * 本 provider では扱わない（catalog では未登録。DATA_ELEMENT hold だけが対象）。
 */
@Component
public class AiLogProvider implements ProductionPrivacyProvider {

    public static final String PROVIDER_ID = "ai-log";
    public static final String REDACTED_SUMMARY = "AI_REDACTED_SUMMARY";

    static final ProviderTable RUN = new ProviderTable("t_ai_recommendation_run", List.of(
            "id", "tenant_id", "trace_id", "use_case", "redacted_summary_json", "status", "created_at", "updated_at",
            "deleted_flag"), List.of());

    private final PrivacyReadOnlyQuery query;

    public AiLogProvider(PrivacyReadOnlyQuery query) {
        this.query = query;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<ProviderDataElement> dataElements() {
        return List.of(ProviderDataElement.exact(REDACTED_SUMMARY, SubjectAttribution.ATTRIBUTABLE, List.of(
                new SubjectLink(PrivacySubjectType.ENGINEER,
                        "t_ai_recommendation_item.target_id(target_type=ENGINEER)"))));
    }

    @Override
    public List<ProviderTable> tables() {
        return List.of(RUN);
    }

    @Override
    public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
        ProviderRows.requireTenant(tenantId);
        if (subject.type() != PrivacySubjectType.ENGINEER) {
            return List.of();
        }
        List<PrivacyRecord> records = new ArrayList<>();
        for (Map<String, Object> row : query.select(RUN, "tenant_id = :tenantId AND id IN (SELECT i.run_id "
                + "FROM t_ai_recommendation_item i WHERE i.tenant_id = :tenantId AND i.target_type = 'ENGINEER' "
                + "AND i.target_id = :subjectId)", ProviderRows.params(tenantId, subject))) {
            records.add(ProviderRows.toRecord(PROVIDER_ID, RUN, row, List.of(REDACTED_SUMMARY), true));
        }
        return records;
    }
}
