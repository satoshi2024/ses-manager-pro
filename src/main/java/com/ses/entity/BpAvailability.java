package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.time.LocalDate;

/**
 * 外部要員在庫エンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_bp_availability")
public class BpAvailability extends BaseEntity {

    private String initialName;
    private String bpCompany;
    private String skillsJson;
    private Long unitPrice;
    private LocalDate availableFrom;
    private Integer experienceYears;
    private String status;
    private Long promotedEngineerId;
    private String remarks;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
