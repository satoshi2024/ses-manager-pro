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
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<ProposalKanbanDto> getKanbanPage(String status, Long current, Long size, String keyword);

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

    /**
     * スキルシートを生成して提案へ保存し、保存ファイル名を返す。
     *
     * @param proposalId 提案ID
     * @param anonymize  匿名化するか
     * @param template   テンプレート（任意）
     * @param format     PDF または EXCEL
     * @return 保存ファイル名
     */
    String exportAndSaveSkillSheet(Long proposalId, boolean anonymize, String template, String format);
}
