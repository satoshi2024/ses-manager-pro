package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 棚卸し実施台帳エンティティ (t_asset_inventory_run)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_asset_inventory_run")
public class AssetInventoryRun extends BaseEntity {

    /**
     * 棚卸しコード (例: INV-2026-H1)
     */
    private String inventoryCode;

    /**
     * 棚卸し名称
     */
    private String title;

    /**
     * 基準日
     */
    private LocalDate targetDate;

    /**
     * ステータス: DRAFT, IN_PROGRESS, COMPLETED, CANCELLED
     */
    @Builder.Default
    private String status = "IN_PROGRESS";

    /**
     * 対象総資産数
     */
    @Builder.Default
    private Integer totalAssets = 0;

    /**
     * 一致件数
     */
    @Builder.Default
    private Integer matchedCount = 0;

    /**
     * 差異件数
     */
    @Builder.Default
    private Integer discrepancyCount = 0;

    /**
     * 所在不明件数
     */
    @Builder.Default
    private Integer missingCount = 0;

    /**
     * 実施責任者ユーザーID
     */
    private Long conductedBy;

    /**
     * 完了日時
     */
    private LocalDateTime completedAt;

    /**
     * 楽観ロック用バージョン
     */
    @Version
    @Builder.Default
    private Integer version = 0;
}
