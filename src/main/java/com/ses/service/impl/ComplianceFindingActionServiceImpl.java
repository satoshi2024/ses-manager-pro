package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.ComplianceFinding;
import com.ses.entity.Contract;
import com.ses.mapper.ComplianceFindingMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.service.ComplianceFindingActionService;
import com.ses.service.ContractService;
import com.ses.service.MenuCacheService;
import com.ses.service.compliance.ComplianceAccessControl;
import com.ses.service.compliance.ComplianceFindingStore;
import com.ses.service.security.DataScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * T065 B2: findingの対応状態遷移（ack/in-progress/resolve/exception）。
 * 遷移は既存rule実行（ComplianceFindingStore.sync）と競合するため、@Version CASで制御する。
 * EXCEPTION_APPROVEDの失効はComplianceDeadlineServiceが担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceFindingActionServiceImpl implements ComplianceFindingActionService {

    private final ContractService contractService;
    private final ComplianceFindingMapper findingMapper;
    private final DocumentMapper documentMapper;
    private final DataScopeService dataScopeService;
    private final ObjectProvider<MenuCacheService> menuCacheServiceProvider;

    @Override
    @Transactional
    public void ack(Long contractId, Long findingId) {
        requireAccess();
        ComplianceFinding finding = requireFinding(contractId, findingId);
        if (!java.util.Set.of(ComplianceFindingStore.STATUS_OPEN, ComplianceFindingStore.STATUS_IN_PROGRESS)
                .contains(finding.getStatus())) {
            throw BusinessException.of(400, "contract.compliance.findingTransitionDenied");
        }
        finding.setStatus(ComplianceFindingStore.STATUS_ACKNOWLEDGED);
        finding.setAcknowledgedBy(SecurityUtils.currentUserId());
        finding.setAcknowledgedAt(LocalDateTime.now());
        updateCas(finding);
    }

    @Override
    @Transactional
    public void inProgress(Long contractId, Long findingId) {
        requireAccess();
        ComplianceFinding finding = requireFinding(contractId, findingId);
        if (!java.util.Set.of(ComplianceFindingStore.STATUS_OPEN, ComplianceFindingStore.STATUS_ACKNOWLEDGED)
                .contains(finding.getStatus())) {
            throw BusinessException.of(400, "contract.compliance.findingTransitionDenied");
        }
        finding.setStatus(ComplianceFindingStore.STATUS_IN_PROGRESS);
        updateCas(finding);
    }

    @Override
    @Transactional
    public void resolve(Long contractId, Long findingId, String note, Long evidenceDocumentId) {
        requireAccess();
        ComplianceFinding finding = requireFinding(contractId, findingId);
        if (ComplianceFindingStore.STATUS_RESOLVED.equals(finding.getStatus())) {
            return;
        }
        if (!StringUtils.hasText(note)) {
            throw BusinessException.of(400, "contract.compliance.findingNoteRequired");
        }
        if (evidenceDocumentId != null && documentMapper.selectById(evidenceDocumentId) == null) {
            throw BusinessException.of(400, "contract.compliance.invalidEvidence");
        }
        finding.setStatus(ComplianceFindingStore.STATUS_RESOLVED);
        finding.setResolutionNote(note);
        finding.setEvidenceDocumentId(evidenceDocumentId);
        updateCas(finding);
    }

    @Override
    @Transactional
    public void exception(Long contractId, Long findingId, String note, LocalDateTime expiresAt) {
        requireAccess();
        ComplianceFinding finding = requireFinding(contractId, findingId);
        if (!java.util.Set.of(ComplianceFindingStore.STATUS_OPEN, ComplianceFindingStore.STATUS_ACKNOWLEDGED,
                ComplianceFindingStore.STATUS_IN_PROGRESS).contains(finding.getStatus())) {
            throw BusinessException.of(400, "contract.compliance.findingTransitionDenied");
        }
        if (!StringUtils.hasText(note)) {
            throw BusinessException.of(400, "contract.compliance.findingNoteRequired");
        }
        if (expiresAt == null) {
            throw BusinessException.of(400, "contract.compliance.findingExpiresAtRequired");
        }
        if (!expiresAt.isAfter(LocalDateTime.now())) {
            throw BusinessException.of(400, "contract.compliance.findingExpiresAtPast");
        }
        finding.setStatus(ComplianceFindingStore.STATUS_EXCEPTION_APPROVED);
        finding.setResolutionNote(note);
        finding.setExceptionExpiresAt(expiresAt);
        updateCas(finding);
    }

    // ===== 共通 =====

    private void requireAccess() {
        String role = SecurityUtils.currentRole();
        if ("営業".equals(role)) {
            throw BusinessException.of(403, "contract.compliance.writeDenied");
        }
        MenuCacheService menuCacheService = null;
        try {
            menuCacheService = menuCacheServiceProvider.getIfAvailable();
        } catch (Exception ignored) {
            // fail-closed
        }
        if (!ComplianceAccessControl.canViewCompliance(role, menuCacheService)) {
            throw BusinessException.of(403, "error.accessDenied");
        }
    }

    private ComplianceFinding requireFinding(Long contractId, Long findingId) {
        Contract contract = contractService.getById(contractId);
        if (contract == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        dataScopeService.assertAllowedContract(contractId);
        ComplianceFinding finding = findingMapper.selectById(findingId);
        if (finding == null || !contractId.equals(finding.getContractId())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return finding;
    }

    private void updateCas(ComplianceFinding finding) {
        int rows = findingMapper.updateById(finding);
        if (rows == 0) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
    }
}
