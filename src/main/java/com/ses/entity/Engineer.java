package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Min;
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

    @NotBlank(message = "氏名は必須です")
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
     * 希望単価(円/月)。画面・API・DBとも円で扱う（例: 70万円 = 700000）。
     */
    @PositiveOrZero(message = "希望単価は0以上で入力してください")
    private BigDecimal expectedUnitPrice;

    /** 要員の既定原価部門。契約に明示がある場合は契約を優先する。 */
    private Long costCenterId;

    /**
     * 所属組織。管理会計の帰属と組織スコープの一次情報。
     * アカウント連携({@code t_engineer_account_link})は要員セルフサービスを使う要員にしか存在しないため、
     * ここがNULLのときだけ連携ユーザーの主所属で解決する。
     */
    private Long organizationId;
    
    private LocalDate availableDate;
    @Min(value = 0, message = "経験年数は0以上で入力してください")
    private Integer experienceYears;
    private String japaneseLevel;
    private String resumeSummary;
    private String photoUrl;
    private String remarks;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
