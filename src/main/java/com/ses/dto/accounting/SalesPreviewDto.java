package com.ses.dto.accounting;

import com.ses.dto.accounting.canonical.CanonicalSalesInvoice;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 送信前プレビュー結果DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesPreviewDto {
    private boolean readyToSend;
    private List<String> validationErrors;
    private CanonicalSalesInvoice canonicalInvoice;
    private String providerName;
    private String externalCompanyName;
}
