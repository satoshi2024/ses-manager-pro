package com.ses.featureenablement.catalog;

/**
 * 署名の種別の類（feature-enablement-console design §6.3・§18 J-08。統括の決定で (b)。暫定で、最終は各 owner と Privacy が確認する）。
 * 有効期限の上限を類で分ける。証拠の類だけが、同じ文書・同じ署名日のまま二人の再確認で延長できる（design §6.5）。
 */
public enum SignOffKindClass {

    /** 決定の類（署名者の決定そのもの。NF07 の D-xx、NF06 の admission、NF05 の DG-05 等）。上限 366 日。 */
    DECISION(366, false),

    /** 証拠の類（受理・実測・review の PASS・手動の確認）。上限 1,095 日（3年）で延長できる。 */
    EVIDENCE(1095, true);

    private final int maxValidityDays;
    private final boolean extendable;

    SignOffKindClass(int maxValidityDays, boolean extendable) {
        this.maxValidityDays = maxValidityDays;
        this.extendable = extendable;
    }

    /** 署名日（延長は承認の日）から失効日までの日数の上限。 */
    public int maxValidityDays() {
        return maxValidityDays;
    }

    /** 延長（{@code SIGN_OFF_EXTENSION}）できるか。 */
    public boolean extendable() {
        return extendable;
    }
}
