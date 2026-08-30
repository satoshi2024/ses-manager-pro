package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 有償ライセンスプランマスタエンティティ (m_license_plan)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_license_plan")
public class LicensePlan extends BaseEntity {

    /**
     * プランコード (例: LIC-M365-E5, LIC-GITHUB-ENT)
     */
    private String planCode;

    /**
     * プラン名
     */
    private String planName;

    /**
     * 外部システムID (m_external_account_system.id)
     */
    private Long systemId;

    /**
     * 購入ライセンス席数上限
     */
    private Integer seatLimit;

    /**
     * 現在割当数 (CAS保護)
     */
    @Builder.Default
    private Integer allocatedCount = 0;

    /**
     * 1席あたり月額単価 (円)
     */
    private BigDecimal costPerSeat;

    /**
     * 費用負担組織/Cost Center ID
     */
    private Long costCenterId;

    /**
     * ライセンス契約満了日
     */
    private LocalDate expiryDate;

    /**
     * ステータス: ACTIVE, EXPIRED, TERMINATED
     */
    @Builder.Default
    private String status = "ACTIVE";

    /**
     * 楽観ロック用バージョン
     */
    @Version
    @Builder.Default
    private Integer version = 0;
}
