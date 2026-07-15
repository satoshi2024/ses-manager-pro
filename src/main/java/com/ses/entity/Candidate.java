package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 採用候補者エンティティ。
 *
 * 注意: {@code currentStage} は {@code t_candidate_activity} の最新ステージを
 * キャッシュした非正規化カラムである。整合性を保つため、ステージ変更は必ず
 * {@link com.ses.service.CandidateService#changeStage} 経由で行い、
 * このエンティティ/Mapperを直接updateしてステージを変更してはならない。
 * (design.md 5章「currentStage非正規化のズレ」を参照)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_candidate")
public class Candidate extends BaseEntity {

    @NotBlank(message = "氏名は必須です")
    private String name;

    private String contactEmail;

    private String contactPhone;

    private String skillSummary;

    private BigDecimal desiredRate;

    /** 情報源: 紹介/エージェント/自社応募等 */
    private String source;

    /**
     * 現在のステージ(非正規化キャッシュ)。
     * 応募受付/書類選考/一次面談/最終面談/内定/内定辞退/入社/不採用
     */
    private String currentStage;

    private LocalDate nextActionDate;

    /** 入社後に変換したt_engineer.idへの紐付け(未変換はnull) */
    private Long convertedEngineerId;

    private String remarks;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
