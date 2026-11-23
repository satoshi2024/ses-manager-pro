package com.ses.featureenablement.catalog;

import java.util.Objects;

/**
 * 開放単位が要する署名の種別（design §4.1）。条件付きの種別は、条件（例: 公開範囲の値が設定されている）が成り立つ時だけ要る。
 *
 * @param kindCode       署名の種別 code
 * @param whenSettingSet この設定の key に値がある時だけ要る（無条件なら null）。例: {@code NF02_PORTAL_INVENTORY} は
 *                       {@code nf02-customer-portal.visible-since} を設定する時だけ要る
 */
public record SignOffRequirement(String kindCode, String whenSettingSet) {

    public SignOffRequirement {
        Objects.requireNonNull(kindCode, "kindCode");
    }

    /** 無条件に要る種別。 */
    public static SignOffRequirement always(String kindCode) {
        return new SignOffRequirement(kindCode, null);
    }

    /** 設定に値がある時だけ要る種別。 */
    public static SignOffRequirement whenSet(String kindCode, String settingKey) {
        return new SignOffRequirement(kindCode, settingKey);
    }

    /** 条件付きか。 */
    public boolean conditional() {
        return whenSettingSet != null;
    }
}
