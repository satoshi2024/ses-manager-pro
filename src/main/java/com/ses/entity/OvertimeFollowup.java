package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 時間外警告と健康対応の状態。診療詳細は保存しない。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_overtime_followup")
public class OvertimeFollowup extends BaseEntity {
    private Long engineerId;
    private LocalDate periodMonth;
    private String warningCode;
    private String status;
    private LocalDateTime notifiedAt;
    private String healthActionStatus;

    @Version
    private Integer version;
}
