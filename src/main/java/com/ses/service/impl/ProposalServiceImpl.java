package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.dto.proposal.ProposalKanbanDto;
import com.ses.entity.Proposal;
import com.ses.entity.ProposalHistory;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.ProposalHistoryMapper;
import com.ses.service.ProposalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 提案サービス実装
 */
@Service
@RequiredArgsConstructor
public class ProposalServiceImpl extends ServiceImpl<ProposalMapper, Proposal> implements ProposalService {

    private final ProposalHistoryMapper proposalHistoryMapper;

    @Override
    public List<ProposalKanbanDto> getKanbanList() {
        // TODO: 要件に応じてMyBatisのXML等でJOINしたクエリを作成して取得する
        // 現時点ではモックデータを返す
        List<ProposalKanbanDto> list = new ArrayList<>();
        ProposalKanbanDto mock1 = new ProposalKanbanDto();
        mock1.setId(1L);
        mock1.setEngineerName("山田 太郎");
        mock1.setEngineerInitial("Y.T");
        mock1.setProjectName("Webアプリケーション開発");
        mock1.setCustomerName("株式会社サンプル");
        mock1.setStatus("書類選考中");
        list.add(mock1);
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, String newStatus) {
        Proposal proposal = this.getById(id);
        if (proposal != null) {
            String oldStatus = proposal.getStatus();
            
            // 提案ステータスの更新
            proposal.setStatus(newStatus);
            this.updateById(proposal);

            // 提案履歴の作成
            ProposalHistory history = new ProposalHistory();
            history.setProposalId(id);
            history.setFromStatus(oldStatus);
            history.setToStatus(newStatus);
            history.setChangedAt(LocalDateTime.now());
            history.setRemarks("ステータス変更");
            proposalHistoryMapper.insert(history);
        }
    }
}
