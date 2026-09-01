package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 顧客ヘルススナップショットエンティティ（t_customer_health_snapshot）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_customer_health_snapshot")
public class CustomerHealthSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 顧客ID */
    private Long customerId;

    /** スナップショット日付 */
    private LocalDate snapshotDate;

    /** 版番号 (1, 2, ...) */
    private Integer versionNo;

    /** ヘルスステータス (HEALTHY, WARNING, CRITICAL) */
    private String healthStatus;

    /** 総合スコア (0-100) */
    private Integer totalScore;

    public Integer getHealthScore() {
        return totalScore;
    }

    public void setHealthScore(Integer healthScore) {
        this.totalScore = healthScore;
    }

    /** 未解決重大障害(P0/P1)件数 */
    private Integer openCriticalIssuesCount;

    /** 直近30日SLA違反件数 */
    @com.baomidou.mybatisplus.annotation.TableField("sla_breach_count_30d")
    private Integer slaBreachCount30d;

    /** 直近90日平均CSATスコア */
    private BigDecimal avgCsatScore;

    /** 売掛金延滞フラグ */
    private Boolean arOverdueFlag;

    /** 欠損入力項目JSON */
    private String missingInputsJson;

    /** スコア算出根拠説明テキスト */
    private String factorsExplanation;

    /** 計算結果ハッシュ */
    private String snapshotHash;

    /** 改定・修正理由 */
    private String revisionReason;

    /** 実行者種別 (SYSTEM, INTERNAL_USER) */
    private String actorType;

    /** 実行者ID */
    private Long actorId;

    /** 実行者名 */
    private String actorName;

    /** 互換表示用フラグ。最新版はversion_no最大値で判定し、履歴行は更新しない。 */
    private Boolean isCurrent;

    /** レコード作成日時 */
    private LocalDateTime createdAt;
}
