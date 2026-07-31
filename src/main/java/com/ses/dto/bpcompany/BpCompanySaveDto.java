package com.ses.dto.bpcompany;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * BP会社保存・更新リクエストDTO (Mass Assignment防止)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BpCompanySaveDto {

    @NotBlank(message = "正式名称は必須です")
    private String legalName;

    private String nameKana;

    @NotBlank(message = "区分は必須です")
    private String entityType; // CORPORATE / INDIVIDUAL / FREELANCE / PROVISIONAL

    private String corporateNumber;
    private String invoiceRegistrationNumber;
    private String capitalBand;
    private String employeeBand;
    private String address;
    private String representative;
    private Long primarySalesUserId;
    private String applicabilityNote;
}
