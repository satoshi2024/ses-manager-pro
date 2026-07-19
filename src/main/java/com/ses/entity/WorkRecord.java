package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_work_record")
public class WorkRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long contractId;
    private String workMonth;
    private BigDecimal actualHours;
    private BigDecimal billingAmount;
    private BigDecimal paymentAmount;
    private String status;
    private String remarks;
    /** 差戻しコメント（差戻し時に必須保存、再提出でクリア）。業務備考 remarks とは別項目。 */
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String rejectComment;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
