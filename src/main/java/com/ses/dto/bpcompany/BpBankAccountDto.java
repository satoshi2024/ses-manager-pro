package com.ses.dto.bpcompany;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
    private String maskedLabel; // 末尾のみ表示 (例: ****1234)
    private LocalDate validFrom;
    private LocalDate validTo;
    private String approvalStatus;
    private Long approvedBy;
    private LocalDateTime approvedAt;
}
