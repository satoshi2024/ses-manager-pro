package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 資産台帳エンティティ (m_asset)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_asset")
public class Asset extends BaseEntity {

    /**
     * 全社一意資産管理番号 (例: AST-PC-2026-0001)
     */
    private String assetTag;

    /**
     * 製造番号/シリアルNo
     */
    private String serialNo;

    /**
     * 資産名称 (例: ThinkPad T14 Gen4)
     */
    private String assetName;

    /**
     * 資産区分: PC, MONITOR, SMARTPHONE, SECURITY_KEY, TABLET, OTHER
     */
    private String category;

    /**
     * 所有法人ID (m_organization_unit.legal_entity_id)
     */
    private Long ownerCompanyId;

    /**
     * ステータス: IN_STOCK, ASSIGNED, UNDER_MAINTENANCE, LOST, DISPOSED, RESERVED
     */
    @Builder.Default
    private String status = "IN_STOCK";

    /**
     * 保管場所/拠点
     */
    private String location;

    /**
     * 取得日
     */
    private LocalDate purchaseDate;

    /**
     * 取得価格 (円)
     */
    private BigDecimal purchasePrice;

    /**
     * メーカー保証満了日
     */
    private LocalDate warrantyExpiry;

    /**
     * リース満了日
     */
    private LocalDate leaseExpiry;

    /**
     * 備考
     */
    private String note;

    /**
     * 楽観ロック用バージョン
     */
    @Version
    @Builder.Default
    private Integer version = 0;
}
