package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 承認route定義エンティティ。current=有効かつ期間内、history=version_no+valid_from/to。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("m_approval_route")
public class ApprovalRoute extends BaseEntity {

    private String tenantId;
    private String requestType;
    /** NULLは全role対象。値があるrouteは同一条件の汎用routeより優先される。 */
    private String applicantRoleCondition;
    private Long organizationId;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private Integer versionNo;
    private LocalDate validFrom;
    private LocalDate validTo;
    private Integer activeFlag;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /** 旧テスト/seedの数値tenant指定を、永続化する文字列tenantへ明示変換する互換builder。 */
    public static class ApprovalRouteBuilder {
        public ApprovalRouteBuilder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public ApprovalRouteBuilder tenantId(Long tenantId) {
            if (tenantId == null) {
                this.tenantId = null;
            } else if (tenantId == 1L) {
                this.tenantId = "default";
            } else {
                this.tenantId = String.valueOf(tenantId);
            }
            return this;
        }
    }
}
