package com.ses.privacy.production.provider;

import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 推薦の redacted summary（{@code t_ai_recommendation_run.redacted_summary_json}、pii-inventory.md DB-029、§3.4.1 D6）。
 *
 * <p>summary の中身は記録時の「要員1名（と案件）」の masked 属性で、要員IDは持たない
 * （{@code AiRecommendationRecorderImpl}: 要員 = source 要員、無ければ推薦結果の1件目の要員）。本人は次の2つで導出する。
 * <ul>
 *   <li>案件→要員の照合: 推薦結果の1件目（{@code rank_no = 1}）が {@code ENGINEER} の run は、その要員が本人。
 *       2件目以降の要員は summary に含まれない第三者なので本人にしない。</li>
 *   <li>要員→案件の照合: run は source 要員の列を持たないため、{@code use_case = 'MATCHING'} の run について、
 *       記録時と同じ規則で {@code parameter_hash}（use_case・source 要員・source 案件・推薦結果の並び）を再計算し、
 *       一致した run を本人の run とする。{@code parameter_hash} は WHERE の照合にだけ使い、返さない。
 *       この照合のために、1件目が要員でない run の推薦結果（{@code t_ai_recommendation_item} の対象種別と ID）を読む。</li>
 * </ul>
 * どちらにも当たらない run（{@code MATCHING} 以外の use_case で source 要員を持つ run、source 要員と source 案件を
 * 両方持つ run、記録規則が変わった run）は帰属できない行として残る（plan §3.4.1 D6 の残余。DSAR では要手動確認）。
 * 記録規則との一致は {@code AiLogProviderTest} が記録側の計算と比べて固定する。
 * BP の推薦結果は summary に BP 本人の属性を含まないため導出に使わない。実行者（{@code actor_user_id}）は操作の主体で
 * あって内容の本人ではないため使わない。hash・費用・token 数・外部送信の claim 列は返さない。
 *
 * <p>legacy の {@code t_ai_log} は tenant 列・論理削除列を持たず、{@code request_params} の本人も導出できないため
 * 本 provider では扱わない（catalog では未登録。DATA_ELEMENT hold だけが対象）。
 */
@Component
public class AiLogProvider implements ProductionPrivacyProvider {

    public static final String PROVIDER_ID = "ai-log";
    public static final String REDACTED_SUMMARY = "AI_REDACTED_SUMMARY";
    /** source 要員を持つ run を記録する use_case（{@code AiApiController} の照合）。 */
    static final String SOURCE_ENGINEER_USE_CASE = "MATCHING";

    /** hash を IN で照合する1回あたりの件数。 */
    private static final int HASH_BATCH = 500;

    static final ProviderTable RUN = new ProviderTable("t_ai_recommendation_run", List.of(
            "id", "tenant_id", "trace_id", "use_case", "redacted_summary_json", "status", "created_at", "updated_at",
            "deleted_flag"), List.of());

