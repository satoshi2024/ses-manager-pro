package com.ses.privacy.production.provider;

import com.ses.privacy.production.retention.catalog.SubjectAttribution;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * provider が扱う data element（{@code m_pii_data_element.data_element_id}）の宣言。
 *
 * <p>{@code prefixMatch} が true の宣言は、その接頭辞で始まる data element のうち {@code excludedIds} 以外を扱う
 * （例: {@code DOCUMENT:} は、個別に宣言した文書種別を除いた {@code DOCUMENT:<document_type>}）。個別の宣言と
 * 接頭辞の宣言は {@code excludedIds} で重ならないようにする（registry は重なりを拒否する）。
 * provider が宣言する分類は UNKNOWN であってはならない（C-2。provider 登録時に拒否する）。
 * hold の本人帰属（{@code attribution}）と DSAR の交付区分（{@code deliveryClass}）は別に宣言する。
 *
 * @param dataElementId data element ID、または接頭辞
 * @param prefixMatch   接頭辞として照合するか
 * @param excludedIds   接頭辞の宣言から除く data element ID（個別に宣言したもの）
 * @param attribution   本人帰属の分類（catalog の seed と一致しなければ coverage report が不一致として報告する）
 * @param deliveryClass DSAR の交付区分
 * @param subjectLinks  本人の導出規則
 */
public record ProviderDataElement(String dataElementId, boolean prefixMatch, Set<String> excludedIds,
                                  SubjectAttribution attribution, DsarDeliveryClass deliveryClass,
                                  List<SubjectLink> subjectLinks) {

    public ProviderDataElement {
        if (dataElementId == null || dataElementId.isBlank()) {
            throw new IllegalArgumentException("data element ID が必要です");
        }
        excludedIds = Set.copyOf(Objects.requireNonNull(excludedIds, "除外の一覧が必要です"));
        if (!prefixMatch && !excludedIds.isEmpty()) {
            throw new IllegalArgumentException("除外は接頭辞の宣言にだけ置けます: " + dataElementId);
        }
        Objects.requireNonNull(attribution, "本人帰属の分類が必要です");
        Objects.requireNonNull(deliveryClass, "DSAR の交付区分が必要です");
        subjectLinks = List.copyOf(Objects.requireNonNull(subjectLinks, "本人の導出規則が必要です"));
        if (attribution == SubjectAttribution.ATTRIBUTABLE && subjectLinks.isEmpty()) {
            throw new IllegalArgumentException("ATTRIBUTABLE の data element は導出規則を持つ必要があります: "
                    + dataElementId);
        }
    }

    /** 1つの data element だけを扱う宣言（交付区分は第三者混在として扱う）。 */
    public static ProviderDataElement exact(String dataElementId, SubjectAttribution attribution,
                                            List<SubjectLink> subjectLinks) {
        return exact(dataElementId, attribution, DsarDeliveryClass.MIXED_THIRD_PARTY, subjectLinks);
    }

    /** 1つの data element だけを扱う宣言。 */
    public static ProviderDataElement exact(String dataElementId, SubjectAttribution attribution,
                                            DsarDeliveryClass deliveryClass, List<SubjectLink> subjectLinks) {
        return new ProviderDataElement(dataElementId, false, Set.of(), attribution, deliveryClass, subjectLinks);
    }

    /** 接頭辞で始まる data element を扱う宣言（除外なし、交付区分は第三者混在）。 */
    public static ProviderDataElement prefix(String prefix, SubjectAttribution attribution,
                                             List<SubjectLink> subjectLinks) {
        return prefix(prefix, Set.of(), attribution, DsarDeliveryClass.MIXED_THIRD_PARTY, subjectLinks);
    }

    /** 接頭辞で始まり、{@code excludedIds} を除く data element を扱う宣言。 */
    public static ProviderDataElement prefix(String prefix, Set<String> excludedIds, SubjectAttribution attribution,
                                             DsarDeliveryClass deliveryClass, List<SubjectLink> subjectLinks) {
        return new ProviderDataElement(prefix, true, excludedIds, attribution, deliveryClass, subjectLinks);
    }

    /** catalog の data element ID がこの宣言に当たるか（大小文字を区別する）。 */
    public boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        if (!prefixMatch) {
            return dataElementId.equals(candidate);
        }
        return candidate.startsWith(dataElementId) && candidate.length() > dataElementId.length()
                && !excludedIds.contains(candidate);
    }

    /** 2つの宣言が同じ data element を扱い得るか（provider 内・provider 間の重複検出に使う）。 */
    public boolean overlaps(ProviderDataElement other) {
        if (other == null) {
            return false;
        }
        if (prefixMatch && other.prefixMatch) {
            // 接頭辞どうしは除外を考えず重なりとみなす（保守側）
            return dataElementId.startsWith(other.dataElementId) || other.dataElementId.startsWith(dataElementId);
        }
        if (prefixMatch) {
            return matches(other.dataElementId);
        }
        if (other.prefixMatch) {
            return other.matches(dataElementId);
        }
        return dataElementId.equals(other.dataElementId);
    }
}
