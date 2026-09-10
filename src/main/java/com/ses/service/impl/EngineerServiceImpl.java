package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.constant.StatusConstants;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.Proposal;
import com.ses.common.exception.BusinessException;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.service.EngineerSalesService;
import com.ses.service.EngineerService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.ScopeChangeInvalidator;
import com.ses.service.security.TenantOwnershipResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.List;

/**
 * エンジニアサービス実装クラス
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
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        Long engineerId = Long.valueOf(id.toString());
        Engineer current = tenantOwnershipResolver.selectEngineer(tenantId, engineerId);
        if (current == null) {
            return false;
        }
        if (legalEntityContextService != null && current.getLegalEntityId() != null) {
            legalEntityContextService.assertCurrent(current.getLegalEntityId());
        }
        return removeById(id, current.getVersion() == null ? 0 : current.getVersion());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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
        if (legalEntityContextService != null && current.getLegalEntityId() != null) {
            legalEntityContextService.assertCurrent(current.getLegalEntityId());
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
            engineerSalesService.releaseAllByEngineerId(engineerId);
            // 削除成功後にのみ、要員アカウントの紐付けを解除し当該ユーザーを無効化する（G3規約）。
            com.ses.entity.EngineerAccountLink link = engineerAccountLinkService.findByEngineerId(engineerId);
            if (link != null) {
                Long userId = link.getSysUserId();
                engineerAccountLinkService.unlinkByEngineerId(engineerId);
                com.ses.entity.SysUser user = sysUserMapper.selectByIdAndTenant(userId, tenantId);
                if (user != null) {
                    user.setStatus(0);
                    sysUserMapper.updateById(user);
                    if (persistentSessionService != null) {
                        persistentSessionService.revokeAllForUser(userId, "ENGINEER_DELETED");
                    }
                }
            }
        }
        return removed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateWithStatusGuard(Engineer engineer) {
        if (engineer == null || engineer.getId() == null || engineer.getVersion() == null) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        Engineer old = tenantOwnershipResolver.selectEngineer(tenantId, engineer.getId());
        if (old == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (legalEntityContextService != null && old.getLegalEntityId() != null) {
            legalEntityContextService.assertCurrent(old.getLegalEntityId());
            if (engineer.getLegalEntityId() != null) {
                legalEntityContextService.assertSame(old.getLegalEntityId(), engineer.getLegalEntityId());
            }
            engineer.setLegalEntityId(old.getLegalEntityId());
        }
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
        recordAccountingHistory(engineer.getId());
        // 要員自身の所属組織（organizationId）はscope派生SQLの一次情報。変更後もTTLが切れる
        // まで旧scopeの母集団が返らないよう進める（第十四次Review P1-3）。
        // organizationIdはupdate-strategy:not_nullのため、リクエストがnullなら未変更のまま
        // （old値が維持される）。nullの場合はここで比較する意味がないので対象から外す。
        if (engineer.getOrganizationId() != null
                && !java.util.Objects.equals(old.getOrganizationId(), engineer.getOrganizationId())
                && scopeChangeInvalidator != null) {
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
        Engineer current = getById(entity.getId());
        if (current == null || current.getLegalEntityId() == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        legalEntityContextService.assertCurrent(current.getLegalEntityId());
        if (entity.getLegalEntityId() != null) {
            legalEntityContextService.assertSame(entity.getLegalEntityId(), current.getLegalEntityId());
        }
        entity.setLegalEntityId(current.getLegalEntityId());
        boolean updated = super.updateById(entity);
        if (updated) recordAccountingHistory(entity.getId());
        return updated;
    }

    /**
     * 原価部門・想定単価の版を記録する。
     *
     * <p>Bench待機原価の月次snapshotは対象月時点の版を読む。ここで記録しないと、
     * 締めが遅れている間の異動・単価改定が過去月へ焼き付く（第十三次Review P1-5）。
     * 更新後の値をDBから読み直すのは、部分更新（{@code update-strategy: not_null}）で
     * 引数側がnullでも実際の現在値を版にするため。
     */
    private void recordAccountingHistory(Long engineerId) {
        if (engineerAccountingHistoryMapper == null || engineerId == null) {
            return;
        }
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        Engineer saved = tenantOwnershipResolver.selectEngineer(tenantId, engineerId);
        if (saved == null) {
            return;
        }
        com.ses.entity.EngineerAccountingHistory currentRow =
                engineerAccountingHistoryMapper.selectCurrent(engineerId);
        if (currentRow != null
                && java.util.Objects.equals(currentRow.getOrganizationId(), saved.getOrganizationId())
                && java.util.Objects.equals(currentRow.getCostCenterId(), saved.getCostCenterId())
                && numericEquals(currentRow.getExpectedUnitPrice(), saved.getExpectedUnitPrice())) {
            return;
        }
        java.time.LocalDate today = legalEntityContextService == null
                ? null : legalEntityContextService.requireCurrentDate();
        if (today == null) {
            throw BusinessException.of(503, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        if (currentRow != null) {
            if (!currentRow.getValidFrom().isBefore(today)) {
                // 同日中の複数回変更は版を増やさず最後の値で上書きする。
                currentRow.setOrganizationId(saved.getOrganizationId());
                currentRow.setCostCenterId(saved.getCostCenterId());
                currentRow.setExpectedUnitPrice(saved.getExpectedUnitPrice());
                engineerAccountingHistoryMapper.updateById(currentRow);
                return;
            }
            engineerAccountingHistoryMapper.closeCurrent(engineerId, today.minusDays(1));
        }
        engineerAccountingHistoryMapper.insert(com.ses.entity.EngineerAccountingHistory.builder()
                .engineerId(engineerId)
                .organizationId(saved.getOrganizationId())
                .costCenterId(saved.getCostCenterId())
                .expectedUnitPrice(saved.getExpectedUnitPrice())
                .validFrom(today).validTo(null).build());
    }

    private boolean numericEquals(java.math.BigDecimal left, java.math.BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }
}
