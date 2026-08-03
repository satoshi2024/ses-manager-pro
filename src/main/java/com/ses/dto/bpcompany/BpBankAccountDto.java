package com.ses.dto.bpcompany;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * BP銀行口座情報DTO (暗号化口座番号を出力せずマスク表記のみ返却)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BpBankAccountDto {

    private Long id;
    private Long bpCompanyId;
    private String bankName;
    private String branchName;
    private String accountType;
    private String accountHolder;
    private String maskedLabel;
    private LocalDate validFrom;
    private LocalDate validTo;
    private String approvalStatus;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private LocalDateTime createdAt;
}
