package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.time.LocalDate;

/**
 * 資産貸与履歴台帳エンティティ (t_asset_assignment)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_asset_assignment")
public class AssetAssignment extends BaseEntity {

    /**
     * 対象資産ID (m_asset.id)
     */
    private Long assetId;

    /**
     * 貸与先区分: ENGINEER, USER
     */
    private String assigneeType;

    /**
     * 要員IDまたはユーザーID
     */
    private Long assigneeId;

    /**
     * 貸与開始日
     */
    private LocalDate startDate;

    /**
     * 返却予定日
     */
    private LocalDate expectedReturnDate;

    /**
     * 実際の返却日 (NULL=現在貸与中)
     */
    private LocalDate actualReturnDate;

    /**
     * 受渡し証跡文書ID (t_document.id)
     */
    private Long handoverEvidenceDocId;

    /**
     * 返却証跡文書ID (t_document.id)
     */
    private Long returnEvidenceDocId;

    /**
     * 状態: ACTIVE, RETURNED, OVERDUE, WAIVED
     */
    @Builder.Default
    private String status = "ACTIVE";

    /**
     * 貸与メモ
     */
    private String note;

    /**
     * 楽観ロック用バージョン
     */
    @Version
    @Builder.Default
    private Integer version = 0;

    /**
     * 登録者ユーザーID
     */
    private Long createdBy;
}
