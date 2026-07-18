package com.ses.service;

/**
 * 見積書PDF生成サービス。
 */
public interface QuotationPdfService {
    /** 見積IDから客先提出用の見積書PDFを生成する。 */
    byte[] generate(Long quotationId);
}
