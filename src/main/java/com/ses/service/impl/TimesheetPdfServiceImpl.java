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
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.WorkRecord;
import com.ses.entity.WorkRecordDaily;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.WorkRecordDailyMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.TimesheetPdfService;
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
 * OpenPDF による作業報告書（月報）PDF生成。請求書PDFと同じフォント解決方針。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimesheetPdfServiceImpl implements TimesheetPdfService {

    private static final List<String> DEFAULT_FONT_CANDIDATES = List.of(
            "C:/Windows/Fonts/msgothic.ttc,0",
            "C:/Windows/Fonts/meiryo.ttc,0",
            "/usr/share/fonts/opentype/ipafont-gothic/ipag.ttf",
            "/usr/share/fonts/truetype/fonts-japanese-gothic.ttf",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc,0",
            "/usr/share/fonts/truetype/noto/NotoSansJP-Regular.ttf"
    );

    private final PdfProperties pdfProperties;
    private final WorkRecordMapper workRecordMapper;
    private final WorkRecordDailyMapper workRecordDailyMapper;
    private final ContractMapper contractMapper;
    private final EngineerMapper engineerMapper;
    private final ProjectMapper projectMapper;
    private final CustomerMapper customerMapper;

    @Override
    public byte[] generate(Long workRecordId) {
        WorkRecord record = workRecordMapper.selectById(workRecordId);
        if (record == null) {
            throw BusinessException.of("error.workRecord.notFound2");
        }
        Contract contract = contractMapper.selectById(record.getContractId());
        Engineer engineer = contract != null && contract.getEngineerId() != null
                ? engineerMapper.selectById(contract.getEngineerId()) : null;
        Project project = contract != null && contract.getProjectId() != null
                ? projectMapper.selectById(contract.getProjectId()) : null;
        Customer customer = contract != null && contract.getCustomerId() != null
                ? customerMapper.selectById(contract.getCustomerId()) : null;
        List<WorkRecordDaily> dailies = workRecordDailyMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WorkRecordDaily>()
                        .eq("work_record_id", workRecordId).orderByAsc("work_date"));

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

            document.add(new Paragraph("作業報告書", titleFont));
            document.add(new Paragraph(" "));

            Paragraph info = new Paragraph();
            info.setFont(normalFont);
            info.add("対象月: " + nz(record.getWorkMonth()) + "\n");
            info.add("要員名: " + (engineer != null ? nz(engineer.getFullName()) : "-") + "\n");
            info.add("案件名: " + (project != null ? nz(project.getProjectName()) : "-") + "\n");
            info.add("客先名: " + (customer != null ? nz(customer.getCompanyName()) : "-") + "\n");
            document.add(info);
            document.add(new Paragraph(" "));

            if (!dailies.isEmpty()) {
                document.add(buildDailyTable(dailies, headerFont, normalFont));
            }
            document.add(new Paragraph(" "));
            document.add(new Paragraph("合計稼働時間: "
                    + (record.getActualHours() != null ? record.getActualHours().toPlainString() : "0") + " h", boldFont));

            document.add(new Paragraph(" "));
            document.add(buildApprovalTable(headerFont, normalFont));

            document.close();
            return baos.toByteArray();
        } catch (DocumentException e) {
            log.error("作業報告書PDF生成に失敗しました: workRecordId={}", workRecordId, e);
            throw BusinessException.of("error.workRecord.pdfGenerateFailed");
        }
    }

    private PdfPTable buildDailyTable(List<WorkRecordDaily> dailies, Font headerFont, Font normalFont)
            throws DocumentException {
        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{2, 2, 2, 1.5f, 3});
        addHeaderCell(table, "日付", headerFont);
        addHeaderCell(table, "開始", headerFont);
        addHeaderCell(table, "終了", headerFont);
        addHeaderCell(table, "稼働(h)", headerFont);
        addHeaderCell(table, "備考", headerFont);
        for (WorkRecordDaily d : dailies) {
            table.addCell(new Phrase(d.getWorkDate() != null ? d.getWorkDate().toString() : "", normalFont));
            table.addCell(new Phrase(d.getStartTime() != null ? d.getStartTime().toString() : "", normalFont));
            table.addCell(new Phrase(d.getEndTime() != null ? d.getEndTime().toString() : "", normalFont));
            PdfPCell h = new PdfPCell(new Phrase(d.getWorkedHours() != null ? d.getWorkedHours().toPlainString() : "", normalFont));
            h.setHorizontalAlignment(Element.ALIGN_RIGHT);
            table.addCell(h);
            table.addCell(new Phrase(nz(d.getRemarks()), normalFont));
        }
        return table;
    }

    private PdfPTable buildApprovalTable(Font headerFont, Font normalFont) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(60);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        addHeaderCell(table, "客先確認印", headerFont);
        addHeaderCell(table, "自社確認印", headerFont);
        PdfPCell c1 = new PdfPCell(new Phrase(" ", normalFont));
        c1.setFixedHeight(50);
        PdfPCell c2 = new PdfPCell(new Phrase(" ", normalFont));
        c2.setFixedHeight(50);
        table.addCell(c1);
        table.addCell(c2);
        return table;
    }

    private void addHeaderCell(PdfPTable table, String text, Font headerFont) {
        PdfPCell cell = new PdfPCell(new Phrase(text, headerFont));
        cell.setBackgroundColor(new Color(52, 58, 64));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(6);
        table.addCell(cell);
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
