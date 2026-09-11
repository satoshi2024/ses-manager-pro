package com.ses.service.security.impl;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.security.OwnershipRepairRequest;
import com.ses.dto.security.OwnershipRepairClaimResponse;
import com.ses.entity.OwnershipRepairQueue;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.OwnershipRepairQueueMapper;
import com.ses.mapper.BpAvailabilityMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.OwnershipRepairService;
import com.ses.service.security.RepairAuthorityResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

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
    private final RepairAuthorityResolver authorityResolver;

    @Override
    @Transactional(readOnly = true)
    public List<OwnershipRepairQueue> listPending() {
        RepairAuthorityResolver.RepairAuthority authority = authorityResolver.requireAuthority();
        return queueMapper.selectPending(authority.tenantId());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        RepairAuthorityResolver.RepairAuthority authority = authorityResolver.requireAuthority();
        Map<String, Object> result = new LinkedHashMap<>();
        LocalDateTime oldest = queueMapper.selectOldestPendingAt(authority.tenantId());
        result.put("pendingCount", queueMapper.countPending(authority.tenantId()));
        result.put("oldestPendingAt", oldest);
        result.put("oldestPendingAgeSeconds", oldest == null ? 0L
                : Math.max(0L, Duration.between(oldest, LocalDateTime.now()).getSeconds()));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resolve(Long queueId, OwnershipRepairRequest request) {
        RepairAuthorityResolver.RepairAuthority authority = authorityResolver.requireAuthority();
        if (request == null || request.getExpectedVersion() == null
                || request.getClaimToken() == null || request.getClaimToken().isBlank()
                || request.getEvidence() == null || request.getReason() == null
                || request.getReason().isBlank() || request.getReason().trim().length() > 500) {
            throw BusinessException.of(400, "error.tenant.repairEvidenceRequired");
        }
        OwnershipRepairQueue row = queueMapper.selectForUpdate(queueId, authority.tenantId());
        if (row == null) {
            throw BusinessException.of(404, "error.notFound");
        }
        if (!authority.tenantId().equals(row.getConflictingTenantId())) {
            throw BusinessException.of(403, "error.forbidden");
        }
        if (!"CLAIMED".equals(row.getStatus())
                || !request.getClaimToken().equals(row.getClaimToken())
                || !request.getExpectedVersion().equals(row.getVersion())) {
            throw BusinessException.of(409, "error.tenant.repairConflict");
        }
        String evidence = structuredEvidence(request.getEvidence());
        String evidenceHash = sha256(evidence);
        int updated;
        if ("CUSTOMER".equals(row.getEntityType())) {
            updated = customerMapper.assignTenantForRepair(row.getEntityId(), authority.tenantId());
        } else if ("ENGINEER".equals(row.getEntityType())) {
            updated = engineerMapper.assignTenantForRepair(row.getEntityId(), authority.tenantId());
        } else if ("BP_AVAILABILITY".equals(row.getEntityType())) {
            updated = bpAvailabilityMapper.assignTenantForRepair(row.getEntityId(), authority.tenantId());
        } else if ("ENGINEER_ACCOUNT_LINK".equals(row.getEntityType())) {
            updated = engineerAccountLinkMapper.assignTenantForRepair(row.getEntityId(), authority.tenantId());
        } else {
            throw BusinessException.of(400, "error.tenant.repairEntityType");
        }
        if (updated != 1) {
            throw BusinessException.of(409, "error.tenant.repairConflict");
        }
        if (queueMapper.markResolvedCas(queueId, authority.tenantId(), request.getReason().trim(), evidence,
                LocalDateTime.now(), authority.actorId(), authority.incidentId(), authority.tenantId(),
                evidenceHash, authority.approverId(), request.getClaimToken(), request.getExpectedVersion()) != 1) {
            throw BusinessException.of(409, "error.tenant.repairConflict");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OwnershipRepairClaimResponse assign(Long queueId, Long assigneeUserId, Integer expectedVersion) {
        RepairAuthorityResolver.RepairAuthority authority = authorityResolver.requireAuthority();
        if (assigneeUserId == null || expectedVersion == null
                || sysUserMapper.selectByIdAndTenant(assigneeUserId, authority.tenantId()) == null) {
            throw BusinessException.of(403, "error.tenant.repairAssignee");
        }
        OwnershipRepairQueue row = queueMapper.selectForUpdate(queueId, authority.tenantId());
        if (row == null) {
            throw BusinessException.of(404, "error.notFound");
        }
        if (!authority.tenantId().equals(row.getConflictingTenantId())) {
            throw BusinessException.of(403, "error.forbidden");
        }
        if (!"PENDING".equals(row.getStatus()) || !expectedVersion.equals(row.getVersion())) {
            throw BusinessException.of(409, "error.tenant.repairConflict");
        }
        String claimToken = UUID.randomUUID().toString();
        if (queueMapper.claim(queueId, assigneeUserId, claimToken, LocalDateTime.now(), expectedVersion,
                authority.incidentId(), authority.tenantId()) != 1) {
            throw BusinessException.of(409, "error.tenant.repairConflict");
        }
        return new OwnershipRepairClaimResponse(queueId, claimToken, expectedVersion + 1);
    }

    private String structuredEvidence(OwnershipRepairRequest.Evidence evidence) {
        if (evidence == null || !StringUtils.hasText(evidence.getSourceType())
                || !StringUtils.hasText(evidence.getSourceReference())
                || !StringUtils.hasText(evidence.getStatement())) {
            throw BusinessException.of(400, "error.tenant.repairEvidenceRequired");
        }
        String sourceType = evidence.getSourceType().trim();
        String sourceReference = evidence.getSourceReference().trim();
        String statement = evidence.getStatement().trim();
        if (!sourceType.matches("[A-Z][A-Z0-9_-]{2,30}")
                || sourceReference.length() > 200 || statement.length() > 1000) {
            throw BusinessException.of(400, "error.tenant.repairEvidenceRequired");
        }
        return "sourceType=" + sourceType + "\nsourceReference=" + sourceReference + "\nstatement=" + statement;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("repair evidence hashを生成できません", e);
        }
    }
}
