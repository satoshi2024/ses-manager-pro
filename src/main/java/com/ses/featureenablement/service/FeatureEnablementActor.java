package com.ses.featureenablement.service;

import java.util.Objects;

/**
 * 機能の開放の操作をする利用者（{@link FeatureEnablementActorPolicy} が session の種別と DB の有効な管理者を確かめた後の値）。
 * 監査・履歴・log には ID だけを書き、氏名を書かない（username は監査の {@code username} 列だけに使う）。
 *
 * @param userId   {@code sys_user.id}
 * @param username {@code sys_user.username}
 */
public record FeatureEnablementActor(Long userId, String username) {

    public FeatureEnablementActor {
        Objects.requireNonNull(userId, "userId");
    }
}
