package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * ポータル組織エンティティ（m_portal_organization）。
 * 顧客組織/BP組織の外部identity。customer_id / bp_company_id へ1:1で紐付き、
 * portal userの認可母集団の起点となる（design §6.2。本specはplatform-invariants §2の
 * 既定解が適用できない唯一のspecであり、既存scope serviceを流用しない）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_portal_organization")
public class PortalOrganization extends BaseEntity {

    /** テナントID（独立DB方式のため既定'default'） */
    private String tenantId;

    /** 組織種別: CUSTOMER / BP */
    private String type;

    /** 顧客ID（type=CUSTOMER時。1顧客1組織） */
    private Long customerId;

    /** BP会社ID（type=BP時。1BP会社1組織） */
    private Long bpCompanyId;

    /** 状態: ACTIVE / SUSPENDED（停止時は全portal session失効） */
    private String status;
}
