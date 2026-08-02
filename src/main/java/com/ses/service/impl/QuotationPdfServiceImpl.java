package com.ses.service.impl;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Quotation;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.QuotationPdfService;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

/**
 * OpenPDF による見積書PDF生成。請求書PDF(InvoicePdfServiceImpl)と同じフォント解決・レイアウト方針。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotationPdfServiceImpl implements QuotationPdfService {

    private final SystemConfigService systemConfigService;
    private final CustomerMapper customerMapper;
    private final EngineerMapper engineerMapper;
    private final com.ses.common.util.PdfFontUtils pdfFontUtils;
    private final org.springframework.beans.factory.ObjectProvider<com.ses.service.DocumentService> documentServiceProvider;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.MessageSource messageSource;

    private String msg(String key, java.util.Locale locale) {
        if (messageSource == null) return key;
        return messageSource.getMessage(key, null, key, locale == null ? java.util.Locale.JAPANESE : locale);
    }

    @Override
    public byte[] generate(Quotation q) {
        return generate(q, java.util.Locale.JAPANESE);
    }

    @Override
    public byte[] generate(Quotation q, java.util.Locale locale) {
        if (q == null) {
            throw BusinessException.of("error.quotation.notFound");
        }
        java.util.Locale targetLocale = locale == null ? java.util.Locale.JAPANESE : locale;
        Customer customer = q.getCustomerId() != null ? customerMapper.selectById(q.getCustomerId()) : null;
        Engineer engineer = q.getEngineerId() != null ? engineerMapper.selectById(q.getEngineerId()) : null;

        BaseFont baseFont = pdfFontUtils.resolveCjkFont();
        Font titleFont = new Font(baseFont, 18, Font.BOLD);
        Font normalFont = new Font(baseFont, 10, Font.NORMAL);
        Font boldFont = new Font(baseFont, 12, Font.BOLD);
        Font headerFont = new Font(baseFont, 10, Font.BOLD, Color.WHITE);

        Document document = new Document(PageSize.A4, 40, 40, 50, 50);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, baos);
            document.open();

            document.add(new Paragraph(msg("quotation.pdf.title", targetLocale), titleFont));
            document.add(new Paragraph(" "));

            Paragraph info = new Paragraph();
            info.setFont(normalFont);
            info.add(msg("quotation.pdf.no", targetLocale) + nz(q.getQuotationNo()) + "\n");
            info.add(msg("quotation.pdf.issueDate", targetLocale) + (q.getCreatedAt() != null ? q.getCreatedAt().toLocalDate().toString() : "-") + "\n");
            if (q.getValidUntil() != null) {
                info.add(msg("quotation.pdf.validUntil", targetLocale) + q.getValidUntil() + "\n");
            }
            info.add("\n");
            if (customer != null) {
                info.add(nz(customer.getCompanyName()) + msg("quotation.pdf.honorific", targetLocale) + "\n\n");
            }
            document.add(info);

            document.add(buildItemsTable(q, engineer, headerFont, normalFont, boldFont, targetLocale));
            document.add(new Paragraph(" "));

            if (StringUtils.hasText(q.getRemarks())) {
                document.add(new Paragraph(msg("quotation.pdf.remarks", targetLocale) + q.getRemarks(), normalFont));
            }
            document.add(new Paragraph(" "));
            document.add(new Paragraph(msg("quotation.pdf.taxNote", targetLocale), normalFont));

            document.add(new Paragraph(" "));
            String companyName = systemConfigService.getString("company.name", "SES Manager Pro");
            document.add(new Paragraph(companyName, normalFont));
            String companyAddress = systemConfigService.getString("company.address", "");
            if (StringUtils.hasText(companyAddress)) {
                document.add(new Paragraph(companyAddress, normalFont));
            }
            // 適格請求書発行事業者 登録番号(未設定なら省略)
            String registrationNo = systemConfigService.getString("company.invoice-registration-number", "");
            if (StringUtils.hasText(registrationNo)) {
                document.add(new Paragraph(msg("quotation.pdf.registrationNo", targetLocale) + registrationNo, normalFont));
            }

            document.close();
            byte[] pdfBytes = baos.toByteArray();
            registerQuotationToLedger(q, pdfBytes);
            return pdfBytes;
        } catch (DocumentException e) {
            log.error("見積書PDF生成に失敗しました: quotationNo={}", q.getQuotationNo(), e);
            throw BusinessException.of("error.quotation.pdfGenerateFailed");
        }
    }

    private void registerQuotationToLedger(Quotation q, byte[] pdfBytes) {
        com.ses.service.DocumentService docService = documentServiceProvider.getIfAvailable();
        if (docService == null || q == null || pdfBytes == null || pdfBytes.length == 0) {
            return;
        }
        try {
            com.ses.dto.document.DocumentRegisterRequest req = com.ses.dto.document.DocumentRegisterRequest.builder()
                    .documentType("QUOTATION")
                    .title("見積書 PDF: " + (q.getQuotationNo() != null ? q.getQuotationNo() : "ID:" + q.getId()))
                    .documentNo(q.getQuotationNo())
                    .counterpartyType("CUSTOMER")
                    .counterpartyId(q.getCustomerId())
                    .transactionDate(q.getValidUntil() != null ? q.getValidUntil() : (q.getCreatedAt() != null ? q.getCreatedAt().toLocalDate() : null))
                    .amount(q.getUnitPrice())
                    .direction("OUTGOING")
                    .originalName("quotation_" + (q.getQuotationNo() != null ? q.getQuotationNo() : q.getId()) + ".pdf")
                    .contentType("application/pdf")
                    .sourceType("GENERATED")
                    .businessKey("QUOTATION:" + q.getId())
                    .versionDiscriminator(q.getId() != null ? "quo-" + q.getId() : "v1")
                    .targetType("QUOTATION")
                    .targetId(q.getId())
                    .build();

            try (java.io.InputStream is = new java.io.ByteArrayInputStream(pdfBytes)) {
                docService.registerGenerated(req, is);
            }
        } catch (Exception e) {
            log.warn("[帳票連携] 見積書PDFの法定文書台帳登録失敗: quotationId={} error={}", q.getId(), e.getMessage());
        }
    }

    private PdfPTable buildItemsTable(Quotation q, Engineer engineer, Font headerFont, Font normalFont, Font boldFont, java.util.Locale locale)
            throws DocumentException {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{2, 3, 2, 2});

        addHeaderCell(table, msg("quotation.pdf.col.engineer", locale), headerFont);
        addHeaderCell(table, msg("quotation.pdf.col.title", locale), headerFont);
        addHeaderCell(table, msg("quotation.pdf.col.unitPrice", locale), headerFont);
        addHeaderCell(table, msg("quotation.pdf.col.settlementRange", locale), headerFont);

        table.addCell(new Phrase(initialOf(engineer), normalFont));
        table.addCell(new Phrase(nz(q.getTitle()), normalFont));
        PdfPCell priceCell = new PdfPCell(new Phrase(formatYen(q.getUnitPrice()), boldFont));
        priceCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(priceCell);
        table.addCell(new Phrase(settlementRange(q), normalFont));
        return table;
    }

    private void addHeaderCell(PdfPTable table, String text, Font headerFont) {
        PdfPCell cell = new PdfPCell(new Phrase(text, headerFont));
        cell.setBackgroundColor(new Color(52, 58, 64));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(6);
        table.addCell(cell);
    }

    /** 客先提出物のため要員名はイニシャル表記にする。 */
    private String initialOf(Engineer engineer) {
        if (engineer == null || !StringUtils.hasText(engineer.getInitialName())) {
            return "-";
        }
        String init = engineer.getInitialName().trim();
        return init.isEmpty() ? "-" : init;
    }

    private String settlementRange(Quotation q) {
        if (q.getSettlementHoursMin() == null && q.getSettlementHoursMax() == null) {
            return "-";
        }
        String min = q.getSettlementHoursMin() != null ? q.getSettlementHoursMin().toPlainString() : "";
        String max = q.getSettlementHoursMax() != null ? q.getSettlementHoursMax().toPlainString() : "";
        return min + "〜" + max;
    }

    private String formatYen(BigDecimal amount) {
        return amount == null ? "0" : String.format("%,d", amount.longValue());
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }


}
