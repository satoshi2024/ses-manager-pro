package com.ses.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ses.entity.AssetAssignment;

import java.time.LocalDate;
import java.util.List;

/**
 * 資産貸与・返却管理サービス
 */
public interface AssetAssignmentService {

    /**
     * 資産を貸与する（トランザクション内行ロック ＋ 期間重複チェック ＋ 資産ステータス変更 ＋ イベント記録）
     */
    AssetAssignment createAssignment(Long assetId,
                                     String assigneeType,
                                     Long assigneeId,
                                     LocalDate startDate,
                                     LocalDate expectedReturnDate,
                                     Long handoverEvidenceDocId,
                                     String note,
                                     Long actorUserId);

    /**
     * 貸与資産を返却する（実際の返却日記録 ＋ 証跡記録 ＋ 資産ステータスIN_STOCK復帰 ＋ イベント記録）
     */
    AssetAssignment returnAssignment(Long assignmentId,
                                     LocalDate actualReturnDate,
                                     Long returnEvidenceDocId,
                                     String note,
                                     Long actorUserId);

    /**
     * 貸与を免除・例外完了する
     */
    AssetAssignment waiveAssignment(Long assignmentId,
                                    String reason,
                                    Long approvalRequestId,
                                    Long actorUserId);

    /**
     * 要員またはユーザーの有効貸与一覧を取得する
     */
    List<AssetAssignment> getActiveAssignmentsByAssignee(String assigneeType, Long assigneeId);

    /**
     * 資産ごとの貸与履歴を取得する
     */
    List<AssetAssignment> getAssignmentHistoryByAssetId(Long assetId);

    /**
     * 貸与一覧検索（ページネーション）
     */
    IPage<AssetAssignment> searchAssignments(int page, int size, String assigneeType, Long assigneeId, String status);

    /** 貸与履歴の論理削除を常に拒否する明示的な境界API。 */
    void softDeleteAssignment(Long id);
}
