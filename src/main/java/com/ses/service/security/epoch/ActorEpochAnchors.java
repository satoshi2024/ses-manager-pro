package com.ses.service.security.epoch;

import com.ses.common.exception.BusinessException;
import com.ses.entity.SysUser;
import lombok.extern.slf4j.Slf4j;

/**
 * 利用者（{@code sys_user}行）のactor epoch anchorを、同じtransactionでlocking readした行から作る（計画 §2.2、§4.1）。
 *
 * <p>tenantは要求・session・設定値ではなく、必ずDBから読んだ行の {@code tenant_id} を使う（W3-10 review P3-5:
 * 大文字小文字の違う文字列で同じ行を別anchorと扱い、S→X昇格や重複増分を起こさないため）。行のIDかtenantが
 * 読めない場合は世代を進められないため、業務更新ごと409 {@code error.authorization.epochUnavailable} で止める。</p>
 */
@Slf4j
public final class ActorEpochAnchors {

    private ActorEpochAnchors() {
    }

    /**
     * locking read（{@code FOR UPDATE}）済みの利用者行からanchorを作る。
     *
     * @throws BusinessException 409 行・ID・tenantが無い場合
     */
    public static AuthorizationEpochAnchor.Actor of(SysUser lockedRow) {
        if (lockedRow == null || lockedRow.getId() == null || lockedRow.getId() <= 0
                || lockedRow.getTenantId() == null || lockedRow.getTenantId().isBlank()) {
            log.warn("[authorization epoch] 利用者行のIDまたはtenantを読めないためactor epochを進められません: id={}",
                    lockedRow == null ? null : lockedRow.getId());
            throw BusinessException.of(409, AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY);
        }
        return AuthorizationEpochAnchor.actor(lockedRow.getTenantId(), lockedRow.getId());
    }
}
