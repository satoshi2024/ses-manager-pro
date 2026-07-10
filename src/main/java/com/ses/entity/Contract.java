package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 契約エンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_contract")
public class Contract extends BaseEntity {

    /** 契約番号 */
    private String contractNo;

    /** 提案ID */
    private Long proposalId;

    /** エンジニアID */
    private Long engineerId;

    /** 案件ID */
    private Long projectId;

    /** 顧客ID */
    private Long customerId;

    /**
     * 契約形態
     * '準委任','請負','派遣'
     */
    private String contractType;

    /** 契約開始日 */
    private LocalDate startDate;

    /** 契約終了日 */
    private LocalDate endDate;

    /** 売上単価 */
    private BigDecimal sellingPrice;

    /** 原価 */
    private BigDecimal costPrice;

    /** 精算基準時間（下限） */
    private BigDecimal settlementHoursMin;

    /** 精算基準時間（上限） */
    private BigDecimal settlementHoursMax;

    /** 端数処理ルール */
    private String fractionRule;

    /** 自動更新 (1:する, 0:しない) */
    private Integer autoRenew;

    /**
     * ステータス
     * '準備中','稼動中','終了','解約'
     */
    private String status;

    /** 備考 */
    private String remarks;

    /** 作成者ID */
    private Long createdBy;
}
