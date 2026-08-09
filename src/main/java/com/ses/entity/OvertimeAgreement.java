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

/** 法人別36協定。法人行が無い場合の適合判定はcalculatorでUNKNOWNにする。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("m_overtime_agreement")
public class OvertimeAgreement extends BaseEntity {
    private Long legalEntityId;
    private LocalDate validFrom;
    private LocalDate validTo;
    private Integer specialClause;
    private Integer normalMonthLimitMinutes;
    private Integer normalYearLimitMinutes;
    private Integer specialYearLimitMinutes;
    private Integer totalMonthLimitMinutes;
    private Integer multiMonthAverageLimitMinutes;
    private Integer exceedMonthCountLimit;
    private Integer warningThresholdPercent;
    private String warningRecipients;
    private String configJson;

    @Version
    private Integer version;
}
