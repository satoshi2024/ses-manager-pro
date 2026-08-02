package com.ses.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import java.math.BigDecimal;

/**
 * リードエンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_lead")
public class Lead extends BaseEntity {

    /**
     * 会社名
     */
    @NotBlank(message = "会社名は必須です")
    private String companyName;

    /** 正規化済み会社名。重複候補検索のSQL境界キー。 */
    private String companyNameNormalized;

    /**
     * 担当者名
     */
    private String contactName;

    /**
     * 担当者メール
     */
    private String contactEmail;

    /** 正規化済みメール。重複候補検索のSQL境界キー。 */
    private String contactEmailNormalized;

    /**
     * 担当者電話
     */
    private String contactPhone;

    /** 正規化済み電話番号。重複候補検索のSQL境界キー。 */
    private String contactPhoneNormalized;

    /**
     * リードソース
     */
    private String source;

    /** ROI算定用の獲得原価。NULLは原価未計測としてROIを表示しない。 */
    private BigDecimal sourceCost;

    /**
     * 担当営業ID
     */
    private Long ownerUserId;

    /**
     * ステータス(未対応/対応中/転換済/破棄)
     */
    private String status;

    /**
     * 転換先顧客ID
     */
    private Long convertedCustomerId;

    /**
     * 転換先商機ID
     */
    private Long convertedOpportunityId;

    /**
     * 楽観ロックバージョン
     */
    @Version
    private Integer version;
}
