package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 棚卸し明細台帳エンティティ (t_asset_inventory_item)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_asset_inventory_item")
public class AssetInventoryItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 棚卸し実施ID (t_asset_inventory_run.id)
     */
    private Long inventoryRunId;

    /**
     * 対象資産ID (m_asset.id)
     */
    private Long assetId;

    /**
     * 台帳上ステータス
     */
    private String expectedStatus;

    /**
     * 台帳上保管場所/貸与先
     */
    private String expectedLocation;

    /**
     * 実地確認ステータス
     */
    private String observedStatus;

    /**
     * 実地確認場所
     */
    private String observedLocation;

    /**
     * 差異区分: UNCHECKED, MATCH, DISCREPANCY, MISSING, UNREGISTERED
     */
    @Builder.Default
    private String discrepancyType = "UNCHECKED";

    /**
     * 差異理由
     */
    private String discrepancyReason;

    /**
     * 是正措置
     */
    private String resolutionAction;

    /**
     * 確認者ユーザーID
     */
    private Long checkedBy;

    /**
     * 確認日時
     */
    private LocalDateTime checkedAt;

    /**
     * 作成日時
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新日時
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
