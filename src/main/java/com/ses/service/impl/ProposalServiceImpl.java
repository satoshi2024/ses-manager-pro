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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, String newStatus) {
        Proposal proposal = this.getById(id);
        if (proposal == null) {
            throw new BusinessException("提案が見つかりません");
        }
        
        String oldStatus = proposal.getStatus();
        if (!ALLOWED.getOrDefault(oldStatus, Set.of()).contains(newStatus)) {
            throw new BusinessException("「" + oldStatus + "」から「" + newStatus + "」へは変更できません");
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
    }
}
