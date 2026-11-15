package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.constant.StatusConstants;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.Proposal;
import com.ses.entity.SysUser;
import com.ses.common.exception.BusinessException;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.service.EngineerSalesService;
import com.ses.service.EngineerService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.ScopeChangeInvalidator;
import com.ses.service.security.TenantOwnershipResolver;
import com.ses.service.security.epoch.ActorEpochAnchors;
import com.ses.service.security.epoch.AuthorizationEpochAnchor;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import com.ses.service.security.epoch.EngineerEpochAnchors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * エンジニアサービス実装クラス
 *
 * <p>NF01 F2（細粒度epoch計画 §4.2、§2.2）: 要員行は engineer epoch のanchor（lock rank 7）である。所属組織・
 * 会計履歴・論理削除（述語(e)(f)が読む値）を変える書込みは、要員行を最初にX lockしてから旧値を読み、値が変わった時だけ
 * 同じtransactionでengineer epochを1進める。tenantはlockした行の値を使う。入れ子の営業解除・紐付け解除が同じ要員を
 * 進めても、fenceがtransaction内で重複を除くので増分は1回になる（E-4）。status・skill等の述語外の列だけの変更では
 * 進めない（UPDATEのX lockでreaderのS lockと直列化されるだけ）。</p>
 */
@Service
@RequiredArgsConstructor
public class EngineerServiceImpl extends ServiceImpl<EngineerMapper, Engineer> implements EngineerService {

    private final ContractMapper contractMapper;
    private final ProposalMapper proposalMapper;
    private final EngineerSalesService engineerSalesService;
    private final com.ses.service.EngineerAccountLinkService engineerAccountLinkService;
    private final com.ses.mapper.SysUserMapper sysUserMapper;
    private final TenantOwnershipResolver tenantOwnershipResolver;
    private final AuthorizationEpochFence authorizationEpochFence;

