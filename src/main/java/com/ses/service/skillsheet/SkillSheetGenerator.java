package com.ses.service.skillsheet;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.dto.skillsheet.SkillSheetCareerDto;
import com.ses.dto.skillsheet.SkillSheetDto;
import com.ses.dto.skillsheet.SkillSheetDtoMapper;
import com.ses.dto.skillsheet.SkillSheetSkillDto;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerCareer;
import com.ses.service.EngineerCareerService;
import com.ses.service.EngineerService;
import com.ses.service.EngineerSkillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillSheetGenerator {

    private final EngineerService engineerService;
    private final EngineerSkillService engineerSkillService;
    private final EngineerCareerService engineerCareerService;
    private final com.ses.common.util.PdfFontUtils pdfFontUtils;

    public byte[] generatePdf(Long engineerId) {
        SkillSheetDto dto = fetchSkillSheetDto(engineerId);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             Document document = new Document(PageSize.A4, 36, 36, 36, 36)) {
            
            PdfWriter.getInstance(document, baos);
            document.open();

            // 日本語フォントの設定
            BaseFont baseFont = pdfFontUtils.resolveCjkFont();
            
            Font titleFont = new Font(baseFont, 16, Font.BOLD);
            Font headerFont = new Font(baseFont, 12, Font.BOLD);
            Font normalFont = new Font(baseFont, 10, Font.NORMAL);

            // タイトル
            Paragraph title = new Paragraph("スキルシート", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(20);
            document.add(title);

            // 基本情報
            PdfPTable basicTable = new PdfPTable(2);
            basicTable.setWidthPercentage(100);
            basicTable.setSpacingAfter(20);
            basicTable.setWidths(new float[]{3f, 7f});

            addCell(basicTable, "氏名", headerFont, true);
            addCell(basicTable, dto.getFullName() != null ? dto.getFullName() : "", normalFont, false);
            addCell(basicTable, "最寄駅", headerFont, true);
            addCell(basicTable, dto.getNearestStation() != null ? dto.getNearestStation() : "", normalFont, false);
            addCell(basicTable, "稼働可能日", headerFont, true);
            addCell(basicTable, dto.getAvailableDate() != null ? dto.getAvailableDate().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) : "", normalFont, false);
            document.add(basicTable);

            // スキル一覧
            Paragraph skillTitle = new Paragraph("■ 保有スキル", headerFont);
            skillTitle.setSpacingAfter(10);
            document.add(skillTitle);

            if (dto.getSkills() != null && !dto.getSkills().isEmpty()) {
                PdfPTable skillTable = new PdfPTable(3);
                skillTable.setWidthPercentage(100);
                skillTable.setSpacingAfter(20);
                skillTable.setWidths(new float[]{5f, 2.5f, 2.5f});
                
                addCell(skillTable, "スキル名", headerFont, true);
                addCell(skillTable, "習熟度", headerFont, true);
                addCell(skillTable, "経験年数", headerFont, true);

                for (SkillSheetSkillDto skill : dto.getSkills()) {
                    addCell(skillTable, skill.getSkillName(), normalFont, false);
                    addCell(skillTable, skill.getProficiency(), normalFont, false);
                    addCell(skillTable, skill.getExperienceYears() != null ? skill.getExperienceYears() + "年" : "", normalFont, false);
                }
                document.add(skillTable);
            } else {
                Paragraph noSkill = new Paragraph("登録されているスキルはありません。", normalFont);
                noSkill.setSpacingAfter(20);
                document.add(noSkill);
            }

            // 職務経歴一覧
            Paragraph careerTitle = new Paragraph("■ 職務経歴", headerFont);
            careerTitle.setSpacingAfter(10);
            document.add(careerTitle);

            if (dto.getCareers() != null && !dto.getCareers().isEmpty()) {
                for (SkillSheetCareerDto career : dto.getCareers()) {
                    PdfPTable careerTable = new PdfPTable(2);
                    careerTable.setWidthPercentage(100);
                    careerTable.setSpacingAfter(10);
                    careerTable.setWidths(new float[]{2f, 8f});

                    String periodFrom = career.getPeriodFrom() != null ? career.getPeriodFrom().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) : "";
                    String periodTo = career.getPeriodTo() != null ? career.getPeriodTo().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) : "現在";
                    
                    addCell(careerTable, "期間", headerFont, true);
                    addCell(careerTable, periodFrom + " ～ " + periodTo, normalFont, false);
                    
                    addCell(careerTable, "業界", headerFont, true);
                    addCell(careerTable, career.getClientIndustry() != null ? career.getClientIndustry() : "", normalFont, false);
                    
                    addCell(careerTable, "役割", headerFont, true);
                    addCell(careerTable, career.getRole() != null ? career.getRole() : "", normalFont, false);
                    
                    addCell(careerTable, "概要", headerFont, true);
                    addCell(careerTable, career.getDescription() != null ? career.getDescription() : "", normalFont, false);
                    
                    addCell(careerTable, "技術スタック", headerFont, true);
                    addCell(careerTable, career.getTechStack() != null ? career.getTechStack() : "", normalFont, false);
                    
                    addCell(careerTable, "チーム規模", headerFont, true);
                    addCell(careerTable, career.getTeamSize() != null ? career.getTeamSize() + "名" : "", normalFont, false);

                    document.add(careerTable);
                }
            } else {
                Paragraph noCareer = new Paragraph("登録されている職務経歴はありません。", normalFont);
                noCareer.setSpacingAfter(20);
                document.add(noCareer);
            }

            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Failed to generate PDF for engineerId: " + engineerId, e);
            throw new RuntimeException("PDFの生成に失敗しました", e);
        }
    }

    public byte[] generateExcel(Long engineerId) {
        SkillSheetDto dto = fetchSkillSheetDto(engineerId);

        try (org.apache.poi.xssf.streaming.SXSSFWorkbook workbook = new org.apache.poi.xssf.streaming.SXSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("スキルシート");
            
            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            org.apache.poi.ss.usermodel.CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            
            // 基本情報
            org.apache.poi.ss.usermodel.Row titleRow = sheet.createRow(0);
            titleRow.createCell(0).setCellValue("スキルシート");
            titleRow.getCell(0).setCellStyle(headerStyle);
            
            org.apache.poi.ss.usermodel.Row basicHeaderRow = sheet.createRow(2);
            basicHeaderRow.createCell(0).setCellValue("氏名");
            basicHeaderRow.createCell(1).setCellValue("最寄駅");
            basicHeaderRow.createCell(2).setCellValue("稼働可能日");
            for (int i = 0; i < 3; i++) basicHeaderRow.getCell(i).setCellStyle(headerStyle);
            
            org.apache.poi.ss.usermodel.Row basicDataRow = sheet.createRow(3);
            basicDataRow.createCell(0).setCellValue(sanitize(dto.getFullName()));
            basicDataRow.createCell(1).setCellValue(sanitize(dto.getNearestStation()));
            basicDataRow.createCell(2).setCellValue(dto.getAvailableDate() != null ? dto.getAvailableDate().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) : "");
            
            // スキル一覧
            org.apache.poi.ss.usermodel.Row skillTitleRow = sheet.createRow(5);
            skillTitleRow.createCell(0).setCellValue("■ 保有スキル");
            skillTitleRow.getCell(0).setCellStyle(headerStyle);
            
            org.apache.poi.ss.usermodel.Row skillHeaderRow = sheet.createRow(6);
            skillHeaderRow.createCell(0).setCellValue("スキル名");
            skillHeaderRow.createCell(1).setCellValue("習熟度");
            skillHeaderRow.createCell(2).setCellValue("経験年数");
            for (int i = 0; i < 3; i++) skillHeaderRow.getCell(i).setCellStyle(headerStyle);
            
            int rowIndex = 7;
            if (dto.getSkills() != null && !dto.getSkills().isEmpty()) {
                for (SkillSheetSkillDto skill : dto.getSkills()) {
                    org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIndex++);
                    row.createCell(0).setCellValue(sanitize(skill.getSkillName()));
                    row.createCell(1).setCellValue(sanitize(skill.getProficiency()));
                    row.createCell(2).setCellValue(skill.getExperienceYears() != null ? skill.getExperienceYears() + "年" : "");
                }
            } else {
                sheet.createRow(rowIndex++).createCell(0).setCellValue("登録されているスキルはありません。");
            }
            
            // 職務経歴一覧
            rowIndex++;
            org.apache.poi.ss.usermodel.Row careerTitleRow = sheet.createRow(rowIndex++);
            careerTitleRow.createCell(0).setCellValue("■ 職務経歴");
            careerTitleRow.getCell(0).setCellStyle(headerStyle);
            
            org.apache.poi.ss.usermodel.Row careerHeaderRow = sheet.createRow(rowIndex++);
            careerHeaderRow.createCell(0).setCellValue("期間");
            careerHeaderRow.createCell(1).setCellValue("業界");
            careerHeaderRow.createCell(2).setCellValue("役割");
            careerHeaderRow.createCell(3).setCellValue("概要");
            careerHeaderRow.createCell(4).setCellValue("技術スタック");
            careerHeaderRow.createCell(5).setCellValue("チーム規模");
            for (int i = 0; i < 6; i++) careerHeaderRow.getCell(i).setCellStyle(headerStyle);
            
            if (dto.getCareers() != null && !dto.getCareers().isEmpty()) {
                for (SkillSheetCareerDto career : dto.getCareers()) {
                    org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIndex++);
                    String periodFrom = career.getPeriodFrom() != null ? career.getPeriodFrom().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) : "";
                    String periodTo = career.getPeriodTo() != null ? career.getPeriodTo().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) : "現在";
                    
                    row.createCell(0).setCellValue(periodFrom + " ～ " + periodTo);
                    row.createCell(1).setCellValue(sanitize(career.getClientIndustry()));
                    row.createCell(2).setCellValue(sanitize(career.getRole()));
                    row.createCell(3).setCellValue(sanitize(career.getDescription()));
                    row.createCell(4).setCellValue(sanitize(career.getTechStack()));
                    row.createCell(5).setCellValue(career.getTeamSize() != null ? career.getTeamSize() + "名" : "");
                }
            } else {
                sheet.createRow(rowIndex++).createCell(0).setCellValue("登録されている職務経歴はありません。");
            }
            
            sheet.setColumnWidth(0, 20 * 256);
            sheet.setColumnWidth(1, 15 * 256);
            sheet.setColumnWidth(2, 15 * 256);
            sheet.setColumnWidth(3, 40 * 256);
            sheet.setColumnWidth(4, 30 * 256);
            sheet.setColumnWidth(5, 12 * 256);

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                workbook.write(baos);
                return baos.toByteArray();
            } finally {
                workbook.dispose();
            }
        } catch (Exception e) {
            log.error("Failed to generate Excel for engineerId: " + engineerId, e);
            throw new RuntimeException("Excelの生成に失敗しました", e);
        }
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (!trimmed.isEmpty() && "=+-@\t\r\n".indexOf(trimmed.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    private void addCell(PdfPTable table, String text, Font font, boolean isHeader) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "", font));
        cell.setPadding(5);
        if (isHeader) {
            cell.setBackgroundColor(new Color(240, 240, 240));
        }
        table.addCell(cell);
    }

    private SkillSheetDto fetchSkillSheetDto(Long engineerId) {
        Engineer engineer = engineerService.getById(engineerId);
        if (engineer == null) {
            throw new IllegalArgumentException("指定されたエンジニアが見つかりません。 ID: " + engineerId);
        }

        List<EngineerSkillDetailDto> skills = engineerSkillService.listDetail(engineerId);
        
        List<EngineerCareer> careers = engineerCareerService.list(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EngineerCareer>()
                        .eq(EngineerCareer::getEngineerId, engineerId)
                        .orderByDesc(EngineerCareer::getPeriodFrom)
        );

        return SkillSheetDtoMapper.toDto(engineer, skills, careers);
    }
}
