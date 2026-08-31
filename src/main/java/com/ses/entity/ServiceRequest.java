package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * サービスリクエストエンティティ（t_service_request）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_service_request")
public class ServiceRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** リクエスト管理番号 REQ-YYYYMM-XXXX */
    private String requestNo;

    /** 顧客ID */
    private Long customerId;

    /** 顧客担当者ID */
    private Long contactId;

    /** 契約ID */
    private Long contractId;

    /** 案件ID */
    private Long projectId;

    /** 要員ID */
    private Long engineerId;

    /** カテゴリ (CONTRACT, BILLING, ATTENDANCE, QUALITY, SYSTEM, OTHER) */
    private String category;

    /** 優先度 (P0, P1, P2, P3) */
    private String priority;

    /** 受付チャネル (PORTAL, EMAIL, PHONE, MEETING, INTERNAL) */
    @Builder.Default
    private String channel = "INTERNAL";

    /** 件名 */
    private String subject;

    /** 詳細内容 */
    private String description;

    /** 社内主担当者ID (sys_user.id) */
    private Long ownerUserId;

    /** ステータス (RECEIVED, IN_PROGRESS, WAITING_CUSTOMER, RESOLVED, CLOSED) */
    private String status;

    /** 初回応答日時 */
    private LocalDateTime firstResponseAt;

    /** 解決日時 */
    private LocalDateTime resolvedAt;

    /** 完了日時 */
    private LocalDateTime closedAt;

    /** 最新再オープン日時 */
    private LocalDateTime reopenedAt;

    /** 再オープン回数 */
    private Integer reopenCount;

    /** 起票元ポータルユーザーID */
    private Long portalUserId;

    /** 作成者ID (sys_user.id) */
    private Long createdBy;

    /** 更新者ID */
    private Long updatedBy;

    /** 楽観ロックバージョン */
    @Version
    private Integer version;

    /** 作成日時 */
    private LocalDateTime createdAt;

    /** 更新日時 */
    private LocalDateTime updatedAt;
}
