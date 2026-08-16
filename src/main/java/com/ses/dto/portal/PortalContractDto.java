package com.ses.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 顧客portal向け契約DTO（field-inventory §3.1。売上/原価・営業情報・内部IDを含まない）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalContractDto {
    private Long id;
    private String contractNo;
    private String contractType;
    private String status;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate contractDate;
    private String jobDescription;
    private String workLocation;
    private LocalDate inspectionDueDate;
    private LocalDate paymentDueDate;
    private String paymentMethod;
    private BigDecimal settlementHoursMin;
    private BigDecimal settlementHoursMax;
    private boolean acceptanceRequired;
    private String engineerName;
    private String projectName;
    /** 電子署名の業務状態（下書き/先方確認中/締結済/取消・却下/要確認/未実施）。署名はCloudSign側のメールリンクで実施（R2.4） */
    private String esignStatus;
    /** 締結済み契約書が文書台帳に存在するか（存在時のみdownload可） */
    private boolean contractDocumentAvailable;
}