    /** 要員アカウント無効化時のsession失効。未配線のテストsliceでは何もしない。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.security.PersistentSessionService persistentSessionService;

    /** 会計属性の版を記録する。既存テストスライス互換のため任意注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.mapper.EngineerAccountingHistoryMapper engineerAccountingHistoryMapper;

    /** DataScope invalidation。既存テストスライス互換のため任意注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ScopeChangeInvalidator scopeChangeInvalidator;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.security.LegalEntityContextService legalEntityContextService;

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean removeById(Serializable id) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        Long engineerId = Long.valueOf(id.toString());
        Engineer current = tenantOwnershipResolver.selectEngineer(tenantId, engineerId);
        if (current == null) {
            return false;
        }
        if (legalEntityContextService == null || current.getLegalEntityId() == null) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        legalEntityContextService.assertCurrent(current.getLegalEntityId());
        return removeById(id, current.getVersion() == null ? 0 : current.getVersion());
    }

    /**
     * 要員を論理削除し、現任の担当営業割当・紐付けを解除して連携ユーザーを無効化する。
     *
     * <p>discovery（連携ユーザー）を伴うため READ_COMMITTED（E-3）。lock順は計画 §4.2 の
     * discovery{連携ユーザー} → 3 X{連携ユーザー} → 7 X{要員} → 9（紐付け）→ 10（担当営業）。連携ユーザーは status列だけを更新する
     * （非lockで読んだ行を {@code updateById} で全列書き戻すと、並行して確定した無効化・role変更を古い値へ戻す。§11 F-5）。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean removeById(Serializable id, Integer expectedVersion) {
        if (id == null || expectedVersion == null) {
            throw BusinessException.of(400, "error.common.optimisticLock");
        }
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        Long engineerId = Long.valueOf(id.toString());
        Engineer current = tenantOwnershipResolver.selectEngineer(tenantId, engineerId);
        if (current == null) {
            return false;
        }
        if (legalEntityContextService == null || current.getLegalEntityId() == null) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        legalEntityContextService.assertCurrent(current.getLegalEntityId());

        // discovery: 連携ユーザー（rank 3）は要員行（rank 7）より前にlockする。
        EngineerAccountLink discoveredLink = engineerAccountLinkService.findByEngineerId(engineerId);
        SysUser linkedUser = discoveredLink == null || discoveredLink.getSysUserId() == null ? null
                : sysUserMapper.selectByIdAndTenantForUpdate(discoveredLink.getSysUserId(), tenantId);
        Engineer locked = baseMapper.selectByIdForUpdateForTenant(engineerId, tenantId);
        if (locked == null) {
            return false; // discoveryからlockまでの間に並行して削除された
        }
        AuthorizationEpochAnchor.Engineer anchor = EngineerEpochAnchors.of(locked);
        // 紐付けは要員行のX lock下で安定する（紐付けのwriterは要員行をX lockする）。discoveryと違えば依存集合が
        // 変わったため、lockしていない利用者を無効化しないよう409で止める。
        EngineerAccountLink link = engineerAccountLinkService.findByEngineerId(engineerId);
        if (!Objects.equals(linkedUserIdOf(discoveredLink), linkedUserIdOf(link))) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }

        long active = contractMapper.selectCountForTenant(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getEngineerId, engineerId)
                .eq(Contract::getStatus, StatusConstants.CONTRACT_ACTIVE), tenantId);
        if (active > 0) {
            throw BusinessException.of("error.engineer.delete.activeContract");
        }
        long openProposals = proposalMapper.selectCount(new LambdaQueryWrapper<Proposal>()
                .eq(Proposal::getEngineerId, engineerId)
                .notIn(Proposal::getStatus, List.of("成約", "見送り")));
        if (openProposals > 0) {
            throw BusinessException.of("error.engineer.delete.activeProposal");
        }
        boolean removed = baseMapper.deleteByIdForTenant(engineerId, tenantId, expectedVersion) == 1;
        // 削除が成功したときだけ現任の担当営業割当を解除する（released_at 設定。履歴保全のため
        // 論理削除はしない）。削除失敗(false)時に解除だけがコミットされるのを防ぐ（review-fixes G3）。
        if (removed) {
            // 論理削除は要員の有効性（述語(e)(f)）を変えるため常に進める。入れ子の紐付け解除・営業解除の増分は重複除去される。
            authorizationEpochFence.advance(anchor);
            // 子行はrank順（9 紐付け → 10 担当営業。計画 §3.1・§3.5 規則1、ResignationGateChecker と同じ順）に書く。
            // 削除成功後にのみ、要員アカウントの紐付けを解除し当該ユーザーを無効化する（G3規約）。
            if (link != null) {
                engineerAccountLinkService.unlinkByEngineerId(engineerId);
            }
            engineerSalesService.releaseAllByEngineerId(engineerId);
            if (link != null && linkedUser != null) {
                // 連携ユーザー行（rank 3）は冒頭でX lock済みのため、新しいlockは取らない。
                disableLinkedUser(linkedUser, tenantId);
            }
        }
        return removed;
    }

    /** 連携ユーザーの status列だけを0にし、変化した時だけactor epochを進める（lock済みの行を使う）。 */
    private void disableLinkedUser(SysUser lockedUser, String tenantId) {
        int updated = sysUserMapper.update(null, new UpdateWrapper<SysUser>()
                .eq("id", lockedUser.getId())
                .eq("tenant_id", tenantId)
                .set("status", 0));
        if (updated != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        authorizationEpochFence.advanceIfChanged(ActorEpochAnchors.of(lockedUser), lockedUser.getStatus(), 0);
        if (persistentSessionService != null) {
            persistentSessionService.revokeAllForUser(lockedUser.getId(), "ENGINEER_DELETED");
        }
    }

    private static Long linkedUserIdOf(EngineerAccountLink link) {
        return link == null ? null : link.getSysUserId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateWithStatusGuard(Engineer engineer) {
        if (engineer == null || engineer.getId() == null || engineer.getVersion() == null) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        // E-2: anchor（rank 7）を最初のDB文でX lockし、旧値はlock後の行で比べる（計画 §4.2）。
        Engineer old = baseMapper.selectByIdForUpdateForTenant(engineer.getId(), tenantId);
        if (old == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (legalEntityContextService == null || old.getLegalEntityId() == null) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        legalEntityContextService.assertCurrent(old.getLegalEntityId());
        if (engineer.getLegalEntityId() != null) {
            legalEntityContextService.assertSame(old.getLegalEntityId(), engineer.getLegalEntityId());
        }
        engineer.setLegalEntityId(old.getLegalEntityId());
        if (engineer.getStatus() != null && !engineer.getStatus().equals(old.getStatus())) {
            long active = contractMapper.selectCountForTenant(new LambdaQueryWrapper<Contract>()
                    .eq(Contract::getEngineerId, engineer.getId())
                    .eq(Contract::getStatus, StatusConstants.CONTRACT_ACTIVE), tenantId);
            if (StatusConstants.ENGINEER_ACTIVE.equals(engineer.getStatus()) && active == 0) {
                throw BusinessException.of("error.engineer.statusActiveNoContract");
            }
            if (StatusConstants.ENGINEER_BENCH.equals(engineer.getStatus()) && active > 0) {
                throw BusinessException.of("error.engineer.statusBenchHasContract");
            }
        }
        // OptimisticLockerInnerInterceptor が version を検査し、成功時に +1 する。
        // 競合は 409。存在しない行との区別を保つため false 返却や 404 へ落とさない。
        if (baseMapper.updateByIdForTenant(engineer, tenantId,
                engineer.getVersion()) != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        boolean historyChanged = recordAccountingHistory(engineer.getId());
        // 要員自身の所属組織（organizationId）はscope派生SQLの一次情報。変更後もTTLが切れる
        // まで旧scopeの母集団が返らないよう進める（第十四次Review P1-3）。
        // organizationIdはupdate-strategy:not_nullのため、リクエストがnullなら未変更のまま
        // （old値が維持される）。nullの場合はここで比較する意味がないので対象から外す。
        boolean organizationChanged = engineer.getOrganizationId() != null
                && !Objects.equals(old.getOrganizationId(), engineer.getOrganizationId());
        if (organizationChanged || historyChanged) {
            authorizationEpochFence.advance(EngineerEpochAnchors.of(old));
        }
        if (organizationChanged && scopeChangeInvalidator != null) {
            scopeChangeInvalidator.invalidate();
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(Engineer entity) {
        if (entity == null || legalEntityContextService == null) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        entity.setTenantId(tenantId);
        entity.setLegalEntityId(legalEntityContextService.requireCurrentLegalEntityId());
        boolean saved = super.save(entity);
        if (saved) {
            // insert後に自行を rank 7 X で取ってから会計履歴（rank 11）を記録する。新規行のepochはDEFAULT 0のまま
            // 進めない（提出がまだ存在しないため。計画 §4.2）。
            baseMapper.selectScopeAnchorForUpdate(entity.getId());
            recordAccountingHistory(entity.getId());
        }
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateById(Engineer entity) {
        if (entity == null || entity.getId() == null || legalEntityContextService == null) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        // E-2: anchor（rank 7）を最初のDB文でX lockし、旧値はlock後の行で比べる（計画 §4.2）。
        Engineer current = baseMapper.selectByIdForUpdateForTenant(entity.getId(), tenantId);
        if (current == null || current.getLegalEntityId() == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        legalEntityContextService.assertCurrent(current.getLegalEntityId());
        if (entity.getLegalEntityId() != null) {
            legalEntityContextService.assertSame(entity.getLegalEntityId(), current.getLegalEntityId());
        }
        entity.setLegalEntityId(current.getLegalEntityId());
        // tenantの補正は修復経路（OwnershipRepair）だけが行う。entityの全体更新でtenantを書き換えさせない。
        entity.setTenantId(current.getTenantId());
        boolean updated = super.updateById(entity);
        if (updated) {
            boolean historyChanged = recordAccountingHistory(entity.getId());
            boolean organizationChanged = entity.getOrganizationId() != null
                    && !Objects.equals(current.getOrganizationId(), entity.getOrganizationId());
            if (organizationChanged || historyChanged) {
                authorizationEpochFence.advance(EngineerEpochAnchors.of(current));
            }
        }
        return updated;
    }

    /**
     * 原価部門・想定単価の版を記録する。呼出元が要員行（rank 7）をX lock済みであること（会計履歴は rank 11）。
     *
     * <p>Bench待機原価の月次snapshotは対象月時点の版を読む。ここで記録しないと、
     * 締めが遅れている間の異動・単価改定が過去月へ焼き付く（第十三次Review P1-5）。
     * 更新後の値をDBから読み直すのは、部分更新（{@code update-strategy: not_null}）で
     * 引数側がnullでも実際の現在値を版にするため。
     *
     * @return 会計履歴の行を書き換えた場合 true（engineer epochの増分契機。計画 §2.2）
     */
    private boolean recordAccountingHistory(Long engineerId) {
        if (engineerAccountingHistoryMapper == null || engineerId == null) {
            return false;
        }
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        Engineer saved = tenantOwnershipResolver.selectEngineer(tenantId, engineerId);
        if (saved == null) {
            return false;
        }
        com.ses.entity.EngineerAccountingHistory currentRow =
                engineerAccountingHistoryMapper.selectCurrent(engineerId);
        if (currentRow != null
                && java.util.Objects.equals(currentRow.getOrganizationId(), saved.getOrganizationId())
                && java.util.Objects.equals(currentRow.getCostCenterId(), saved.getCostCenterId())
                && numericEquals(currentRow.getExpectedUnitPrice(), saved.getExpectedUnitPrice())) {
            return false;
        }
        if (legalEntityContextService == null) {
            throw BusinessException.of(503, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        java.time.LocalDate today = legalEntityContextService.requireCurrentDate();
        if (currentRow != null) {
            if (!currentRow.getValidFrom().isBefore(today)) {
                // 同日中の複数回変更は版を増やさず最後の値で上書きする。
                currentRow.setOrganizationId(saved.getOrganizationId());
                currentRow.setCostCenterId(saved.getCostCenterId());
                currentRow.setExpectedUnitPrice(saved.getExpectedUnitPrice());
                engineerAccountingHistoryMapper.updateById(currentRow);
                return true;
            }
            engineerAccountingHistoryMapper.closeCurrent(engineerId, today.minusDays(1));
        }
        engineerAccountingHistoryMapper.insert(com.ses.entity.EngineerAccountingHistory.builder()
                .engineerId(engineerId)
                .organizationId(saved.getOrganizationId())
                .costCenterId(saved.getCostCenterId())
                .expectedUnitPrice(saved.getExpectedUnitPrice())
                .validFrom(today).validTo(null).build());
        return true;
    }

    private boolean numericEquals(java.math.BigDecimal left, java.math.BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }
}
