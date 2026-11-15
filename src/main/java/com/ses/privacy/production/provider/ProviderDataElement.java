package com.ses.privacy.production.provider;

import com.ses.privacy.production.retention.catalog.SubjectAttribution;

import java.util.List;
import java.util.Objects;

/**
 * provider が扱う data element（{@code m_pii_data_element.data_element_id}）の宣言。
 *
 * <p>{@code prefixMatch} が true の宣言は、その接頭辞で始まる全ての data element を扱う
 * （例: {@code DOCUMENT:} は文書種別ごとの {@code DOCUMENT:<document_type>}）。
 * provider が宣言する分類は UNKNOWN であってはならない（C-2。provider 登録時に拒否する）。
 *
 * @param dataElementId data element ID、または接頭辞
 * @param prefixMatch   接頭辞として照合するか
 * @param attribution   本人帰属の分類（catalog の seed と一致しなければ coverage report が不一致として報告する）
 * @param subjectLinks  本人の導出規則
 */
public record ProviderDataElement(String dataElementId, boolean prefixMatch, SubjectAttribution attribution,
                                  List<SubjectLink> subjectLinks) {

    public ProviderDataElement {
        if (dataElementId == null || dataElementId.isBlank()) {
            throw new IllegalArgumentException("data element ID が必要です");
        }
        Objects.requireNonNull(attribution, "本人帰属の分類が必要です");
        subjectLinks = List.copyOf(Objects.requireNonNull(subjectLinks, "本人の導出規則が必要です"));
        if (attribution == SubjectAttribution.ATTRIBUTABLE && subjectLinks.isEmpty()) {
            throw new IllegalArgumentException("ATTRIBUTABLE の data element は導出規則を持つ必要があります: "
                    + dataElementId);
        }
    }

    /** 1つの data element だけを扱う宣言。 */
    public static ProviderDataElement exact(String dataElementId, SubjectAttribution attribution,
                                            List<SubjectLink> subjectLinks) {
        return new ProviderDataElement(dataElementId, false, attribution, subjectLinks);
    }

    /** 接頭辞で始まる data element を扱う宣言。 */
    public static ProviderDataElement prefix(String prefix, SubjectAttribution attribution,
                                             List<SubjectLink> subjectLinks) {
        return new ProviderDataElement(prefix, true, attribution, subjectLinks);
    }

    /** catalog の data element ID がこの宣言に当たるか（大小文字を区別する）。 */
    public boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return prefixMatch ? candidate.startsWith(dataElementId) && candidate.length() > dataElementId.length()
                : dataElementId.equals(candidate);
    }

    /** 2つの宣言が同じ data element を扱い得るか（provider 間の重複検出に使う）。 */
    public boolean overlaps(ProviderDataElement other) {
        if (other == null) {
            return false;
        }
        if (prefixMatch && other.prefixMatch) {
            return dataElementId.startsWith(other.dataElementId) || other.dataElementId.startsWith(dataElementId);
        }
        if (prefixMatch) {
            return matches(other.dataElementId) || dataElementId.equals(other.dataElementId);
        }
        if (other.prefixMatch) {
            return other.matches(dataElementId) || dataElementId.equals(other.dataElementId);
        }
        return dataElementId.equals(other.dataElementId);
    }
}
