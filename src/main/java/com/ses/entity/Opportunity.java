package com.ses.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 商機エンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_opportunity")
public class Opportunity extends BaseEntity {

    /**
     * 顧客ID
     */
    @NotNull(message = "顧客IDは必須です")
    private Long customerId;

    /**
     * 商機名
     */
    @NotBlank(message = "商機名は必須です")
    private String title;

    /**
     * ステージ(見込/要件確認/提案準備/見積提出/交渉/受注/失注)
     */
    private String stage;

    /**
     * 開始予定月(YYYY-MM)
     */
    private String expectedStartMonth;

    /**
     * 想定期間(月)
     */
    private Integer durationMonths;

    /**
     * 募集人数
     */
    private Integer requiredCount;

    /**
     * 想定単価(円)
     */
    private BigDecimal unitPrice;

    /**
     * 見込金額(円)
     */
    private BigDecimal expectedAmount;

    /**
     * 確度(%)
     */
    private Integer probability;

    private String probabilityOverrideReason;

    private java.time.LocalDateTime stageChangedAt;

    /**
     * 担当営業ID
     */
    private Long ownerUserId;

    /**
     * 次回アクション予定日
     */
    private LocalDate nextActionDate;

    /**
     * 競合情報
     */
    private String competitor;

    /**
     * 失注理由
     */
    private String lostReason;

    /**
     * 変換先案件ID
     */
    private Long convertedProjectId;

    /**
     * 変換先見積ID
     */
    private Long convertedQuotationId;

    /**
     * 楽観ロックバージョン
     */
    @Version
    private Integer version;
}
