package com.ses.featureenablement.catalog;

import java.util.Objects;

/**
 * 開放単位の条件になる配備の設定の鍵（secret。design §4.1）。目録は名前と property だけを持ち、値は持たない・返さない。
 * 鍵の形式の判定は既存の部品（段階 B〜D の評価器）が行い、共通の評価器は有無だけを見る。
 *
 * @param name     鍵の名前（環境変数名。画面と理由の code に使う）
 * @param property 値を読む property の名前（有無だけを見る）
 */
public record KeyRequirement(String name, String property) {

    public KeyRequirement {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(property, "property");
    }
}
