package com.ses.featureenablement.catalog;

import com.ses.config.feature.Feature;

import java.util.List;
import java.util.Objects;

/**
 * 副入口（開放単位の下で、別の条件で要求ごとに開閉する入口。requirements §3・§6.11。NF07 保持・保全の hold 開始・REL・REG）。
 * DB の開放状態を持たず、条件が戻れば自動で開く（D-4 の例外。J-03）。条件の判定は段階 C で足す。
 *
 * @param code         副入口の code
 * @param unit         属する開放単位
 * @param signOffKinds 副入口の条件の署名の種別（開放単位の条件ではない）
 */
public record Subgate(String code, Feature unit, List<String> signOffKinds) {

    public Subgate {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(unit, "unit");
        signOffKinds = List.copyOf(signOffKinds);
    }
}
