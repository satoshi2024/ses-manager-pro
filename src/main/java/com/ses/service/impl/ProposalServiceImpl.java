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

    private static final Map<String, Set<String>> ALLOWED = Map.of(
        "書類選考中", Set.of("一次面接", "見送り"),
        "一次面接",   Set.of("二次面接", "結果待ち", "見送り"),
        "二次面接",   Set.of("結果待ち", "見送り"),
        "結果待ち",   Set.of("成約", "見送り"),
        "成約", Set.of(),
        "見送り", Set.of()
    );

    @Override
    public List<ProposalKanbanDto> getKanbanList() {
        return this.baseMapper.selectKanbanList();
    }

    /**
     * 提案の新規作成。
     * 保存に加えて、Bench中の要員を「提案中」へ連動させる。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(Proposal proposal) {
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
                throw com.ses.common.exception.BusinessException.of(409, "この要員はすでに同じ案件に提案中です。");
            }
            throw e;
        }

        if (result && proposal.getEngineerId() != null) {
            engineerStatusService.onProposalCreated(proposal.getEngineerId());
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, String newStatus) {
        Proposal proposal = this.getById(id);
        if (proposal == null) {
            throw BusinessException.of("error.proposal.notFound");
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
            // 主担当営業が退職済み等で未帰属(sales_user_id=NULL)になった場合は担当設定を促すメッセージにする。
            boolean unattributed = draft.getSalesUserId() == null;
            String msgKey = unattributed
                    ? "notification.msg.CONTRACT_DRAFT_UNATTRIBUTED"
                    : "notification.msg.CONTRACT_DRAFT";
            // 契約ドラフト確認通知は担当営業本人へ個別配信する（未帰属時のみ全体通知 / R3R-33）。
            notificationService.publishToUser(
                    draft.getSalesUserId(),
                    "CONTRACT_DRAFT",
                    "契約ドラフト作成",
                    "[\"" + msgKey + "\", \"" + draft.getContractNo() + "\"]",
                    com.ses.common.constant.NotificationLinks.CONTRACT_LIST,
                    "contract-draft:" + proposal.getId());
        }
    }
}




