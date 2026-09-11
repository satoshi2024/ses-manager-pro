package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.TenantOwnershipResolver;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.security.ScopeChangeInvalidator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EngineerAccountLinkServiceImpl implements EngineerAccountLinkService {

    private final EngineerAccountLinkMapper linkMapper;
    private final SysUserMapper sysUserMapper;
    private final TenantOwnershipResolver tenantOwnershipResolver;

    /** DataScope invalidation。既存テストスライス（手動構築）互換のため任意注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ScopeChangeInvalidator scopeChangeInvalidator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EngineerAccountLink link(Long engineerId, Long sysUserId, Long linkedBy) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        SysUser user = sysUserMapper.selectByIdAndTenant(sysUserId, tenantId);
        if (user == null) {
            throw BusinessException.of("error.engineerAccount.userNotFound");
        }
        if (!"要員".equals(user.getRole())) {
            throw BusinessException.of("error.engineerAccount.roleNotEngineer");
        }
        if (tenantOwnershipResolver.selectEngineer(tenantId, engineerId) == null) {
            throw BusinessException.of("error.engineerAccount.engineerNotFound");
        }
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
        // 要員↔ログインアカウントの紐付けは要員の組織scope解決（account link主所属フォールバック）
        // に影響する（第十四次Review P1-3）。
        invalidateScope();
        return link;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlinkByEngineerId(Long engineerId) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        EngineerAccountLink link = linkMapper.selectByEngineerIdAndTenant(engineerId, tenantId);
        if (link != null) {
            linkMapper.deleteByIdForTenant(link.getId(), tenantId);
            invalidateScope();
        }
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
