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
import com.ses.dto.InvoiceDetailDto;
import com.ses.entity.InvoiceItem;
import com.ses.service.InvoicePdfService;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenPDF(独自API、HTML変換ではなくPdfPTable等で直接描画)による請求書PDF生成。
 * 日本語描画にはCJK対応のTrueTypeフォントの埋め込みが必須。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoicePdfServiceImpl implements InvoicePdfService {

    /**
     * 主要Linuxディストリビューションで日本語PDF生成によく使われるフォントパッケージの
     * 標準インストールパス（fonts-japanese-gothic / fonts-ipafont 等）。
     * 実行環境にこれらが無い場合は app.pdf.font-path で明示的に指定する必要がある。
     */
    private static final List<String> DEFAULT_FONT_CANDIDATES = List.of(
            "/usr/share/fonts/opentype/ipafont-gothic/ipag.ttf",
            "/usr/share/fonts/truetype/fonts-japanese-gothic.ttf",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc,0",
            "/usr/share/fonts/truetype/noto/NotoSansJP-Regular.ttf"
    );

    private final PdfProperties pdfProperties;
    private final SystemConfigService systemConfigService;

    @Override
    public byte[] generate(InvoiceDetailDto detail) {
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

            document.add(new Paragraph("請求書", titleFont));
            document.add(new Paragraph(" "));

            Paragraph info = new Paragraph();
            info.setFont(normalFont);
            info.add("請求書番号: " + nz(detail.getInvoiceNo()) + "\n");
            info.add("発行日: " + (detail.getIssuedDate() != null ? detail.getIssuedDate().toString() : "-") + "\n");
            info.add("対象月: " + nz(detail.getBillingMonth()) + "\n\n");
            if (detail.getCustomer() != null) {
                info.add(nz(detail.getCustomer().getCompanyName()) + " 御中\n\n");
            }
            document.add(info);

            document.add(buildItemsTable(detail.getItems(), headerFont, normalFont));
            document.add(new Paragraph(" "));
            document.add(buildTotalsParagraph(detail, normalFont, boldFont));

            String bankInfo = systemConfigService.getString("company.bank-info", "");
            if (StringUtils.hasText(bankInfo)) {
                document.add(new Paragraph(" "));
                document.add(new Paragraph("お振込先: " + bankInfo, normalFont));
            }

            document.add(new Paragraph(" "));
            String companyName = systemConfigService.getString("company.name", "SES Manager Pro");
            document.add(new Paragraph(companyName, normalFont));

            document.close();
            return baos.toByteArray();
        } catch (DocumentException e) {
            log.error("請求書PDF生成に失敗しました: invoiceNo={}", detail.getInvoiceNo(), e);
            throw new BusinessException("PDF生成に失敗しました");
        }
    }

    private PdfPTable buildItemsTable(List<InvoiceItem> items, Font headerFont, Font normalFont) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{3, 1});

        addHeaderCell(table, "摘要", headerFont);
        addHeaderCell(table, "金額(円)", headerFont);

        if (items != null) {
            for (InvoiceItem item : items) {
                table.addCell(new Phrase(nz(item.getDescription()), normalFont));
                PdfPCell amountCell = new PdfPCell(new Phrase(formatYen(item.getAmount()), normalFont));
                amountCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                table.addCell(amountCell);
            }
        }
        return table;
    }

    private void addHeaderCell(PdfPTable table, String text, Font headerFont) {
        PdfPCell cell = new PdfPCell(new Phrase(text, headerFont));
        cell.setBackgroundColor(new Color(52, 58, 64));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(6);
        table.addCell(cell);
    }

    private Paragraph buildTotalsParagraph(InvoiceDetailDto detail, Font normalFont, Font boldFont) {
        Paragraph totals = new Paragraph();
        totals.setAlignment(Element.ALIGN_RIGHT);
        totals.add(new Phrase("小計: " + formatYen(detail.getSubtotal()) + " 円\n", normalFont));
        totals.add(new Phrase("消費税: " + formatYen(detail.getTax()) + " 円\n", normalFont));
        totals.add(new Phrase("合計: " + formatYen(detail.getTotal()) + " 円", boldFont));
        return totals;
    }

    private String formatYen(BigDecimal amount) {
        return amount == null ? "0" : String.format("%,d", amount.longValue());
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    private BaseFont resolveCjkFont() {
        List<String> candidates = new ArrayList<>();
        if (StringUtils.hasText(pdfProperties.getFontPath())) {
            candidates.add(pdfProperties.getFontPath());
        }
        candidates.addAll(DEFAULT_FONT_CANDIDATES);

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
        throw new BusinessException(
                "PDF生成用の日本語フォントが見つかりません。app.pdf.font-path でフォントファイルのパスを指定してください。");
    }
}
