package com.ses.privacy.production.provider;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NF07 F2 の本番 read-only provider（production-bridge-phased-plan.md §3.2）。
 *
 * <p>DSAR 検索・dry-run・hold の帰属判定の母集団を作る。実装は次を守る。
 * <ul>
 *   <li>DB へは {@link PrivacyReadOnlyQuery} だけで接続し、SELECT だけを行う（書込み・外部呼出しの部品を持たない。
 *       {@code ProductionPrivacyProviderReadOnlyStructureTest} が構造で固定する）。</li>
 *   <li>読む列は {@link #tables()} の allow-list だけ（{@code SELECT *} を使わない。秘密列は登録時に拒否する）。</li>
 *   <li>tenant は呼出し側が明示し、tenant を特定できない行（tenant 列が NULL の行）は返さない。</li>
 *   <li>論理削除済みの行も返す（実体が残るため。hold で守る保存名の解決にも使う）。</li>
 *   <li>本人の種別をまたいだ自動の名寄せをしない（扱わない種別の subject には空を返す）。</li>
 * </ul>
 * 合成層の {@code PrivacyProviderRegistry} / {@code PrivacyProvider} とは別の型である（I-6）。
 */
public interface ProductionPrivacyProvider {

    /** provider ID（{@code m_pii_data_element.provider_id} と一致する）。 */
    String providerId();

    /** 扱う data element と本人の導出規則。 */
    List<ProviderDataElement> dataElements();

    /** 読む表と列の allow-list。 */
    List<ProviderTable> tables();

    /**
     * subject に帰属する行（論理削除済みを含む）を返す。
     *
     * @param tenantId 照会する tenant（必須）
     * @param subject  本人
     * @return allow-list の列だけを持つ行。扱わない種別の subject には空
     */
    List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject);

    /** subject に帰属する保存名（論理削除済みの行を含む）。{@code PrivacyHoldFileReferenceProvider}（A1H）が使う。 */
    default Set<String> resolveStoredNames(String tenantId, PrivacySubjectRef subject) {
        Set<String> names = new LinkedHashSet<>();
        for (PrivacyRecord record : findBySubject(tenantId, subject)) {
            names.addAll(record.storedNames());
        }
        return names;
    }

    /** 表ごとの列 allow-list（表示・検査用）。 */
    default Map<String, List<String>> columnAllowList() {
        Map<String, List<String>> allowList = new LinkedHashMap<>();
        for (ProviderTable table : tables()) {
            allowList.put(table.table(), table.columns());
        }
        return allowList;
    }
}
