package com.ses.featureenablement.service;

/**
 * 試験の行為者（{@link FeatureEnablementActor} は本番では {@link FeatureEnablementActorPolicy#resolve} だけが作る。review rb-fe-a1
 * P3-7）。試験は DB に入れた有効な管理者の ID から作り、service が DB から読み直して確かめる。
 */
public final class FeatureEnablementActors {

    private FeatureEnablementActors() {
    }

    public static FeatureEnablementActor of(Long userId, String username) {
        return new FeatureEnablementActor(userId, username);
    }
}
