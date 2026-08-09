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

/** 法人・組織・個人ごとの有効期間付き勤務カレンダー。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("m_work_calendar")
public class WorkCalendar extends BaseEntity {
    private Long legalEntityId;
    private Long organizationId;
    private Long engineerId;
    private String name;
    private LocalDate validFrom;
    private LocalDate validTo;
    private String status;

    @Version
    private Integer version;
}
