package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.time.LocalDate;

/**
 * BP要員所属履歴エンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_engineer_bp_affiliation")
public class EngineerBpAffiliation extends BaseEntity {

    private Long tenantId;
    private Long engineerId;
    private Long bpCompanyId;
    private LocalDate validFrom;
    private LocalDate validTo;
}
