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
 * 連携ジョブ状態遷移履歴 (t_integration_job_event)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_integration_job_event")
public class IntegrationJobEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** ジョブID */
    private Long jobId;

    /** 遷移前状態 */
    private String fromStatus;

    /** 遷移後状態 */
    private String toStatus;

    /** 発生日時 */
    private LocalDateTime occurredAt;

    /** 安全な詳細情報 (PII/Secret除外) */
    private String safeDetail;

    public String getEventType() {
        return toStatus != null ? toStatus : fromStatus;
    }
}
