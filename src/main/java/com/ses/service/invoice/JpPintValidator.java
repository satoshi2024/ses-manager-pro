package com.ses.service.invoice;

import com.ses.common.exception.BusinessException;
import com.ses.dto.invoice.CanonicalInvoice;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class JpPintValidator {

    /**
     * 金額が既存invoiceと1円も食い違わず、合計が合わないXMLは送信前に拒否される。
     * 検算 line合計 + 税 + rounding = total
     */
    public void validateAmount(CanonicalInvoice canonicalInvoice) {
        if (canonicalInvoice == null) {
            throw new BusinessException("CanonicalInvoiceがnullです。");
        }

        BigDecimal lineTotal = BigDecimal.ZERO;
        if (canonicalInvoice.getItems() != null) {
            for (CanonicalInvoice.CanonicalInvoiceItem item : canonicalInvoice.getItems()) {
                if (item.getLineAmount() != null) {
                    lineTotal = lineTotal.add(item.getLineAmount());
                }
            }
        }

        // line合計は taxExclusiveAmount (小計) と一致すべき
        if (canonicalInvoice.getTaxExclusiveAmount() != null && lineTotal.compareTo(canonicalInvoice.getTaxExclusiveAmount()) != 0) {
            throw new BusinessException("インボイスの小計(taxExclusiveAmount)が明細の合算(lineTotal)と一致しません。");
        }

        BigDecimal tax = canonicalInvoice.getTaxAmount() != null ? canonicalInvoice.getTaxAmount() : BigDecimal.ZERO;
        BigDecimal rounding = canonicalInvoice.getRoundingAmount() != null ? canonicalInvoice.getRoundingAmount() : BigDecimal.ZERO;
        BigDecimal calculatedTotal = lineTotal.add(tax).add(rounding);

        BigDecimal actualTotal = canonicalInvoice.getTaxInclusiveAmount() != null ? canonicalInvoice.getTaxInclusiveAmount() : BigDecimal.ZERO;

        if (calculatedTotal.compareTo(actualTotal) != 0) {
            throw new BusinessException(String.format("インボイスの合計金額が明細の合算・税額と一致しません。(計算値:%s, 実際値:%s) 送信を拒否しました。", calculatedTotal, actualTotal));
        }
    }
}
