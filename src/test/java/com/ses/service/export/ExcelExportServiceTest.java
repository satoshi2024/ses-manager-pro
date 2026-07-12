package com.ses.service.export;

import com.ses.dto.export.ContractExportDto;
import com.ses.dto.export.MonthlyRevenueDto;
import com.ses.entity.Engineer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ExcelExportService の単体テスト。
 * Spring コンテキストは不要(純粋な変換処理のため new で直接インスタンス化する)。
 */
class ExcelExportServiceTest {

    private final ExcelExportService service = new ExcelExportService();

    @Test
    void exportEngineers_writesHeaderAndRows() throws IOException {
        Engineer e1 = new Engineer();
        e1.setFullName("山田 太郎");
        e1.setFullNameKana("ヤマダ タロウ");
        e1.setStatus("稼動中");
        e1.setEmploymentType("正社員");
        e1.setExperienceYears(5);
        e1.setExpectedUnitPrice(new BigDecimal("800000"));
        e1.setAvailableDate(LocalDate.of(2026, 4, 1));

        Engineer e2 = new Engineer();
        e2.setFullName("鈴木 花子");
        e2.setFullNameKana("スズキ ハナコ");
        e2.setStatus("Bench");
        e2.setEmploymentType("BP");
        e2.setExperienceYears(3);
        e2.setExpectedUnitPrice(new BigDecimal("650000"));
        e2.setAvailableDate(null);

        byte[] bytes = service.exportEngineers(List.of(e1, e2));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);

            Row header = sheet.getRow(0);
            assertEquals("氏名", header.getCell(0).getStringCellValue());
            assertEquals("フリガナ", header.getCell(1).getStringCellValue());
            assertEquals("稼動状態", header.getCell(2).getStringCellValue());
            assertEquals("雇用形態", header.getCell(3).getStringCellValue());
            assertEquals("経験年数", header.getCell(4).getStringCellValue());
            assertEquals("希望単価", header.getCell(5).getStringCellValue());
            assertEquals("稼動可能日", header.getCell(6).getStringCellValue());

            // header + 2 rows
            assertEquals(3, sheet.getPhysicalNumberOfRows());

            Row row1 = sheet.getRow(1);
            assertEquals("山田 太郎", row1.getCell(0).getStringCellValue());
            assertEquals(800000.0, row1.getCell(5).getNumericCellValue());

            Row row2 = sheet.getRow(2);
            assertEquals("鈴木 花子", row2.getCell(0).getStringCellValue());
            assertEquals("Bench", row2.getCell(2).getStringCellValue());
        }
    }

    @Test
    void exportContracts_writesHeaderAndRows() throws IOException {
        ContractExportDto d1 = ContractExportDto.builder()
                .contractNo("C-202604-0001")
                .engineerName("山田 太郎")
                .projectName("大手金融基盤刷新")
                .customerName("株式会社サンプル")
                .contractType("準委任")
                .startDate(LocalDate.of(2026, 4, 1))
                .endDate(LocalDate.of(2026, 9, 30))
                .sellingPrice(new BigDecimal("800000"))
                .costPrice(new BigDecimal("600000"))
                .status("稼動中")
                .build();

        ContractExportDto d2 = ContractExportDto.builder()
                .contractNo("C-202604-0002")
                .engineerName("鈴木 花子")
                .projectName("ECサイト保守")
                .customerName("テスト商事")
                .contractType("派遣")
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(null)
                .sellingPrice(new BigDecimal("700000"))
                .costPrice(new BigDecimal("500000"))
                .status("稼動中")
                .build();

        byte[] bytes = service.exportContracts(List.of(d1, d2));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);

            Row header = sheet.getRow(0);
            assertEquals("契約番号", header.getCell(0).getStringCellValue());
            assertEquals("要員名", header.getCell(1).getStringCellValue());
            assertEquals("案件名", header.getCell(2).getStringCellValue());
            assertEquals("顧客名", header.getCell(3).getStringCellValue());
            assertEquals("契約形態", header.getCell(4).getStringCellValue());
            assertEquals("開始日", header.getCell(5).getStringCellValue());
            assertEquals("終了日", header.getCell(6).getStringCellValue());
            assertEquals("売上単価", header.getCell(7).getStringCellValue());
            assertEquals("原価", header.getCell(8).getStringCellValue());
            assertEquals("ステータス", header.getCell(9).getStringCellValue());

            assertEquals(3, sheet.getPhysicalNumberOfRows());

            Row row1 = sheet.getRow(1);
            assertEquals("C-202604-0001", row1.getCell(0).getStringCellValue());
            assertEquals(800000.0, row1.getCell(7).getNumericCellValue());
            assertEquals(600000.0, row1.getCell(8).getNumericCellValue());

            Row row2 = sheet.getRow(2);
            assertEquals("鈴木 花子", row2.getCell(1).getStringCellValue());
            Cell endDateCell = row2.getCell(6);
            assertTrue(endDateCell == null || endDateCell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK);
        }
    }

    @Test
    void exportMonthlyRevenue_marksActualAndForecastCorrectly() throws IOException {
        MonthlyRevenueDto actualMonth = MonthlyRevenueDto.builder()
                .label("2026年4月")
                .sales(1000000L)
                .profit(300000L)
                .isActual(true)
                .build();

        MonthlyRevenueDto forecastMonth = MonthlyRevenueDto.builder()
                .label("2026年5月")
                .sales(900000L)
                .profit(250000L)
                .isActual(false)
                .build();

        byte[] bytes = service.exportMonthlyRevenue(2026, List.of(actualMonth, forecastMonth));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);

            Row header = sheet.getRow(0);
            assertEquals("対象月", header.getCell(0).getStringCellValue());
            assertEquals("売上", header.getCell(1).getStringCellValue());
            assertEquals("粗利", header.getCell(2).getStringCellValue());
            assertEquals("区分", header.getCell(3).getStringCellValue());

            assertEquals(3, sheet.getPhysicalNumberOfRows());

            Row row1 = sheet.getRow(1);
            assertEquals("2026年4月", row1.getCell(0).getStringCellValue());
            assertEquals(1000000.0, row1.getCell(1).getNumericCellValue());
            assertEquals(300000.0, row1.getCell(2).getNumericCellValue());
            assertEquals("実績", row1.getCell(3).getStringCellValue());

            Row row2 = sheet.getRow(2);
            assertEquals("2026年5月", row2.getCell(0).getStringCellValue());
            assertEquals("見込み", row2.getCell(3).getStringCellValue());
        }
    }
}
