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
 * SLA計時・ラウンド履歴エンティティ（t_service_sla_clock）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_service_sla_clock")
public class ServiceSlaClock {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** サービスリクエストID */
    private Long serviceRequestId;

    /** ラウンド番号 (1:初回, 2:再オープン後...) */
    private Integer roundNo;

    /** 適用SLAポリシーID */
    private Long policyId;

    /** 初回応答期限日時 */
    private LocalDateTime responseDeadline;

    /** 解決目標期限日時 */
    private LocalDateTime resolveDeadline;

    /** 実初回応答日時 */
    private LocalDateTime firstRespondedAt;

    /** 初回応答超過フラグ */
    private Boolean responseBreached;

    /** 実解決日時 */
    private LocalDateTime resolvedAt;

    /** 解決目標超過フラグ */
    private Boolean resolveBreached;

    /** 顧客確認待ち等による累計停止時間 (分) */
    private Integer totalPauseMinutes;

    /** 直近停止開始日時 */
    private LocalDateTime lastPausedAt;

    /** 計時状態 (RUNNING, PAUSED, COMPLETED) */
    private String status;

    /** 楽観ロックバージョン */
    @Version
    private Integer version;

    /** 作成日時 */
    private LocalDateTime createdAt;

    /** 更新日時 */
    private LocalDateTime updatedAt;
}
