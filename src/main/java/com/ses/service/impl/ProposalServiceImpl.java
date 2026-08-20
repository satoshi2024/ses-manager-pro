package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.dto.proposal.ProposalKanbanDto;
import com.ses.entity.Proposal;
import com.ses.entity.ProposalHistory;
import com.ses.common.exception.BusinessException;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.ProposalHistoryMapper;
import com.ses.service.EngineerStatusService;
import com.ses.service.ProposalService;
import com.ses.service.ContractService;
import com.ses.service.NotificationService;
import com.ses.entity.Contract;
import com.ses.common.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 提案サービス実装
 */
@Service
@RequiredArgsConstructor
public class ProposalServiceImpl extends ServiceImpl<ProposalMapper, Proposal> implements ProposalService {

    private final ProposalHistoryMapper proposalHistoryMapper;
    private final EngineerStatusService engineerStatusService;
    private final ContractService contractService;
    private final NotificationService notificationService;
    private final com.ses.mapper.ProjectPositionMapper positionMapper;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.ses.service.security.DataScopeService dataScopeService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.ses.service.ai.AiOutcomeService> aiOutcomeService;

    private static final Map<String, Set<String>> ALLOWED = Map.of(
        "書類選考中", Set.of("一次面接", "見送り"),
        "一次面接",   Set.of("二次面接", "結果待ち", "見送り"),
        "二次面接",   Set.of("結果待ち", "見送り"),
        "結果待ち",   Set.of("成約", "見送り"),
        "成約", Set.of(),
        "見送り", Set.of()
    );

    /** staffing-capacity-planning: ポジション紐付けは案件配下の実在ポジションに限定する。 */
    @Override
    public boolean updateById(Proposal entity) {
        validatePosition(entity);
        return super.updateById(entity);
    }

    private void validatePosition(Proposal proposal) {
        if (proposal.getPositionId() == null) {
            return;
        }
        com.ses.entity.ProjectPosition position = positionMapper.selectById(proposal.getPositionId());
        if (position == null) {
            throw BusinessException.of(404, "error.staffing.positionNotFound");
        }
        if (proposal.getProjectId() != null
                && !java.util.Objects.equals(position.getProjectId(), proposal.getProjectId())) {
            throw BusinessException.of(400, "error.staffing.positionProjectMismatch");
        }
    }

    @Override
    public List<ProposalKanbanDto> getKanbanList() {
        return this.baseMapper.selectKanbanList();
    }

    @Override
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<ProposalKanbanDto> getKanbanPage(String status, Long current, Long size, String keyword) {
        List<ProposalKanbanDto> list = this.baseMapper.selectKanbanList();
        if (dataScopeService != null && dataScopeService.isScoped()) {
            Set<Long> allowed = dataScopeService.allowedProposalIds();
            list = list.stream().filter(p -> allowed.contains(p.getId())).collect(java.util.stream.Collectors.toList());
        }
        if (status != null && !status.isBlank()) {
            list = list.stream().filter(p -> status.equals(p.getStatus())).collect(java.util.stream.Collectors.toList());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.toLowerCase().trim();
            list = list.stream().filter(p ->
                (p.getEngineerName() != null && p.getEngineerName().toLowerCase().contains(kw)) ||
                (p.getProjectName() != null && p.getProjectName().toLowerCase().contains(kw)) ||
                (p.getCustomerName() != null && p.getCustomerName().toLowerCase().contains(kw))
            ).collect(java.util.stream.Collectors.toList());
        }
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ProposalKanbanDto> page = com.ses.common.util.PageUtils.safePage(current == null ? 1L : current, size == null ? 20L : size, 100L);
        int total = list.size();
        page.setTotal(total);
        int from = (int) Math.min((page.getCurrent() - 1) * page.getSize(), total);
        int to = (int) Math.min(from + page.getSize(), total);
        page.setRecords(list.subList(from, to));
        return page;
    }

