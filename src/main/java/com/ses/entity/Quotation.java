package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 見積エンティティ。
 */
@Data
@TableName("t_quotation")
public class Quotation {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String quotationNo;

    @NotNull(message = "顧客は必須です")
    private Long customerId;

    private Long projectId;
    private Long engineerId;
    /** 任意紐付け（参照のみ）。 */
    private Long proposalId;

    @NotBlank(message = "件名は必須です")
    private String title;

    @NotNull(message = "単価は必須です")
    @PositiveOrZero(message = "単価は0以上で入力してください")
    private BigDecimal unitPrice;

    private BigDecimal settlementHoursMin;
    private BigDecimal settlementHoursMax;
    private LocalDate validUntil;

    /** '下書き','提出済','受注','失注' */
    private String status;

    private String remarks;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deletedFlag;
}
