package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 銀行入金明細（freee連携・入金消込）。
 * status は 未消込→消込済 の一方向遷移のみ。候補提示/保留は未消込内の分類であり永続化しない。
 */
@Data
@TableName("t_bank_deposit")
public class BankDeposit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String freeeDepositId;
    private LocalDate depositDate;
    private BigDecimal amount;
    private String payerName;
    private String status;
    private Long matchedInvoiceId;
    private Long matchedPaymentId;
    private String remarks;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
