package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/** 派遣先の就業事業所マスタ。住所・組織の変更履歴はprofile snapshotへ取り込む。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_workplace")
public class Workplace extends BaseEntity {

    private String tenantId;
    private Long customerId;
    private Long organizationId;
    private String name;
    private String address;
    private String organizationUnit;
    private String phone;
    private LocalDate validFrom;
    private LocalDate validTo;
    private String status;

    @Version
    private Integer version;
}
