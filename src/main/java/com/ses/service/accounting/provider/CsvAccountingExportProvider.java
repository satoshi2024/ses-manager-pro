package com.ses.service.accounting.provider;

import com.ses.dto.accounting.canonical.*;
import com.ses.entity.IntegrationConnection;
import com.ses.service.accounting.AccountingProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * CSV 出力フォールバック用 AccountingProvider (design §2, platform-invariants §5.1)。
 * freee API が利用できない契約プランや手動インポート運用時に、同一の Canonical DTO から CSV を出力する。
 */
@Slf4j
@Component("csvAccountingExportProvider")
public class CsvAccountingExportProvider implements AccountingProvider {

    @Override
    public String providerName() {
        return "csv";
    }

    @Override
    public CanonicalDealResult upsertSalesInvoice(IntegrationConnection connection, CanonicalSalesInvoice invoice) {
        log.info("Generating CSV row for sales invoice: invoiceNo={}", invoice.getInvoiceNo());
        String csvRow = formatSalesInvoiceCsv(invoice);
        return CanonicalDealResult.builder()
                .success(true)
                .externalId("CSV-INV-" + invoice.getInvoiceId())
                .providerRequestId("CSV-" + System.currentTimeMillis())
                .errorMessageSafe("CSV形式データ生成完了")
                .responseTotal(invoice.getTotal())
                .build();
    }

    @Override
    public CanonicalDealResult cancelSalesInvoice(IntegrationConnection connection, String externalDealId, String reason) {
        log.info("Generating CSV cancellation for deal: externalDealId={}", externalDealId);
        return CanonicalDealResult.builder()
                .success(true)
                .externalId(externalDealId + "-CANCEL")
                .providerRequestId("CSV-" + System.currentTimeMillis())
                .errorMessageSafe("CSV取消伝票生成完了")
                .build();
    }

    @Override
    public CanonicalDealResult upsertPurchaseDeal(IntegrationConnection connection, CanonicalPurchaseDeal purchase) {
        log.info("Generating CSV row for purchase deal: bpPaymentId={}", purchase.getBpPaymentId());
        return CanonicalDealResult.builder()
                .success(true)
                .externalId("CSV-BP-" + purchase.getBpPaymentId())
                .providerRequestId("CSV-" + System.currentTimeMillis())
                .errorMessageSafe("CSV形式仕入データ生成完了")
                .responseTotal(purchase.getAmount())
                .build();
    }

    @Override
    public CanonicalDealResult upsertExpenseDeal(IntegrationConnection connection, CanonicalExpenseDeal expense) {
        log.info("Generating CSV row for expense deal: expenseNo={}", expense.getExpenseNo());
        return CanonicalDealResult.builder()
                .success(true)
                .externalId("CSV-EXP-" + expense.getExpenseRequestId())
                .providerRequestId("CSV-" + System.currentTimeMillis())
                .errorMessageSafe("CSV形式経費データ生成完了")
                .responseTotal(expense.getAmount())
                .build();
    }

    @Override
    public List<CanonicalPaymentSync> fetchPayments(IntegrationConnection connection, LocalDate fromDate, LocalDate toDate) {
        return Collections.emptyList();
    }

    @Override
    public CanonicalPaymentSync fetchDealPayment(IntegrationConnection connection, String externalDealId) {
        return null;
    }

    @Override
    public boolean validateConnection(IntegrationConnection connection) {
        return true;
    }

    /**
     * 売上請求書一覧から freee 取引インポート形式の CSV 文字列を生成する。
     */
    public String exportSalesInvoicesCsv(List<CanonicalSalesInvoice> invoices) {
        StringBuilder sb = new StringBuilder();
        // ヘッダー: 収支区分,管理番号,発生日,期日,取引先名,勘定科目,税区分,金額,備考
        sb.append("収支区分,管理番号,発生日,期日,取引先コード,取引先名,勘定科目,税区分,金額,備考\n");
        if (invoices != null) {
            for (CanonicalSalesInvoice inv : invoices) {
                sb.append(formatSalesInvoiceCsv(inv)).append("\n");
            }
        }
        return sb.toString();
    }

    public byte[] exportSalesInvoicesCsvBytes(List<CanonicalSalesInvoice> invoices) {
        return exportSalesInvoicesCsv(invoices).getBytes(StandardCharsets.UTF_8);
    }

    private String formatSalesInvoiceCsv(CanonicalSalesInvoice inv) {
        return String.join(",",
                "収入",
                escapeCsv(inv.getInvoiceNo()),
                inv.getIssueDate() != null ? inv.getIssueDate().toString() : "",
                inv.getDueDate() != null ? inv.getDueDate().toString() : "",
                escapeCsv(inv.getCustomerCode()),
                escapeCsv(inv.getCustomerName()),
                "売上高",
                "課税売上10%",
                formatAmount(inv.getTotal()),
                escapeCsv(inv.getRemarks() != null ? inv.getRemarks() : "SES請求")
        );
    }

    /**
     * CSV エスケープと数式インジェクション対策 (platform-invariants §5.1)。
     * 先頭が =, +, -, @ の場合はクォートとアポストロフィで防御するが、
     * 正常な負数（例: "-1500"）は数値として扱う。
     */
    public static String escapeCsv(String value) {
        if (value == null) return "";
        String s = value.trim();
        // 正常な負数判定
        if (isNumeric(s)) {
            return s;
        }
        // 数式インジェクション対策
        if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@")) {
            s = "\t" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public static String formatAmount(BigDecimal amount) {
        if (amount == null) return "0";
        return amount.toPlainString();
    }

    private static boolean isNumeric(String str) {
        if (str == null || str.isBlank()) return false;
        try {
            new BigDecimal(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
