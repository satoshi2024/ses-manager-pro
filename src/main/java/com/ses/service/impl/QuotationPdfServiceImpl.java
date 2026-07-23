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
import com.ses.config.PdfProperties;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Quotation;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.QuotationMapper;
import com.ses.service.QuotationPdfService;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenPDF による見積書PDF生成。請求書PDF(InvoicePdfServiceImpl)と同じフォント解決・レイアウト方針。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotationPdfServiceImpl implements QuotationPdfService {

    private final PdfProperties pdfProperties;
    private final SystemConfigService systemConfigService;
    private final QuotationMapper quotationMapper;
    private final CustomerMapper customerMapper;
    private final EngineerMapper engineerMapper;

    @Override
    public byte[] generate(Quotation q) {
        if (q == null) {
            throw BusinessException.of("error.quotation.notFound");
        }
        Customer customer = q.getCustomerId() != null ? customerMapper.selectById(q.getCustomerId()) : null;
        Engineer engineer = q.getEngineerId() != null ? engineerMapper.selectById(q.getEngineerId()) : null;

        BaseFont baseFont = resolveCjkFont();
        Font titleFont = new Font(baseFont, 18, Font.BOLD);
        Font normalFont = new Font(baseFont, 10, Font.NORMAL);
        Font boldFont = new Font(baseFont, 12, Font.BOLD);
        Font headerFont = new Font(baseFont, 10, Font.BOLD, Color.WHITE);

        Document document = new Document(PageSize.A4, 40, 40, 50, 50);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, baos);
            document.open();

            document.add(new Paragraph("見積書", titleFont));
            document.add(new Paragraph(" "));

            Paragraph info = new Paragraph();
            info.setFont(normalFont);
            info.add("見積番号: " + nz(q.getQuotationNo()) + "\n");
            info.add("発行日: " + (q.getCreatedAt() != null ? q.getCreatedAt().toLocalDate().toString() : "-") + "\n");
            if (q.getValidUntil() != null) {
                info.add("有効期限: " + q.getValidUntil() + "\n");
            }
            info.add("\n");
            if (customer != null) {
                info.add(nz(customer.getCompanyName()) + " 御中\n\n");
            }
            document.add(info);

            document.add(buildItemsTable(q, engineer, headerFont, normalFont, boldFont));
            document.add(new Paragraph(" "));

            if (StringUtils.hasText(q.getRemarks())) {
                document.add(new Paragraph("備考: " + q.getRemarks(), normalFont));
            }
            document.add(new Paragraph(" "));
            document.add(new Paragraph("本見積は税抜表記です。消費税は請求時の税率を適用します。", normalFont));

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
                document.add(new Paragraph("登録番号: " + registrationNo, normalFont));
            }

            document.close();
            return baos.toByteArray();
        } catch (DocumentException e) {
            log.error("見積書PDF生成に失敗しました: quotationNo={}", q.getQuotationNo(), e);
            throw BusinessException.of("error.quotation.pdfGenerateFailed");
        }
    }

    private PdfPTable buildItemsTable(Quotation q, Engineer engineer, Font headerFont, Font normalFont, Font boldFont)
            throws DocumentException {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{2, 3, 2, 2});

        addHeaderCell(table, "要員", headerFont);
        addHeaderCell(table, "件名", headerFont);
        addHeaderCell(table, "単価(円/月)", headerFont);
        addHeaderCell(table, "精算幅(h)", headerFont);

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

    private BaseFont resolveCjkFont() {
        try {
            // First try the bundled font
            byte[] fontBytes = org.springframework.util.StreamUtils.copyToByteArray(
                getClass().getClassLoader().getResourceAsStream("fonts/ipaexg.ttf")
            );
            return BaseFont.createFont("ipaexg.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, fontBytes, null);
        } catch (Exception ex) {
            log.warn("Bundled font load failed, falling back to system fonts", ex);
        }

        List<String> candidates = new ArrayList<>();
        if (StringUtils.hasText(pdfProperties.getFontPath())) {
            candidates.add(pdfProperties.getFontPath());
        }
        candidates.addAll(pdfProperties.getDefaultFontCandidates());

        for (String candidate : candidates) {
            String filePath = candidate.contains(",") ? candidate.substring(0, candidate.indexOf(',')) : candidate;
            if (!Files.exists(Paths.get(filePath))) {
                continue;
            }
            try {
                return BaseFont.createFont(candidate, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            } catch (Exception e) {
                log.warn("CJKフォントの読み込みに失敗しました: {}", candidate, e);
            }
        }
        try {
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            log.warn("フォールバックフォントの作成に失敗しました", e);
        }
        throw new BusinessException(
                "PDF生成用の日本語フォントが見つかりません。app.pdf.font-path でフォントファイルのパスを指定してください。");
    }
}
