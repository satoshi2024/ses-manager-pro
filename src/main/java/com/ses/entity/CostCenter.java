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

/** 原価部門マスタエンティティ。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("m_cost_center")
public class CostCenter extends BaseEntity {

    private Long legalEntityId;
    private String code;
    private String name;
    private Long organizationId;
    private LocalDate validFrom;
    private LocalDate validTo;
    private String status;

    @Version
    private Integer version;
}