    /**
     * 提案の新規作成。
     * 保存に加えて、Bench中の要員を「提案中」へ連動させる。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(Proposal proposal) {
        validatePosition(proposal);
        if (proposal.getStatus() == null || proposal.getStatus().isBlank()) {
            proposal.setStatus("書類選考中");
        } else if (!"書類選考中".equals(proposal.getStatus())) {
            throw BusinessException.of("error.proposal.statusTransitionInvalid", "新規", proposal.getStatus());
        }
        proposal.setProposedAt(LocalDateTime.now());
        proposal.setProposedBy(SecurityUtils.currentUserId());

        boolean result;
        try {
            result = super.save(proposal);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            if (e.getMessage() != null && e.getMessage().contains("uk_proposal_active_engineer_project")) {
                throw com.ses.common.exception.BusinessException.of(409, "error.proposal.alreadyProposed");
            }
            throw e;
        }

        if (result && proposal.getEngineerId() != null) {
            engineerStatusService.onProposalCreated(proposal.getEngineerId());
        }
        if (result) {
            recordOutcome(svc -> svc.onProposalSaved(proposal));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, String newStatus) {
        Proposal proposal = this.baseMapper.selectByIdForUpdate(id);
        if (proposal == null) {
            throw BusinessException.of("error.proposal.notFound");
        }
        if (dataScopeService != null) {
            if (proposal.getEngineerId() != null) {
                dataScopeService.assertAllowedEngineer(proposal.getEngineerId());
            }
            if (proposal.getProjectId() != null) {
                dataScopeService.assertAllowedProject(proposal.getProjectId());
            }
        }
        
        String oldStatus = proposal.getStatus();
        if (!ALLOWED.getOrDefault(oldStatus, Set.of()).contains(newStatus)) {
            throw BusinessException.of("error.proposal.statusTransitionInvalid", oldStatus, newStatus);
        }
        
        // 提案ステータスの更新
        proposal.setStatus(newStatus);
        if ("成約".equals(newStatus) || "見送り".equals(newStatus)) {
            proposal.setClosedAt(LocalDateTime.now());
        }
        this.updateById(proposal);

        // 提案履歴の作成
        ProposalHistory history = new ProposalHistory();
        history.setProposalId(id);
        history.setFromStatus(oldStatus);
        history.setToStatus(newStatus);
        history.setChangedAt(LocalDateTime.now());
        history.setChangedBy(SecurityUtils.currentUserId());
        history.setRemarks("ステータス変更");
        proposalHistoryMapper.insert(history);
        
        if ("見送り".equals(newStatus)) {
            engineerStatusService.releaseIfIdle(proposal.getEngineerId());
        }

        // 成約時: 契約ドラフト(準備中)を同一トランザクションで自動生成し、確認を促す通知を発行する。
        // トランザクション失敗時は成約遷移ごとロールバックされる。
        if ("成約".equals(newStatus)) {
            Contract draft = contractService.createDraftFromProposal(proposal);
            boolean unattributed = draft.getSalesUserId() == null;
            String msgKey = unattributed
                    ? "notification.msg.CONTRACT_DRAFT_UNATTRIBUTED"
                    : "notification.msg.CONTRACT_DRAFT";
            notificationService.publishToUser(
                    draft.getSalesUserId(),
                    "CONTRACT_DRAFT",
                    "契約ドラフト作成",
                    "[\"" + msgKey + "\", \"" + draft.getContractNo() + "\"]",
                    com.ses.common.constant.NotificationLinks.CONTRACT_LIST,
                    "contract-draft:" + proposal.getId());
        }
        recordOutcome(svc -> svc.onProposalStatusChanged(proposal));
    }

    private void recordOutcome(java.util.function.Consumer<com.ses.service.ai.AiOutcomeService> action) {
        if (aiOutcomeService == null) {
            return;
        }
        com.ses.service.ai.AiOutcomeService svc = aiOutcomeService.getIfAvailable();
        if (svc != null) {
            action.accept(svc);
        }
    }

    @Override
    public List<ProposalKanbanDto> findActiveDuplicates(Long engineerId, Long customerId, Long excludeId) {
        if (dataScopeService != null && dataScopeService.isScoped()) {
            dataScopeService.assertAllowedEngineer(engineerId);
            dataScopeService.assertAllowedCustomer(customerId);
        }
        return this.baseMapper.selectActiveDuplicates(engineerId, customerId, excludeId, com.ses.common.constant.StatusConstants.PROPOSAL_ACTIVE_STATUSES);
    }

    @Override
    public List<ProposalKanbanDto> getProposalHistory(Long engineerId) {
        java.util.Collection<Long> allowedCustomerIds = null;
        if (dataScopeService != null && dataScopeService.isScoped()) {
            dataScopeService.assertAllowedEngineer(engineerId);
            allowedCustomerIds = dataScopeService.allowedCustomerIds();
            if (allowedCustomerIds.isEmpty()) {
                return java.util.Collections.emptyList();
            }
        }
        return this.baseMapper.selectProposalHistory(engineerId, allowedCustomerIds);
    }
}




