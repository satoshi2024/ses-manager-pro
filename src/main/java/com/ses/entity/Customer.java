package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 顧客マスタエンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_customer")
public class Customer extends BaseEntity {

    /**
     * 会社名
     */
    @NotBlank(message = "会社名は必須です")
    private String companyName;

    /**
     * フリガナ
     */
    private String companyNameKana;

    /**
     * 担当者名
     */
    private String contactPerson;

    /**
     * メール
     */
    private String contactEmail;

    /**
     * 電話番号
     */
    private String contactPhone;

    /**
     * 住所
     */
    private String address;

    /**
     * 商流位置 (元請/一次請/二次請)
     */
    private String commercialFlow;

    /**
     * 信頼度ランク (S, A, B, C)
     */
    private String trustLevel;

    /**
     * 備考
     */
    private String remarks;
}
