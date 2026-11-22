package com.ses.featureenablement.service;

import java.util.Objects;

/**
 * 機能の開放の操作をする利用者（{@link FeatureEnablementActorPolicy} が session の種別と DB の有効な管理者を確かめた後の値）。
 * 監査・履歴・log には ID だけを書き、氏名を書かない（username は監査の {@code username} 列だけに使う）。
 *
 * <p>作れるのは同じ package の {@link FeatureEnablementActorPolicy#resolve}（session の種別の検査を通った値）だけで、controller 等が
 * 利用者 ID から直接作って break-glass・bootstrap・MFA の検査を飛ばせないようにする（review rb-fe-a1 P3-7）。service は受け取った
 * 値の利用者を、DB から有効な管理者か読み直して確かめる。</p>
 */
public final class FeatureEnablementActor {

    private final Long userId;
    private final String username;

    FeatureEnablementActor(Long userId, String username) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.username = username;
    }

    /** {@code sys_user.id}。 */
    public Long userId() {
        return userId;
    }

    /** {@code sys_user.username}。 */
    public String username() {
        return username;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FeatureEnablementActor actor && userId.equals(actor.userId)
                && Objects.equals(username, actor.username);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, username);
    }

    @Override
    public String toString() {
        return "FeatureEnablementActor[userId=" + userId + "]";
    }
}
