package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_bp_payment")
public class BpPayment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workRecordId;
    private Integer layerOrder;
    private String payeeCompanyName;
    private Long parentPaymentId;
    private BigDecimal amount;
    private String status;
    private LocalDate paidDate;
    private String remarks;
    @TableLogic
    private Integer deletedFlag;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
