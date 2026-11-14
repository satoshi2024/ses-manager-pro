package com.ses.privacy.production.retention.catalog;

/**
 * data element の本人帰属の分類（{@code m_pii_data_element.subject_attribution}。production-bridge-phased-plan.md §3.4.3）。
 *
 * <p>C-2 の前提: 保存値が null・空・未知の綴り（大小文字違いを含む）の場合は全て {@link #UNKNOWN} として読み、
 * UNKNOWN は fail-closed（hold 機構を使った tenant では削除を止める）として扱う。UNKNOWN を「帰属できない」
 * （{@link #NOT_ATTRIBUTABLE}）と読み替えてはならない。DB 側も既定 {@code 'UNKNOWN'}・4値の CHECK を持つ（V190）。
 * 分類の変更（特に NOT_ATTRIBUTABLE / NO_PERSONAL_DATA への変更）は migration と Plan Review でだけ行う。
 */
public enum SubjectAttribution {
    /** 行ごとに本人を導出できる。SUBJECT hold に当たる行だけを止める。 */
    ATTRIBUTABLE,
    /** 本人を導出できない。DATA_ELEMENT hold があるときだけ止める。 */
    NOT_ATTRIBUTABLE,
    /** 個人データを含まない。hold の gate に接続しない。 */
    NO_PERSONAL_DATA,
    /** 未分類。fail-closed。 */
    UNKNOWN;

    /** 保存値を厳密に解釈する。完全一致しない値は全て {@link #UNKNOWN}。 */
    public static SubjectAttribution fromStored(String stored) {
        if (stored == null) {
            return UNKNOWN;
        }
        for (SubjectAttribution value : values()) {
            if (value.name().equals(stored)) {
                return value;
            }
        }
        return UNKNOWN;
    }

    /** 分類が確定しているか（UNKNOWN 以外）。 */
    public boolean isClassified() {
        return this != UNKNOWN;
    }
}
