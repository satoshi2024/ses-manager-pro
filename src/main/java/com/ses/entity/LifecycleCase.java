package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ライフサイクル案件インスタンスエンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_lifecycle_case")
public class LifecycleCase extends BaseEntity {

    /**
     * 案件番号 (例: LC-202608-0001)
     */
    private String caseNo;

    /**
     * ライフサイクル種別 (JOIN, ASSIGNMENT, TRANSFER, LEAVE, REINSTATEMENT, RESIGNATION)
     */
    private String lifecycleType;

    /**
     * 対象要員ID
     */
    private Long engineerId;

    /**
     * 適用テンプレートID
     */
    private Long templateId;

    /**
     * 適用テンプレート版番号スナップショット
     */
    private Integer templateVersion;

    /**
     * 基準日 (入社日、異動日、退社日等)
     */
    private LocalDate anchorDate;

    /**
     * ステータス (DRAFT, ACTIVE, ON_HOLD, COMPLETED, CANCELLED)
     */
    @Builder.Default
    private String status = "DRAFT";

    /**
     * 案件タイトル
     */
    private String title;

    /**
     * 特記事項・備考
     */
    private String remarks;

    /**
     * 起票者ユーザーID
     */
    private Long applicantUserId;

    /**
     * 起票時点の要員・組織・営業スナップショットJSON
     */
    private String engineerSnapshotJson;

    /**
     * 案件完了日時
     */
    private LocalDateTime completedAt;

    /**
     * 完了確定ユーザーID
     */
    private Long completedBy;

    /**
     * 楽観ロックバージョン
     */
    @Version
    @Builder.Default
    private Integer version = 0;

    /**
     * 作成者
     */
    private Long createdBy;

    /**
     * 更新者
     */
    private Long updatedBy;
}
