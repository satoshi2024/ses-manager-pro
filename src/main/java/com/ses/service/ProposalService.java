package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.Proposal;
import com.ses.dto.proposal.ProposalKanbanDto;

import java.util.List;

/**
 * 提案サービスインターフェース
 */
public interface ProposalService extends IService<Proposal> {

    /**
     * かんばんリスト取得
     *
     * @return 提案かんばんDTOリスト
     */
    List<ProposalKanbanDto> getKanbanList();

    /**
     * ステータス変更
     *
     * @param id        提案ID
     * @param newStatus 新ステータス
     */
    void changeStatus(Long id, String newStatus);
}
