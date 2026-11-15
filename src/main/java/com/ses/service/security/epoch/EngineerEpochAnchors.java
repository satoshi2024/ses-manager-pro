package com.ses.service.security.epoch;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import lombok.extern.slf4j.Slf4j;

/**
 * 要員（{@code t_engineer}行）のengineer epoch anchorを、同じtransactionでlocking readした行から作る（計画 §2.2、§4.2）。
 *
 * <p>tenantは要求・tenant context・設定値ではなく、必ずDBから読んだ行の {@code tenant_id} を使う（W3-10 review P3-5:
 * 表記の違う文字列で同じ行を別anchorと扱い、重複増分やS→X昇格を起こさないため）。行のIDかtenantが読めない場合
 * （tenant未解決のlegacy行を含む）は世代を進められないため、業務更新ごと409 {@code error.authorization.epochUnavailable}
 * で止める（計画 §2.6 の fail-closed）。</p>
 */
@Slf4j
public final class EngineerEpochAnchors {

    private EngineerEpochAnchors() {
    }

    /**
     * locking read（{@code FOR UPDATE}）済みの要員行からanchorを作る。
     *
     * @throws BusinessException 409 行・ID・tenantが無い場合
     */
    public static AuthorizationEpochAnchor.Engineer of(Engineer lockedRow) {
        if (lockedRow == null || lockedRow.getId() == null || lockedRow.getId() <= 0
                || lockedRow.getTenantId() == null || lockedRow.getTenantId().isBlank()) {
            log.warn("[authorization epoch] 要員行のIDまたはtenantを読めないためengineer epochを進められません: id={}",
                    lockedRow == null ? null : lockedRow.getId());
            throw BusinessException.of(409, AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY);
        }
        return AuthorizationEpochAnchor.engineer(lockedRow.getTenantId(), lockedRow.getId());
    }
}
