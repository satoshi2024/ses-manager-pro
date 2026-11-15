package com.ses.service.security.epoch;

import com.ses.mapper.OrganizationUnitMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * NF01 lock rank 6（組織行）の共有lock。外部キー検査が参照先の組織行に付ける共有lockを、要員行（rank 7）のX lockより前に
 * 取っておくために使う（細粒度epoch計画 §3.1・§3.5 規則1、W3-16a後半 review P3-1）。
 *
 * <p>要員の所属組織を変える更新（{@code t_engineer.organization_id}）や、組織を持つ業務行のinsert（休暇申請等）は、
 * 要員行をX lockした後の書込みでInnoDBが外部キー検査として組織行に {@code S,REC_NOT_GAP} を付ける。組織行を先にX lockして
 * 要員行を待つwriter（組織統合・所属登録・異動・所属更新: 6 X → 7 X）と同じ要員・同じ組織で並行すると、7 X → 6 S と
 * 6 X → 7 X の循環待ち（1213）になり得た。参照先の組織行を要員行より先に共有lockすれば、外部キー検査は保持済みのlockで
 * 済み、新しい待ちを作らない。共有lockなので、同じ組織を参照する要員の更新同士やCONFIRMのreader（rank 6 S）は待たない。</p>
 *
 * <p>H2は {@code FOR SHARE} を解釈しないため、{@link SharedLockSupport} の判定に従い {@code FOR UPDATE} で代える。</p>
 */
@Component
public class OrganizationShareLock {

    private final OrganizationUnitMapper organizationUnitMapper;
    private final SharedLockSupport sharedLockSupport;

    public OrganizationShareLock(OrganizationUnitMapper organizationUnitMapper, SharedLockSupport sharedLockSupport) {
        this.organizationUnitMapper = Objects.requireNonNull(organizationUnitMapper, "organizationUnitMapper");
        this.sharedLockSupport = Objects.requireNonNull(sharedLockSupport, "sharedLockSupport");
    }

    /**
     * 外部キーで参照する組織行（rank 6）を共有lockする。{@code null} は何もしない。行が無ければ何もせず、参照の可否は
     * 後続の書込み（外部キー制約）に委ねる。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void lockReferenced(Long organizationId) {
        if (organizationId == null) {
            return;
        }
        TransactionBoundState.requireActualTransaction();
        organizationUnitMapper.lockReferencedRow(organizationId, !sharedLockSupport.supported());
    }
}
