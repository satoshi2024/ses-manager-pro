package com.ses.service.security.epoch;

import java.util.Set;

/**
 * 認可述語が読む {@code m_system_config} キーの台帳（NF01細粒度epoch計画 §4.1、§4.5 U3、§12 O-1）。
 *
 * <p>ここに載るキーの値が変わると、全体epoch（{@link AuthorizationEpochAnchor#global()}）を同じtransactionで進める
 * （Q-3: system configの変更はglobal級の失効を受容する）。逆に、ここに載らないキー（勤怠同期のcursor等の定期書込み）
 * では全体epochを進めない。全キーで進めると、定期書込みのたびに未確認の提出がすべて失効するためである（O-1）。</p>
 *
 * <ul>
 *   <li>台帳のキーを書けるのは {@code SystemConfigServiceImpl.updateAll} だけで、全体epoch行を先にX lockしてから
 *       書く。{@code put} は同じtransactionで全体epoch行のX lockを取得済みでなければ台帳のキーを拒否する。</li>
 *   <li>認可判定クラスが新しいキーを読み始めたら、ここへ追加しなければ {@code AuthorizationConfigKeyFenceTest} が失敗する
 *       （漏れが許可ではなく失敗になる）。</li>
 * </ul>
 */
public final class AuthorizationConfigKeys {

    /** 営業ロールを担当データだけに絞る設定（{@code DataScopeServiceImpl#isSalesDataScoped}）。 */
    public static final String SALES_OWN_DATA_ONLY = "scope.sales-own-data-only";

    /** 台帳（初期値は {@link #SALES_OWN_DATA_ONLY} だけ）。 */
    public static final Set<String> KEYS = Set.of(SALES_OWN_DATA_ONLY);

    private AuthorizationConfigKeys() {
    }

    /** 認可述語が読むキーか。{@code null} は台帳に含まれない。 */
    public static boolean contains(String key) {
        return key != null && KEYS.contains(key);
    }
}
