package com.ses.privacy.production.provider;

import java.util.Objects;

/**
 * data element の行から本人を導出する規則の1項（宣言。catalog API で表示し、A1H の帰属判定が従う）。
 *
 * <p>1つの data element が複数の項を持つ場合は和集合として扱う（どれか1つでも hold 中の本人に当たれば、
 * その行は hold の対象）。導出に使う列が NULL の行は帰属できない行として扱う（§3.4.3 規則4）。
 *
 * @param type   導出される本人の種別
 * @param source 導出元（例: {@code t_resume_ingestion.converted_engineer_id}）
 */
public record SubjectLink(PrivacySubjectType type, String source) {

    public SubjectLink {
        Objects.requireNonNull(type, "本人の種別が必要です");
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("導出元が必要です");
        }
    }
}
