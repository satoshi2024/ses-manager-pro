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

/** 組織マスタエンティティ。所属・実績の期間履歴を上書きせず保持する。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("m_organization_unit")
public class OrganizationUnit extends BaseEntity {

    private Long tenantId;
    private Long legalEntityId;
    private String code;
    private String name;
    private String type;
    private Long parentId;
    private LocalDate validFrom;
    private LocalDate validTo;
    private String status;
    /** 統合先組織。旧組織の履歴を残したまま参照先を明示する。 */
    private Long mergedInto;

    @Version
    private Integer version;
}
