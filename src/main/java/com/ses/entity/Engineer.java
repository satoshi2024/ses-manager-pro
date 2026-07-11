package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 要員テーブルエンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_engineer")
public class Engineer extends BaseEntity {

    private String fullName;
    private String fullNameKana;
    private String initialName;
    private String gender;
    private LocalDate birthDate;
    private String nationality;
    private String nearestStation;

    /** 最寄り駅の都道府県 */
    private String prefecture;

    /** 最寄り駅の鉄道会社・路線 */
    private String railwayCompany;

    /**
     * 雇用形態: 正社員, 契約社員, BP
     */
    private String employmentType;
    
    /**
     * 稼動状態: 稼動中, 退場予定, Bench, 提案中
     */
    private String status;
    
    /**
     * 希望単価(万円/月)
     */
    private BigDecimal expectedUnitPrice;
    
    private LocalDate availableDate;
    private Integer experienceYears;
    private String japaneseLevel;
    private String resumeSummary;
    private String photoUrl;
    private String remarks;
    private Long createdBy;
}
