package com.ses.featureenablement.service;

import com.ses.entity.FeatureSignOffBundle;

import java.util.List;
import java.util.Map;

/**
 * 署名の組（{@code t_feature_sign_off_bundle}）の登録・確認・却下・取下げ・取消し（feature-enablement-console design §2.6.1・
 * §6.5）。組の種類に依らない（NF07 の組の種類の整合の検査は {@link FeatureSignOffBundleConsistencyCheck} の実装で足す）。
 */
public interface FeatureSignOffBundleService {

    /** 組の新しい member（文書つきの署名）。 */
    record NewMember(FeatureSignOffService.Registration registration, byte[] document, String originalName) {
    }

    /**
     * 組を登録する（組と新しい署名は {@code REGISTERED}）。member は新しい署名と、引き継ぐ確認済みの署名（種別 → 署名 ID）で、
     * 組の種類の必須の member を全て含み、組の種類に無い種別を含まない。
     */
    FeatureSignOffBundle register(String bundleKind, List<NewMember> newMembers, Map<String, Long> carriedOver,
                                  FeatureEnablementActor actor);

    /**
     * 登録者以外が組を1回で確認する。旧い組と置き換わる署名を {@code SUPERSEDED}、新しい署名と組を {@code CONFIRMED} にし、それを
     * 要する開いている開放単位を評価し直す（同じ transaction）。
     */
    FeatureSignOffBundle confirm(long bundleId, int expectedVersion, FeatureEnablementActor actor);

    /** 未確認の組を登録者以外が却下する（新しい署名も却下する）。 */
    void reject(long bundleId, int expectedVersion, String reasonCode, FeatureEnablementActor actor);

    /** 未確認の組を登録者が取り下げる。 */
    void withdraw(long bundleId, int expectedVersion, FeatureEnablementActor actor);

    /** 確認済みの組を取り消す（組の種類を要する開いている開放単位を {@code AUTO_CLOSED(SIGN_OFF_REVOKED)}）。 */
    void revoke(long bundleId, int expectedVersion, String reasonCode, FeatureEnablementActor actor);
}
