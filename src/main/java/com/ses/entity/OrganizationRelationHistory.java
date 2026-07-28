package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 組織の親子関係・状態の履歴。
 *
 * <p>{@code m_organization_unit} は現在値しか持たないため、統合や親付け替えを行うと
 * 「昨日の組織ツリー」を問い合わせても今日の結果が返り、過去のscope・部門損益・監査結果が
 * 後から変わってしまう。asOf 指定の解決はこのテーブルを正とする。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_organization_relation_history")
public class OrganizationRelationHistory extends BaseEntity {

    private Long organizationId;
    private Long parentId;
    private String status;
    private LocalDate validFrom;
    /** NULL は現行版。 */
    private LocalDate validTo;
}
