package com.ses.privacy.production.retention.hold;

import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.privacy.production.provider.AiLogProvider;
import com.ses.privacy.production.provider.DocumentFileProvider;
import com.ses.privacy.production.provider.EngineerProfileProvider;
import com.ses.privacy.production.provider.IngestionTextProvider;
import com.ses.privacy.production.provider.PrivacyRecord;
import com.ses.privacy.production.provider.PrivacySubjectRef;
import com.ses.privacy.production.provider.ProductionPrivacyProvider;
import com.ses.privacy.production.provider.ProviderDataElement;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * hold の帰属判定の入力（production-bridge-phased-plan.md §3.4.1・§3.4.3。F2 の provider と catalog を使う）。
 *
 * <p>規則5（帰属できない data element）の入力は catalog の {@code subject_attribution} だけにする（F2 delta Review P2-B）。
 * ただし provider の宣言と食い違う行（coverage report の {@code ATTRIBUTION_MISMATCH} と同じ条件）と、ATTRIBUTABLE なのに
 * 本人を導出する provider の宣言が無い行は {@link SubjectAttribution#UNKNOWN} として扱う（fail-closed）。catalog に行が
 * 無い element（{@code CATALOG_MISSING}）・論理削除された行も UNKNOWN（F2 Review P3-6）。
 *
 * <p>catalog は JDBC で読む（gate が lock の後に呼んでも MyBatis の session cache を使わない。F2 Review §7 (a)）。
 * 件数は tenant 内の行数だけを返し（subject ごとではない）、行の内容は読まない。件数を照会する手段を持たない element は
 * null（画面は「件数不明」と表示する）。破壊的経路ごとの本人導出（A1H-2）と保存名の解決は provider に委ねる。
 */
public class HoldSubjectResolver {

    static final String DOCUMENT_PREFIX = "DOCUMENT:";
    /** 破壊的経路が行を照合する表（F2 provider の {@code ProviderTable} と同じ名前）。 */
    public static final String DOCUMENT_TABLE = "t_document";
    public static final String RESUME_INGESTION_TABLE = "t_resume_ingestion";
    public static final String BP_INGESTION_TABLE = "t_bp_availability_ingestion";
    public static final String AI_RUN_TABLE = "t_ai_recommendation_run";
    static final String FILE_OBJECT = "FILE_OBJECT";
    /** catalog の {@code storage_ref} が application の表の列であることを示す接頭辞（V196・V197 は全て {@code t_<表>.<列>}）。 */
    static final String APPLICATION_TABLE_PREFIX = "t_";
    /** provider の無い取込原本（catalog は UNKNOWN。DATA_ELEMENT hold の保存先だけを解決する）。 */
    static final String PROJECT_INGESTION_ORIGINAL = "PROJECT_INGESTION_ORIGINAL";
    /** 機能の開放の署名の文書（V203。保存名は {@code t_feature_sign_off.stored_name}。消去の経路 D17。review rb-fe-a1 P2-1）。 */
    public static final String FEATURE_SIGN_OFF_DOCUMENT = "FEATURE_SIGN_OFF_DOCUMENT";

    private final JdbcTemplate jdbc;
    private final ProductionPrivacyProviderRegistry registry;

    public HoldSubjectResolver(JdbcTemplate jdbc, ProductionPrivacyProviderRegistry registry) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** tenant の実効の分類（data element ID → 分類。宣言と食い違う行は UNKNOWN）。 */
    public Map<String, SubjectAttribution> catalog(String tenantId) {
        Map<String, SubjectAttribution> result = new LinkedHashMap<>();
        jdbc.query("SELECT data_element_id, provider_id, subject_attribution FROM m_pii_data_element "
                        + "WHERE tenant_id = ? AND deleted_flag = 0 ORDER BY data_element_id",
                rs -> {
                    String elementId = rs.getString(1);
                    result.put(elementId, effective(elementId, rs.getString(2),
                            SubjectAttribution.fromStored(rs.getString(3))));
                }, tenantId);
        return Collections.unmodifiableMap(result);
    }

    /** 分類（catalog に無ければ UNKNOWN）。 */
    public static SubjectAttribution attributionOf(Map<String, SubjectAttribution> catalog, String dataElementId) {
        if (catalog == null || dataElementId == null) {
            return SubjectAttribution.UNKNOWN;
        }
        return catalog.getOrDefault(dataElementId, SubjectAttribution.UNKNOWN);
    }

    /** SUBJECT hold の開始時に選択を要する element（NOT_ATTRIBUTABLE）の ID（昇順）。 */
    public static List<String> unattributableElementIds(Map<String, SubjectAttribution> catalog) {
        List<String> ids = new ArrayList<>();
        catalog.forEach((id, attribution) -> {
            if (attribution == SubjectAttribution.NOT_ATTRIBUTABLE) {
                ids.add(id);
            }
        });
        ids.sort(null);
        return List.copyOf(ids);
    }

    /** 未分類（UNKNOWN）の element の ID（昇順。hold 機構を使った tenant では接続した経路の削除が止まる）。 */
    public static List<String> unclassifiedElementIds(Map<String, SubjectAttribution> catalog) {
        List<String> ids = new ArrayList<>();
        catalog.forEach((id, attribution) -> {
            if (attribution == SubjectAttribution.UNKNOWN) {
                ids.add(id);
            }
        });
        ids.sort(null);
        return List.copyOf(ids);
    }

    /**
     * tenant 内の行数（subject ごとではない）。文書種別（{@code DOCUMENT:<種別>}）は文書台帳の行数（論理削除済みを含む。
     * 実体が残るため）。それ以外は照会の手段を持たないため null。
     */
    public Long tenantRowCount(String tenantId, String dataElementId) {
        if (dataElementId == null || !dataElementId.startsWith(DOCUMENT_PREFIX)) {
            return null;
        }
        String documentType = dataElementId.substring(DOCUMENT_PREFIX.length());
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_document WHERE tenant_id = ? AND document_type = ?",
                Long.class, tenantId, documentType);
    }

    /**
     * subject に帰属する保存名（論理削除済みの行を含む。F2 provider の和集合）。A1H-2 の
     * {@code PrivacyHoldFileReferenceProvider} が使う。provider の例外はそのまま返す（呼出し側が fail-closed にする）。
     */
    public Set<String> resolveStoredNames(String tenantId, PrivacySubjectRef subject) {
        Set<String> names = new LinkedHashSet<>();
        for (ProductionPrivacyProvider provider : registry.providers()) {
            names.addAll(provider.resolveStoredNames(tenantId, subject));
        }
        return Collections.unmodifiableSet(names);
    }

    // ---- 破壊的経路ごとの本人導出（A1H-2。§3.4.1 の表。規則は F2 provider の宣言そのものを使い、ここで書き直さない） ----

    /** D1〜D3: 文書（{@code t_document}）。{@link DocumentFileProvider} の link・逆参照の和集合（plan §3.4.1「D1〜D3 の本人導出」）。 */
    public Map<Long, Set<String>> documentSubjects(String tenantId, Collection<String> heldSubjects) {
        return heldSubjectsByRow(tenantId, heldSubjects, DocumentFileProvider.PROVIDER_ID, DOCUMENT_TABLE);
    }

    /** D4: 履歴取込（{@code t_resume_ingestion}）。{@link IngestionTextProvider} の {@code converted_engineer_id} と {@code candidate_id} の和集合。 */
    public Map<Long, Set<String>> resumeIngestionSubjects(String tenantId, Collection<String> heldSubjects) {
        return heldSubjectsByRow(tenantId, heldSubjects, IngestionTextProvider.PROVIDER_ID, RESUME_INGESTION_TABLE);
    }

    /** D5: BP 空き取込（{@code t_bp_availability_ingestion}）。{@link IngestionTextProvider} の {@code converted_availability_id}。 */
    public Map<Long, Set<String>> bpAvailabilityIngestionSubjects(String tenantId, Collection<String> heldSubjects) {
        return heldSubjectsByRow(tenantId, heldSubjects, IngestionTextProvider.PROVIDER_ID, BP_INGESTION_TABLE);
    }

    /** D6: AI 推薦の run（{@code t_ai_recommendation_run}）。{@link AiLogProvider} の rank 1 の要員と source 要員の照合。 */
    public Map<Long, Set<String>> aiRecommendationRunSubjects(String tenantId, Collection<String> heldSubjects) {
        return heldSubjectsByRow(tenantId, heldSubjects, AiLogProvider.PROVIDER_ID, AI_RUN_TABLE);
    }

    /**
     * held な subject ごとに provider の {@code findBySubject}（論理削除済みを含む）を引き、表の行 ID → その行に帰属する held な
     * subject を返す（行から本人を逆に導く規則を別に書かないため、subject → 行の向きで provider の宣言をそのまま使う）。
     * 返らない行は「どの held subject にも帰属しない」（帰属できない行を含む）。subject 参照を解釈できない・provider が
     * 未登録・照会に失敗した場合は例外（呼出し側が経路のその回を UNAVAILABLE として扱う）。
     */
    public Map<Long, Set<String>> heldSubjectsByRow(String tenantId, Collection<String> heldSubjects, String providerId,
                                                    String table) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (heldSubjects == null || heldSubjects.isEmpty()) {
            return Map.of();
        }
        ProductionPrivacyProvider provider = registry.require(providerId);
        Map<Long, Set<String>> byRow = new LinkedHashMap<>();
        for (String held : heldSubjects) {
            PrivacySubjectRef subject = PrivacySubjectRef.parse(held)
                    .orElseThrow(() -> new IllegalStateException("hold の subject 参照を解釈できません"));
            for (PrivacyRecord record : provider.findBySubject(tenantId, subject)) {
                if (table.equals(record.table())) {
                    byRow.computeIfAbsent(record.rowId(), id -> new LinkedHashSet<>()).add(subject.ref());
                }
            }
        }
        Map<Long, Set<String>> result = new LinkedHashMap<>();
        byRow.forEach((id, subjects) -> result.put(id, Collections.unmodifiableSet(subjects)));
        return Collections.unmodifiableMap(result);
    }

    /**
     * DATA_ELEMENT hold の element に属する保存名（D10。論理削除済みの行・状態を問わない）。
     * <ul>
     *   <li>実体 file を持たない element（catalog の {@code storage_kind} が {@code FILE_OBJECT} 以外）は空。</li>
     *   <li>{@code FILE_OBJECT} でも保存先が application の表の列でない element（{@code storage_ref} が {@code t_} で
     *       始まらない。CloudSign・freee・mailbox 等の外部 service 側の写し）は、upload root に実体を持たず孤児清掃の
     *       対象にならないため、空で {@link ElementStoredNames#outsideUploadRoot()} を立てて返す（清掃を止めても守れる
     *       ものが無い。A1H-2 review P2-1）。呼出し側は固定 code の WARN と metric を出す。</li>
     *   <li>catalog に行が無い element と、保存先が表の列なのに解決の規則を持たない element は例外（呼出し側は孤児清掃の
     *       その回を中止する）。</li>
     * </ul>
     * 保存先は catalog の {@code storage_ref}（V196・V197）と F2 provider の {@code storedNameColumns} と同じ列。
     */
    public ElementStoredNames resolveElementStoredNames(String tenantId, String dataElementId) {
        Objects.requireNonNull(tenantId, "tenantId");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT storage_kind, storage_ref FROM m_pii_data_element "
                + "WHERE tenant_id = ? AND data_element_id = ? AND deleted_flag = 0", tenantId, dataElementId);
        if (rows.size() != 1) {
            throw new IllegalStateException("hold の data element が catalog に1行ありません");
        }
        Object storageKind = rows.get(0).get("storage_kind");
        Object storageRef = rows.get(0).get("storage_ref");
        if (!FILE_OBJECT.equals(storageKind == null ? null : storageKind.toString())) {
            return ElementStoredNames.none();
        }
        if (storageRef == null || !storageRef.toString().trim().startsWith(APPLICATION_TABLE_PREFIX)) {
            return ElementStoredNames.outside();
        }
        List<String> names;
        if (dataElementId.startsWith(DOCUMENT_PREFIX)) {
            names = jdbc.queryForList("SELECT v.storage_key FROM t_document_version v WHERE v.tenant_id = ? "
                    + "AND v.document_id IN (SELECT d.id FROM t_document d WHERE d.tenant_id = ? AND d.document_type = ?)",
                    String.class, tenantId, tenantId, dataElementId.substring(DOCUMENT_PREFIX.length()));
        } else if (IngestionTextProvider.RESUME_ORIGINAL.equals(dataElementId)) {
            names = jdbc.queryForList("SELECT stored_file_name FROM t_resume_ingestion WHERE tenant_id = ? "
                    + "AND stored_file_name IS NOT NULL", String.class, tenantId);
        } else if (IngestionTextProvider.BP_ORIGINAL.equals(dataElementId)) {
            // 表に tenant 列が無い。F2 provider（変換先の tenant）と清掃（作成者の tenant）の両方の境界を和集合で取る
            names = jdbc.queryForList("SELECT b.stored_file_name FROM t_bp_availability_ingestion b "
                    + "WHERE b.stored_file_name IS NOT NULL AND (b.file_ext IS NULL OR b.file_ext <> 'PASTE') "
                    + "AND (b.converted_availability_id IN (SELECT a.id FROM t_bp_availability a WHERE a.tenant_id = ?) "
                    + "OR b.created_by IN (SELECT u.id FROM sys_user u WHERE u.tenant_id = ?))",
                    String.class, tenantId, tenantId);
        } else if (EngineerProfileProvider.PHOTO.equals(dataElementId)) {
            names = jdbc.queryForList("SELECT photo_url FROM t_engineer WHERE tenant_id = ? AND photo_url IS NOT NULL",
                    String.class, tenantId);
        } else if (FEATURE_SIGN_OFF_DOCUMENT.equals(dataElementId)) {
            // 状態に依らず保存名の残る全ての行（PURGED は保存名が NULL）。DATA_ELEMENT hold の間は消去（D17）も止まる
            names = jdbc.queryForList("SELECT stored_name FROM t_feature_sign_off WHERE tenant_id = ? "
                    + "AND stored_name IS NOT NULL", String.class, tenantId);
        } else if (PROJECT_INGESTION_ORIGINAL.equals(dataElementId)) {
            names = jdbc.queryForList("SELECT stored_file_name FROM t_project_ingestion WHERE tenant_id = ? "
                    + "AND stored_file_name IS NOT NULL", String.class, tenantId);
        } else {
            throw new IllegalStateException("hold の data element の保存先を解決できません");
        }
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                result.add(name.trim());
            }
        }
        return new ElementStoredNames(result, false);
    }

    /**
     * DATA_ELEMENT hold の element の保存名。
     *
     * @param storedNames       upload root にある実体の保存名
     * @param outsideUploadRoot 実体が application の外（外部 service 側）にあり、孤児清掃では守れも消せもしない
     */
    public record ElementStoredNames(Set<String> storedNames, boolean outsideUploadRoot) {
        public ElementStoredNames {
            storedNames = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(storedNames, "storedNames")));
        }

        static ElementStoredNames none() {
            return new ElementStoredNames(Set.of(), false);
        }

        static ElementStoredNames outside() {
            return new ElementStoredNames(Set.of(), true);
        }
    }

    private SubjectAttribution effective(String elementId, String providerId, SubjectAttribution stored) {
        if (stored == SubjectAttribution.UNKNOWN) {
            return stored;
        }
        Optional<ProviderDataElement> declaration = providerId == null ? Optional.empty()
                : registry.find(providerId).flatMap(provider -> ProductionPrivacyProviderRegistry.declaration(provider, elementId));
        if (declaration.isPresent()) {
            return declaration.get().attribution() == stored ? stored : SubjectAttribution.UNKNOWN;
        }
        // 本人を導出する宣言が無い ATTRIBUTABLE は導出できないため fail-closed。宣言の無い残余（D7・D9）はそのまま
        return stored == SubjectAttribution.ATTRIBUTABLE ? SubjectAttribution.UNKNOWN : stored;
    }
}
