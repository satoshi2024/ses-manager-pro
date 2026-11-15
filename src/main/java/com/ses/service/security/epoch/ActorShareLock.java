package com.ses.service.security.epoch;

import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * NF01 lock rank 3（利用者行）の共有lock（計画 §3.4「営業割当・account link | 3 S{営業ユーザー/連携ユーザー} → 7 X」）。
 *
 * <p>営業割当・account linkのwriterは、要員行（rank 7）をX lockする前に、相手の利用者行を共有lockしてから有効性を
 * 読み直す。利用者の無効化・role変更・削除（rank 3 X、W3-15）と直列化し、非lockの読取で「有効」と判定した直後に
 * 削除・無効化された利用者へ割当・紐付けを作らないため（W3-15 review P3-8: {@code deleteUser} の「連携中ユーザーの
 * 削除拒否」と並行する {@code link} が同時に成立し得た）。所属writerが連携要員を見つける際の安定化にも使う（§4.2）。
 * 共有lockなので、同じ利用者を提出者・確認者とするCONFIRMのreader（rank 3 S）や、同じ営業を別の要員へ割り当てる
 * writer同士は待たない（細粒度、M4）。</p>
 *
 * <p>H2は {@code FOR SHARE} を解釈しない（計画 §2.5）。判定は {@link SharedLockSupport} が行い、H2では同じ行・同じrankの
 * {@code FOR UPDATE} で代える（排他lockは共有lockの上位互換でlock順も同じ）。</p>
 */
@Component
public class ActorShareLock {

    private final SysUserMapper sysUserMapper;
    private final SharedLockSupport sharedLockSupport;

    public ActorShareLock(SysUserMapper sysUserMapper, SharedLockSupport sharedLockSupport) {
        this.sysUserMapper = Objects.requireNonNull(sysUserMapper, "sysUserMapper");
        this.sharedLockSupport = Objects.requireNonNull(sharedLockSupport, "sharedLockSupport");
    }

    /**
     * 利用者行（rank 3）を共有lockし、論理削除されていない行をlock下の現在値で返す（無ければ {@code null}）。
     * {@code tenantId} を渡せば {@code id + tenant_id} で照合し、{@code null} ならIDだけで照合する。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public SysUser lockActive(long userId, String tenantId) {
        TransactionBoundState.requireActualTransaction();
        return sysUserMapper.selectActiveByIdForLock(userId, tenantId, !sharedLockSupport.supported());
    }
}
