package com.ses.privacy.production.retention.hold;

import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.privacy.production.provider.PrivacySubjectRef;
import com.ses.privacy.production.provider.ProductionPrivacyProvider;
import com.ses.privacy.production.provider.ProviderDataElement;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
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
