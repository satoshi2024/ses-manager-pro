package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 注文エンティティ（t_sales_order）。
 * 顧客から受領する注文書と、自社が返す注文請書を管理する。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sales_order")
public class SalesOrder extends BaseEntity {

    /** テナントID（独立DB方式のため既定 'default'） */
    private String tenantId;

    /** 法人ID（将来multi-entity用） */
    private Long legalEntityId;

    /** 注文番号 */
    private String orderNo;

    /** 顧客PO番号 */
    private String customerPoNo;

    /** 顧客ID */
    @NotNull(message = "顧客は必須です")
    private Long customerId;

    /** 顧客担当者ID */
    private Long contactId;

    /** 生成元見積ID（nullable UNIQUE: uk_sales_order_quotation。見積由来は1見積1注文） */
    private Long quotationId;

    /** 注文日 */
    @NotNull(message = "注文日は必須です")
    private LocalDate orderDate;

    /** 期間開始日 */
    private LocalDate startDate;

    /** 期間終了日 */
    private LocalDate endDate;

    /**
     * 状態: 下書き / 受領確認 / 注文請提出 / 契約化 / 完了 / 取消
     * 遷移は SalesOrderServiceImpl の状態機械（design §5.3）が唯一の正。
     */
    private String status;

    /** 注文確定時点の総額snapshot（下書きはNULL） */
    private BigDecimal totalAmountSnapshot;

    /** 注文確定時点の支払条件snapshot（下書きはNULL） */
    private String paymentTermsSnapshot;

    /** 受領注文書document ID */
    private Long sourceDocumentId;

    /** 注文請書document ID */
    private Long acknowledgementDocumentId;

    /** 楽観ロックバージョン */
    @Version
    private Integer version;

    /** 作成者ID */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
