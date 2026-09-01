package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** SLA通知の受信者解決・配信失敗を保持する冪等台帳。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_service_sla_escalation")
public class ServiceSlaEscalation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long serviceRequestId;
    private Long slaClockId;
    private Integer roundNo;
    private String breachType;
    private String stage;
    private String dedupeKey;
    private Integer recipientCount;
    private String status;
    private Integer attemptCount;
    private String lastError;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime nextRetryAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
