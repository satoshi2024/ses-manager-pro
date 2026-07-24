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

    /**
     * アクティブな重複提案を検索する
     *
     * @param engineerId 要員ID
     * @param customerId 顧客ID
     * @param excludeId 除外する提案ID
     * @return アクティブな重複提案のリスト
     */
    List<ProposalKanbanDto> findActiveDuplicates(Long engineerId, Long customerId, Long excludeId);

    /**
     * 要員の提案履歴を取得する
     *
     * @param engineerId 要員ID
     * @return 提案履歴のリスト
     */
    List<ProposalKanbanDto> getProposalHistory(Long engineerId);
}