    /** 要員→案件の照合で、parameter_hash の再計算に使う推薦結果（返さない）。 */
    static final ProviderTable ITEM = new ProviderTable("t_ai_recommendation_item", List.of(
            "id", "tenant_id", "run_id", "rank_no", "target_type", "target_id"), List.of());

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
                        "t_ai_recommendation_item.target_id(rank_no=1, target_type=ENGINEER)"),
                new SubjectLink(PrivacySubjectType.ENGINEER,
                        "source要員（use_case=MATCHING の parameter_hash を記録時の規則で再計算して照合）"))));
    }

    @Override
    public List<ProviderTable> tables() {
        return List.of(RUN, ITEM);
    }

    @Override
    public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
        ProviderRows.requireTenant(tenantId);
        if (subject.type() != PrivacySubjectType.ENGINEER) {
            return List.of();
        }
        Map<String, Object> params = ProviderRows.params(tenantId, subject);
        Map<Long, PrivacyRecord> byRun = new LinkedHashMap<>();
        // 案件→要員の照合: summary は1件目の要員のもの
        for (Map<String, Object> row : query.select(RUN, "tenant_id = :tenantId AND id IN (SELECT i.run_id "
                + "FROM t_ai_recommendation_item i WHERE i.tenant_id = :tenantId AND i.rank_no = 1 "
                + "AND i.target_type = 'ENGINEER' AND i.target_id = :subjectId)", params)) {
            PrivacyRecord record = ProviderRows.toRecord(PROVIDER_ID, RUN, row, List.of(REDACTED_SUMMARY), true);
            byRun.put(record.rowId(), record);
        }
        // 要員→案件の照合: source 要員を parameter_hash の再計算で照合する
        List<String> hashes = new ArrayList<>(expectedSourceEngineerHashes(tenantId, subject));
        for (int from = 0; from < hashes.size(); from += HASH_BATCH) {
            Map<String, Object> batch = new LinkedHashMap<>(params);
            batch.put("useCase", SOURCE_ENGINEER_USE_CASE);
            batch.put("hashes", hashes.subList(from, Math.min(hashes.size(), from + HASH_BATCH)));
            for (Map<String, Object> row : query.select(RUN, "tenant_id = :tenantId AND use_case = :useCase "
                    + "AND parameter_hash IN (:hashes)", batch)) {
                PrivacyRecord record = ProviderRows.toRecord(PROVIDER_ID, RUN, row, List.of(REDACTED_SUMMARY), true);
                byRun.putIfAbsent(record.rowId(), record);
            }
        }
        return new ArrayList<>(byRun.values());
    }

    /** 1件目が要員でない MATCHING run ごとに、subject を source 要員とした場合の parameter_hash。 */
    private Set<String> expectedSourceEngineerHashes(String tenantId, PrivacySubjectRef subject) {
        Map<String, Object> params = new LinkedHashMap<>(ProviderRows.params(tenantId, subject));
        params.put("useCase", SOURCE_ENGINEER_USE_CASE);
        Map<Long, List<Map<String, Object>>> itemsByRun = new LinkedHashMap<>();
        for (Map<String, Object> item : query.select(ITEM, "tenant_id = :tenantId AND run_id IN (SELECT f.run_id "
                + "FROM t_ai_recommendation_item f WHERE f.tenant_id = :tenantId AND f.rank_no = 1 "
                + "AND f.target_type <> 'ENGINEER') AND run_id IN (SELECT r.id FROM t_ai_recommendation_run r "
                + "WHERE r.tenant_id = :tenantId AND r.use_case = :useCase)", params)) {
            Long runId = ProviderRows.longValue(item.get("run_id"));
            if (runId != null) {
                itemsByRun.computeIfAbsent(runId, id -> new ArrayList<>()).add(item);
            }
        }
        Set<String> hashes = new LinkedHashSet<>();
        for (List<Map<String, Object>> items : itemsByRun.values()) {
            items.sort(Comparator.comparingLong(AiLogProvider::rank));
            hashes.add(sha256(canonicalParameters(SOURCE_ENGINEER_USE_CASE, subject.id(), items)));
        }
        return hashes;
    }

    /**
     * 記録時（{@code AiRecommendationRecorderImpl#canonicalParameters}）と同じ正規形。source 案件は要員→案件の照合では
     * 常に null。推薦結果は rank 順で、対象種別に応じて (要員, 案件, BP) の1つだけが値を持つ。
     */
    static String canonicalParameters(String useCase, long sourceEngineerId, List<Map<String, Object>> items) {
        StringBuilder value = new StringBuilder(useCase.trim())
                .append("|sourceEngineer=").append(sourceEngineerId)
                .append("|sourceProject=").append((Object) null);
        for (Map<String, Object> item : items) {
            String type = ProviderRows.text(item.get("target_type"));
            Long targetId = ProviderRows.longValue(item.get("target_id"));
            value.append("|result[").append(rank(item) - 1).append("]=")
                    .append("ENGINEER".equals(type) ? targetId : null).append(',')
                    .append("PROJECT".equals(type) ? targetId : null).append(',')
                    .append("BP".equals(type) ? targetId : null);
        }
        return value.toString();
    }

    private static long rank(Map<String, Object> item) {
        Long rank = ProviderRows.longValue(item.get("rank_no"));
        return rank == null ? Long.MAX_VALUE : rank;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 を利用できません", e);
        }
    }
}
