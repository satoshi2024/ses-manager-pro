package com.ses.dto.engineersales;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 要員担当営業の表示用DTO（sys_user と join して営業名を持つ）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EngineerSalesDto {

    /** 割当ID (t_engineer_sales.id) */
    private Long id;

    /** 要員ID */
    private Long engineerId;

    /** 担当営業ユーザーID */
    private Long salesUserId;

    /** 担当営業氏名 */
    private String salesUserName;

    /** 主担当フラグ (1:主担当) */
    private Integer primaryFlag;

    /** 担当開始日 */
    private LocalDate assignedAt;

    /** 担当解除日（NULL=現任） */
    private LocalDate releasedAt;

    /** 備考 */
    private String remarks;
}
