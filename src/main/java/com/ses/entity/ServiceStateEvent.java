package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * サービスリクエスト状態変更監査イベントエンティティ（t_service_state_event）
 * 追記専用（UPDATE/DELETE禁止）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_service_state_event")
public class ServiceStateEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** サービスリクエストID */
    private Long serviceRequestId;

    /** ラウンド番号 */
    private Integer roundNo;

    /** 変更前ステータス */
    private String fromStatus;

    /** 変更後ステータス */
    private String toStatus;

    /** 変更理由 */
    private String reason;

    /** 実行者種別 (INTERNAL_USER, PORTAL_USER, SYSTEM) */
    private String actorType;

    /** 実行者ID */
    private Long actorId;

    /** 実行者名 */
    private String actorName;

    /** 作成日時 */
    private LocalDateTime createdAt;
}
