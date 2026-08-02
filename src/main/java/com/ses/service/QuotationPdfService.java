package com.ses.service;

import java.util.Locale;

/**
 * 見積書PDF生成サービス。
 */
public interface QuotationPdfService {
    /** 見積IDから客先提出用の見積書PDFを生成する。 */
    default byte[] generate(com.ses.entity.Quotation quotation) {
        return generate(quotation, Locale.JAPANESE);
    }

    /** 見積IDと指定Localeから客先提出用の見積書PDFを生成する。 */
    byte[] generate(com.ses.entity.Quotation quotation, Locale locale);
}
