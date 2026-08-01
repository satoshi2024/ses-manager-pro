package com.ses.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

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

    /**
     * 担当者名
     */
    private String contactName;

    /**
     * 担当者メール
     */
    private String contactEmail;

    /**
     * 担当者電話
     */
    private String contactPhone;

    /**
     * リードソース
     */
    private String source;

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
