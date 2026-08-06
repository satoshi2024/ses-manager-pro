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
import com.ses.entity.SalesOrder;
import com.ses.entity.SalesOrderLine;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.SalesOrderLineMapper;
import com.ses.service.SalesOrderPdfService;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * OpenPDF による注文請書PDF生成（QuotationPdfServiceImpl と同じフォント解決・レイアウト方針）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesOrderPdfServiceImpl implements SalesOrderPdfService {

    private final SystemConfigService systemConfigService;
    private final CustomerMapper customerMapper;
    private final EngineerMapper engineerMapper;
    private final ProjectMapper projectMapper;
    private final SalesOrderLineMapper lineMapper;
    private final com.ses.common.util.PdfFontUtils pdfFontUtils;
    private final org.springframework.beans.factory.ObjectProvider<com.ses.service.DocumentService> documentServiceProvider;
    private final MessageSource messageSource;

    private String msg(String key, Locale locale) {
        return messageSource.getMessage(key, null, key, locale == null ? Locale.JAPANESE : locale);
    }

    @Override
    public byte[] generate(SalesOrder order) {
        return generate(order, Locale.JAPANESE);
    }

    @Override
    public byte[] generate(SalesOrder order, Locale locale) {
        if (order == null) {
            throw BusinessException.of(404, "error.order.notFound");
        }
        Locale targetLocale = locale == null ? Locale.JAPANESE : locale;
        com.ses.entity.Customer customer = order.getCustomerId() != null
                ? customerMapper.selectById(order.getCustomerId()) : null;
        List<SalesOrderLine> lines = lineMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SalesOrderLine>()
                        .eq(SalesOrderLine::getOrderId, order.getId())
                        .orderByAsc(SalesOrderLine::getLineNo));

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

            document.add(new Paragraph(msg("salesOrder.pdf.title", targetLocale), titleFont));
            document.add(new Paragraph(" "));

            Paragraph info = new Paragraph();
            info.setFont(normalFont);
            info.add(msg("salesOrder.pdf.no", targetLocale) + " " + nz(order.getOrderNo()) + "\n");
            if (order.getCustomerPoNo() != null) {
                info.add(msg("salesOrder.pdf.poNo", targetLocale) + " " + order.getCustomerPoNo() + "\n");
            }
            if (order.getOrderDate() != null) {
                info.add(msg("salesOrder.pdf.orderDate", targetLocale) + " " + order.getOrderDate() + "\n");
            }
            if (order.getStartDate() != null || order.getEndDate() != null) {
                info.add(msg("salesOrder.pdf.period", targetLocale) + " " + nz(order.getStartDate())
                        + " 〜 " + nz(order.getEndDate()) + "\n");
            }
            info.add("\n");
            if (customer != null) {
                info.add(nz(customer.getCompanyName()) + msg("quotation.pdf.honorific", targetLocale) + "\n\n");
            }
            document.add(info);

            document.add(buildItemsTable(lines, headerFont, normalFont, boldFont, targetLocale));
            document.add(new Paragraph(" "));

            BigDecimal total = BigDecimal.ZERO;
            for (SalesOrderLine line : lines) {
                total = total.add(line.getAmount() != null ? line.getAmount() : BigDecimal.ZERO);
            }
            Paragraph totalPara = new Paragraph(msg("salesOrder.pdf.total", targetLocale)
                    + formatYen(total), boldFont);
            totalPara.setAlignment(Element.ALIGN_RIGHT);
            document.add(totalPara);
            document.add(new Paragraph(" "));

            if (StringUtils.hasText(order.getPaymentTermsSnapshot())) {
                document.add(new Paragraph(msg("salesOrder.pdf.paymentTerms", targetLocale) + " "
                        + order.getPaymentTermsSnapshot(), normalFont));
            }
            document.add(new Paragraph(msg("salesOrder.pdf.ackNote", targetLocale), normalFont));
            document.add(new Paragraph(" "));

            String companyName = systemConfigService.getString("company.name", "SES Manager Pro");
            document.add(new Paragraph(companyName, normalFont));
            String companyAddress = systemConfigService.getString("company.address", "");
            if (StringUtils.hasText(companyAddress)) {
                document.add(new Paragraph(companyAddress, normalFont));
            }
            String registrationNo = systemConfigService.getString("company.invoice-registration-number", "");
            if (StringUtils.hasText(registrationNo)) {
                document.add(new Paragraph(msg("salesOrder.pdf.registrationNo", targetLocale) + " " + registrationNo, normalFont));
            }

            document.close();
            byte[] pdfBytes = baos.toByteArray();
            registerToLedger(order, pdfBytes);
            return pdfBytes;
        } catch (DocumentException e) {
            log.error("注文請書PDF生成に失敗しました: orderNo={}", order.getOrderNo(), e);
            throw BusinessException.of("error.order.pdfGenerateFailed");
        }
    }

    private void registerToLedger(SalesOrder order, byte[] pdfBytes) {
        com.ses.service.DocumentService docService = documentServiceProvider.getIfAvailable();
        if (docService == null || order == null || pdfBytes == null || pdfBytes.length == 0) {
            return;
        }
        try {
            com.ses.dto.document.DocumentRegisterRequest req = com.ses.dto.document.DocumentRegisterRequest.builder()
                    .documentType("ORDER_ACKNOWLEDGEMENT")
                    .title("注文請書 PDF: " + (order.getOrderNo() != null ? order.getOrderNo() : "ID:" + order.getId()))
                    .documentNo(order.getOrderNo())
                    .counterpartyType("CUSTOMER")
                    .counterpartyId(order.getCustomerId())
                    .transactionDate(order.getOrderDate())
                    .amount(order.getTotalAmountSnapshot())
                    .direction("OUTGOING")
                    .originalName("order_ack_" + (order.getOrderNo() != null ? order.getOrderNo() : order.getId()) + ".pdf")
                    .contentType("application/pdf")
                    .sourceType("GENERATED")
                    .businessKey("ORDER_ACKNOWLEDGEMENT:" + order.getId())
                    .versionDiscriminator("1")
                    .targetType("SALES_ORDER")
                    .targetId(order.getId())
                    .build();
            try (java.io.InputStream is = new java.io.ByteArrayInputStream(pdfBytes)) {
                docService.registerGenerated(req, is);
            }
        } catch (Exception e) {
            log.warn("[帳票連携] 注文請書PDFの文書台帳登録失敗: orderId={} error={}", order.getId(), e.getMessage());
        }
    }

    private PdfPTable buildItemsTable(List<SalesOrderLine> lines, Font headerFont, Font normalFont,
                                      Font boldFont, Locale locale) throws DocumentException {
        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{1, 3, 3, 2, 2});
        addHeaderCell(table, msg("salesOrder.pdf.col.no", locale), headerFont);
        addHeaderCell(table, msg("salesOrder.pdf.col.engineer", locale), headerFont);
        addHeaderCell(table, msg("salesOrder.pdf.col.project", locale), headerFont);
        addHeaderCell(table, msg("salesOrder.pdf.col.unitPrice", locale), headerFont);
        addHeaderCell(table, msg("salesOrder.pdf.col.amount", locale), headerFont);

        for (SalesOrderLine line : lines) {
            table.addCell(new Phrase(String.valueOf(line.getLineNo()), normalFont));
            com.ses.entity.Engineer engineer = line.getEngineerId() == null ? null
                    : engineerMapper.selectById(line.getEngineerId());
            table.addCell(new Phrase(engineer == null ? "-" : engineer.getFullName(), normalFont));
            com.ses.entity.Project project = line.getProjectId() == null ? null
                    : projectMapper.selectById(line.getProjectId());
            table.addCell(new Phrase(project == null ? "-" : project.getProjectName(), normalFont));
            PdfPCell priceCell = new PdfPCell(new Phrase(formatYen(line.getUnitPrice()), normalFont));
            priceCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            table.addCell(priceCell);
            PdfPCell amountCell = new PdfPCell(new Phrase(formatYen(line.getAmount()), boldFont));
            amountCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            table.addCell(amountCell);
        }
        return table;
    }

    private void addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(cell);
    }

    private String formatYen(BigDecimal value) {
        return "¥" + (value == null ? "0" : value.toPlainString());
    }

    private String nz(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
