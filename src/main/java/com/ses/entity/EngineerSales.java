package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 要員担当営業エンティティ
 * 要員と営業ユーザーの担当関連を表す。履歴は released_at で表現する（NULL=現任）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_engineer_sales")
public class EngineerSales extends BaseEntity {

    /** 要員ID */
    private Long engineerId;

    /** 担当営業ユーザーID */
    private Long salesUserId;

    /** 主担当フラグ (1:主担当, 0:副担当) */
    private Integer primaryFlag;

    /** 担当開始日 */
    private LocalDate assignedAt;

    /** 担当解除日（NULL=現任） */
    private LocalDate releasedAt;

    /** 備考 */
    private String remarks;
}
