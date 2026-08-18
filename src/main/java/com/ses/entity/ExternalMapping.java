package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 外部マスタマッピング (m_external_mapping)。
 * 勘定科目、税区分、部門、取引先等の内部キーと外部IDを対応付ける。
 * verified_at IS NULL は未検証（送信停止）を表す。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_external_mapping")
public class ExternalMapping {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接続ID */
    private Long connectionId;

    /** マッピング対象種別 (CUSTOMER_PARTNER, BP_PARTNER, ACCOUNT_SALES, TAX_SALES_10, SECTION等) */
    private String objectType;

    /** 内部エンティティID (顧客ID, BP企業ID, 要員ID, CostCenterID等) */
    private Long internalId;

    /** 内部コード/キー */
    private String internalCode;

    /** 外部システムID */
    private String externalId;

    /** 外部システムコード */
    private String externalCode;

    /** 検証時点の外部マスタスナップショットJSON */
    private String payloadSnapshot;

    /** 検証日時 (NULL=未検証) */
    private LocalDateTime verifiedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deletedFlag;

    @Version
    private Integer version;
}
