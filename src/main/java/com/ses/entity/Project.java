package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 案件エンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_project")
public class Project extends BaseEntity {

    /**
     * 案件名
     */
    private String projectName;

    /**
     * 顧客ID
     */
    private Long customerId;

    /**
     * 商流位置
     */
    private String commercialFlow;

    /**
     * 案件概要
     */
    private String description;

    /**
     * 募集人数
     */
    private Integer requiredCount;

    /**
     * 単価(下限)
     */
    private BigDecimal unitPriceMin;

    /**
     * 単価(上限)
     */
    private BigDecimal unitPriceMax;

    /**
     * 作業場所
     */
    private String workLocation;

    /**
     * リモート状況
     */
    private String remoteType;

    /**
     * 開始日
     */
    private LocalDate startDate;

    /**
     * 終了予定日
     */
    private LocalDate endDate;

    /**
     * ステータス
     */
    private String status;

    /**
     * 優先度
     */
    private String priority;

    /**
     * 備考
     */
    private String remarks;

    /**
     * 登録者ID
     */
    private Long createdBy;
}
