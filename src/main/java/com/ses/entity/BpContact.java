package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.*;

/**
 * BP担当者連絡先エンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_bp_contact")
public class BpContact extends BaseEntity {

    private Long tenantId;
    private Long bpCompanyId;
    private String name;
    private String department;
    private String role;
    private String email;
    private String phone;
    private Integer primaryFlag;
}
