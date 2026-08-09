package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 直接雇用時の紛争防止措置（DIRECT_HIRE_DISPUTE_HISTORY）。
 * 紹介可能契約だけ条件付きappend-only。訂正・取消は新event INSERTで行い、旧行は不変。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_direct_hire_dispute_history")
public class DirectHireDisputeHistory extends BaseEntity {

    private String tenantId;
    private Long contractId;
    private Long workerId;
    private String eventId;
    private String eventType;
    private String supersedesEventId;
    private String correctionReason;
    private Long actorUserId;
    private LocalDateTime occurredAt;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String measure;
    private String feeDetail;
    private String requestMethod;

    @Version
    private Integer version;
}
