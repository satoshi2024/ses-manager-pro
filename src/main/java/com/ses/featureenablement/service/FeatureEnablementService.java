package com.ses.featureenablement.service;

import com.ses.config.feature.Feature;
import com.ses.entity.FeatureEnablementRequest;

/**
 * 機能の開放の申請・承認・却下・取下げ・閉鎖・自動閉鎖（feature-enablement-console design §5）。全ての書込みは世代の行を最初に
 * lock し、同じ transaction の中で読み直した状態で条件を評価する（READ COMMITTED）。
 */
public interface FeatureEnablementService {

    /** 申請の期限（J-07。7日）。 */
    java.time.Duration REQUEST_TTL = java.time.Duration.ofDays(7);

    /** 開放を申請する（条件が全て成り立つ時だけ。同じ対象の期限切れの未決を先に EXPIRED にする）。 */
    FeatureEnablementRequest requestOpen(Feature feature, String reason, FeatureEnablementActor actor);

    /** 承認する（申請者以外。STALE・EXPIRED・条件の不成立は commit してから結果の値で返す）。 */
    FeatureEnablementApprovalResult approve(long requestId, int expectedVersion, String reason,
                                            FeatureEnablementActor actor);

    /** 却下する（申請者以外。理由が必須）。 */
    void reject(long requestId, int expectedVersion, String reason, FeatureEnablementActor actor);

    /** 取り下げる（申請者だけ）。 */
    void withdraw(long requestId, int expectedVersion, FeatureEnablementActor actor);

    /** 閉じる（管理者1名。理由が必須。子を AUTO_CLOSED(PARENT_CLOSED)、未決を CANCELLED、全ての管理者へ通知）。 */
    void close(Feature feature, String reason, FeatureEnablementActor actor);

    /**
     * system が自動閉鎖する（起動時・定期の評価。開いていなければ何もしない）。
     *
     * @param startup 起動時の評価か（監査の経路の区別）
     * @return 閉じたか（既に閉じていれば false）
     */
    boolean autoClose(Feature feature, String reasonCode, boolean startup);

    /** 期限を過ぎた未決の申請を EXPIRED にする（日の始まりの処理）。終えた件数。 */
    int expirePendingRequests();
}
