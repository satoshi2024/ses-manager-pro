package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 紛失資産インシデント台帳。秘密情報は保持せず、初動・外部対応・関連文書の状態だけを記録する。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_asset_lost_incident")
public class AssetLostIncident extends BaseEntity {

    private Long assetId;
    private LocalDateTime reportedAt;
    private Long reportedBy;
    private String incidentDetails;
    private String remoteWipeStatus;
    private LocalDateTime remoteWipeRequestedAt;
    private LocalDateTime remoteWipeExecutedAt;
    private LocalDateTime remoteWipeConfirmedAt;
    private String policeReportNumber;
    private String insuranceClaimStatus;
    private LocalDateTime insuranceClaimedAt;

    /** 対応情報更新の楽観ロック用バージョン。 */
    @Version
    private Integer version;

    /** t_document_link.target_type=ASSET_LOST_INCIDENT の関連文書ID一覧。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private List<Long> relatedDocumentIds;
}
