package com.ses.privacy.production.provider;

/**
 * DSAR で data element を交付するときの区分（hold の本人帰属とは別の宣言。NF07-F2 delta review 第4項）。
 *
 * <p>hold の帰属は広いほど安全（本人に関係し得る行を止める）だが、交付は第三者情報を出さない側に倒す。
 * そのため2つを別に宣言する。{@link #MIXED_THIRD_PARTY} の data element は、D-06 の redaction 規則が設定された後も
 * 自動 package に入れず、必ず人が確認する（{@code ThirdPartyRedactor} が固定する。PDF 等の file は項目単位で
 * redact できないため）。宣言の無い data element は {@link #MIXED_THIRD_PARTY} として扱う（fail-safe）。
 */
public enum DsarDeliveryClass {
    /** 本人の情報だけで構成される（D-06 の規則で本人の項目を自動交付し得る）。 */
    SELF,
    /** 第三者（顧客・担当者・BP 会社・作成者等）の情報が混在し得る。自動では交付しない。 */
    MIXED_THIRD_PARTY
}
