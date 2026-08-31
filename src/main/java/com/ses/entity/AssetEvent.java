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
 * 資産不変イベント台帳エンティティ (t_asset_event)
 * ※追記専用。論理削除フラグを持たない。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_asset_event")
public class AssetEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 資産ID (m_asset.id)。外部アカウント確認イベントではNULL。
     */
    private Long assetId;

    /** イベントの対象種別（例: EXTERNAL_ACCOUNT_REFERENCE）。 */
    private String referenceType;

    /** イベントの対象参照ID。 */
    private Long referenceId;

    /**
     * イベント種別: CREATED, ASSIGNED, RETURNED, TRANSFERRED, REPAIRED, REPORTED_LOST, REMOTE_WIPED, DISPOSED, INVENTORIED
     */
    private String eventType;

    /**
     * イベント発生日時
     */
    @Builder.Default
    private LocalDateTime eventTime = LocalDateTime.now();

    /**
     * 人間操作者の互換ID。SYSTEM/PROVIDERではNULL。
     */
    private Long actorUserId;

    /** 確認主体区分。 */
    private String actorType;

    /** 確認チャネル区分。 */
    private String confirmationSource;

    /** 人間確認時のユーザーID。actorUserIdとは同じ値を保持する。 */
    private Long humanUserId;

    /**
     * 貸与先区分: ENGINEER, USER
     */
    private String assigneeType;

    /**
     * 貸与先ID
     */
    private Long assigneeId;

    /**
     * 変更前ステータス
     */
    private String fromStatus;

    /**
     * 変更後ステータス
     */
    private String toStatus;

    /**
     * 関連証跡文書ID (t_document.id)
     */
    private Long evidenceDocId;

    /**
     * イベント要約
     */
    private String eventSummary;

    /**
     * 追加メタデータJSON (PII/Secret非含有)
     */
    private String detailsJson;

    /** 要求と確認を結ぶ相関ID。 */
    private String correlationId;

    /** 冪等性キー（外部アカウント確認イベントの場合）。 */
    private String idempotencyKey;

    /**
     * 作成日時
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
