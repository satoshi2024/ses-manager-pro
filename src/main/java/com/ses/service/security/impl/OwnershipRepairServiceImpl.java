package com.ses.service.security.impl;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.OwnershipRepairQueue;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.OwnershipRepairQueueMapper;
import com.ses.mapper.BpAvailabilityMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.OwnershipRepairService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** unresolved ownershipの手動修復。推測補完せず、現在tenantの管理者操作だけを記録する。 */
@Service
@RequiredArgsConstructor
public class OwnershipRepairServiceImpl implements OwnershipRepairService {
    private final OwnershipRepairQueueMapper queueMapper;
    private final CustomerMapper customerMapper;
    private final EngineerMapper engineerMapper;
    private final BpAvailabilityMapper bpAvailabilityMapper;
    private final EngineerAccountLinkMapper engineerAccountLinkMapper;
    private final SysUserMapper sysUserMapper;

    @Override
    @Transactional(readOnly = true)
    public List<OwnershipRepairQueue> listPending() {
        requireAdmin();
        return queueMapper.selectPending();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        requireAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        LocalDateTime oldest = queueMapper.selectOldestPendingAt();
        result.put("pendingCount", queueMapper.countPending());
        result.put("oldestPendingAt", oldest);
        result.put("oldestPendingAgeSeconds", oldest == null ? 0L
                : Math.max(0L, Duration.between(oldest, LocalDateTime.now()).getSeconds()));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resolve(Long queueId, String tenantId, String reason, String evidence) {
        requireAdmin();
        String currentTenant = AccountingTenantContextHolder.requireTenantContext();
        if (tenantId == null || tenantId.isBlank() || !currentTenant.equals(tenantId.trim())
                || reason == null || reason.isBlank() || evidence == null || evidence.isBlank()) {
            throw BusinessException.of(403, "error.tenant.repairEvidenceRequired");
        }
        OwnershipRepairQueue row = queueMapper.selectById(queueId);
        if (row == null || !"PENDING".equals(row.getStatus())) {
            throw BusinessException.of(404, "error.notFound");
        }
        int updated;
        if ("CUSTOMER".equals(row.getEntityType())) {
            updated = customerMapper.assignTenantForRepair(row.getEntityId(), currentTenant);
        } else if ("ENGINEER".equals(row.getEntityType())) {
            updated = engineerMapper.assignTenantForRepair(row.getEntityId(), currentTenant);
        } else if ("BP_AVAILABILITY".equals(row.getEntityType())) {
            updated = bpAvailabilityMapper.assignTenantForRepair(row.getEntityId(), currentTenant);
        } else if ("ENGINEER_ACCOUNT_LINK".equals(row.getEntityType())) {
            updated = engineerAccountLinkMapper.assignTenantForRepair(row.getEntityId(), currentTenant);
        } else {
            throw BusinessException.of(400, "error.tenant.repairEntityType");
        }
        if (updated != 1) {
            throw BusinessException.of(409, "error.tenant.repairConflict");
        }
        if (queueMapper.markResolved(queueId, currentTenant, reason.trim(), evidence.trim(),
                LocalDateTime.now(), SecurityUtils.currentUserId()) != 1) {
            throw BusinessException.of(409, "error.tenant.repairConflict");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assign(Long queueId, Long assigneeUserId) {
        requireAdmin();
        String currentTenant = AccountingTenantContextHolder.requireTenantContext();
        if (assigneeUserId == null || sysUserMapper.selectByIdAndTenant(assigneeUserId, currentTenant) == null) {
            throw BusinessException.of(403, "error.tenant.repairAssignee");
        }
        OwnershipRepairQueue row = queueMapper.selectById(queueId);
        if (row == null || !"PENDING".equals(row.getStatus())) {
            throw BusinessException.of(404, "error.notFound");
        }
        if (queueMapper.assign(queueId, assigneeUserId, LocalDateTime.now()) != 1) {
            throw BusinessException.of(409, "error.tenant.repairConflict");
        }
    }

    private void requireAdmin() {
        if (!"管理者".equals(SecurityUtils.currentRole())) {
            throw BusinessException.of(403, "error.forbidden");
        }
    }
}
