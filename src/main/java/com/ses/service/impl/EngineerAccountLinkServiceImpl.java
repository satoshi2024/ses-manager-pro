package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.security.ScopeChangeInvalidator;
import com.ses.service.security.epoch.ActorShareLock;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import com.ses.service.security.epoch.EngineerEpochAnchors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 要員↔ログインアカウントの紐付け。
 *
 * <p>NF01 F2（細粒度epoch計画 §4.2、§3.4）: 紐付けは要員の組織scope（連携ユーザーの主所属へのフォールバック・
 * 上長の直属経路）と要員本人の可視範囲（述語(e)(f)）を変えるため、紐付け行（lock rank 9）を書く前に連携ユーザー行
 * （rank 3）を共有lock、要員行（rank 7、engineer epochのanchor）をX lockし、同じtransactionでengineer epochを1進める。
 * 連携ユーザーの削除・無効化・roleはlock下の現在値で読み直す（W3-15 review P3-8）。tenantはlockした要員行の値を使う。</p>
 */
@Service
@RequiredArgsConstructor
public class EngineerAccountLinkServiceImpl implements EngineerAccountLinkService {

    private final EngineerAccountLinkMapper linkMapper;
    private final EngineerMapper engineerMapper;
    private final ActorShareLock actorShareLock;
    private final AuthorizationEpochFence authorizationEpochFence;

    /** DataScope invalidation。既存テストスライス（手動構築）互換のため任意注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ScopeChangeInvalidator scopeChangeInvalidator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EngineerAccountLink link(Long engineerId, Long sysUserId, Long linkedBy) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        // 計画 §3.4: 3 S{連携ユーザー} → 7 X{要員} → 9。非lockの読取で通すと、並行する deleteUser
        // （連携中ユーザーの削除拒否を非lockの照会で判定する）と同時に成立し得るため、削除・無効化・roleはlock下で読む。
        SysUser user = sysUserId == null ? null : actorShareLock.lockActive(sysUserId, tenantId);
        if (user == null) {
            throw BusinessException.of("error.engineerAccount.userNotFound");
        }
        if (!"要員".equals(user.getRole())) {
            throw BusinessException.of("error.engineerAccount.roleNotEngineer");
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw BusinessException.of("error.engineerAccount.userDisabled");
        }
        Engineer engineer = engineerId == null ? null : engineerMapper.selectByIdForUpdateForTenant(engineerId, tenantId);
        if (engineer == null) {
            throw BusinessException.of("error.engineerAccount.engineerNotFound");
        }
        // 重複判定はanchor取得後に行う（紐付けのwriterはすべて同じ要員行のX lockで直列化される）。
        if (linkMapper.selectByUserIdAndTenant(sysUserId, tenantId) != null) {
            throw BusinessException.of("error.engineerAccount.userAlreadyLinked");
        }
        if (linkMapper.selectByEngineerIdAndTenant(engineerId, tenantId) != null) {
            throw BusinessException.of("error.engineerAccount.engineerAlreadyLinked");
        }
        EngineerAccountLink link = new EngineerAccountLink();
        link.setTenantId(tenantId);
        link.setEngineerId(engineerId);
        link.setSysUserId(sysUserId);
        link.setLinkedBy(linkedBy);
        try {
            linkMapper.insert(link);
        } catch (DuplicateKeyException e) {
            // 事前照会を同時に通過した場合もDB一意制約を最終防線として409へ正規化する。
            throw BusinessException.of(409, "error.engineerAccount.alreadyLinked");
        }
        authorizationEpochFence.advance(EngineerEpochAnchors.of(engineer));
        // 要員↔ログインアカウントの紐付けは要員の組織scope解決（account link主所属フォールバック）
        // に影響する（第十四次Review P1-3）。
        invalidateScope();
        return link;
    }

    /**
     * 紐付けを解除する。discovery（紐付け→連携ユーザー）を伴うため READ_COMMITTED（E-3）で、lock後に読み直す。
     * 要員削除の後段（同じtransactionで要員を論理削除した後）からも呼ばれるため、要員の削除状態では絞らない。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void unlinkByEngineerId(Long engineerId) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        EngineerAccountLink discovered = engineerId == null ? null
                : linkMapper.selectByEngineerIdAndLinkTenant(engineerId, tenantId);
        if (discovered == null) {
            return;
        }
        // 計画 §4.2: 3 S{連携ユーザー（discovery）} → 7 X{要員} → 9。連携ユーザーが削除済みならlockする行が無いだけ。
        if (discovered.getSysUserId() != null) {
            actorShareLock.lockActive(discovered.getSysUserId(), tenantId);
        }
        Engineer engineer = engineerMapper.selectScopeAnchorForUpdate(engineerId);
        EngineerAccountLink link = linkMapper.selectByEngineerIdAndLinkTenant(engineerId, tenantId);
        if (link == null) {
            return; // lockまでの間に並行して解除済み
        }
        if (!Objects.equals(link.getId(), discovered.getId())
                || !Objects.equals(link.getSysUserId(), discovered.getSysUserId())) {
            // lockを取った連携ユーザーと別の紐付けへ替わっている。lockしていない利用者の紐付けは消さない。
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        if (linkMapper.deleteByIdForTenant(link.getId(), tenantId) != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        // 要員行が無い孤立した紐付けはanchorが無く、CONFIRMのreaderもその要員を読めないため増分の対象にしない。
        if (engineer != null) {
            authorizationEpochFence.advance(EngineerEpochAnchors.of(engineer));
        }
        invalidateScope();
    }

    private void invalidateScope() {
        if (scopeChangeInvalidator != null) {
            scopeChangeInvalidator.invalidate();
        }
    }

    @Override
    public Long findEngineerIdByUserId(Long sysUserId) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        EngineerAccountLink link = linkMapper.selectByUserIdAndTenant(sysUserId, tenantId);
        // NULL tenant_id の旧行は OwnershipRepair 経由でのみ修復する。通常経路で推測フォールバックしない。
        return link != null ? link.getEngineerId() : null;
    }

    @Override
    public EngineerAccountLink findByEngineerId(Long engineerId) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        return linkMapper.selectByEngineerIdAndTenant(engineerId, tenantId);
    }

    @Override
    public boolean isUserLinked(Long sysUserId) {
        return findEngineerIdByUserId(sysUserId) != null;
    }

    @Override
    public java.util.Set<Long> findLinkedEngineerIds(java.util.Collection<Long> engineerIds) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        if (engineerIds == null || engineerIds.isEmpty()) {
            return java.util.Set.of();
        }
        return linkMapper.selectByEngineerIdsAndTenant(new java.util.ArrayList<>(engineerIds), tenantId).stream()
                .map(EngineerAccountLink::getEngineerId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Override
    public java.util.Set<Long> findLinkedUserIds(java.util.Collection<Long> sysUserIds) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        if (sysUserIds == null || sysUserIds.isEmpty()) {
            return java.util.Set.of();
        }
        return linkMapper.selectLinkedUserIdsAndTenant(new java.util.ArrayList<>(sysUserIds), tenantId).stream()
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
    }
}
