package com.ses.dto.export;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 契約一覧Excel出力用DTO(要員名・案件名・顧客名を解決済み)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractExportDto {

    /** 契約番号 */
    private String contractNo;

    /** 要員名 */
    private String engineerName;

    /** 案件名 */
    private String projectName;

    /** 顧客名 */
    private String customerName;

    /** 契約形態 */
    private String contractType;

    /** 契約開始日 */
    private LocalDate startDate;

    /** 契約終了日 */
    private LocalDate endDate;

    /** 売上単価 */
    private BigDecimal sellingPrice;

    /** 原価 */
    private BigDecimal costPrice;

    /** ステータス */
    private String status;
}
