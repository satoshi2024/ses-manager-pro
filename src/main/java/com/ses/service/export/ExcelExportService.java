package com.ses.service.export;

import com.ses.dto.export.ContractExportDto;
import com.ses.dto.export.MonthlyRevenueDto;
import com.ses.dto.invoice.AgingReportDto;
import com.ses.entity.Engineer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Excel(.xlsx)帳票出力サービス。
 * SXSSFWorkbook(ストリーミング)を使用し、生成後は必ず dispose() で一時ファイルを解放する。
 */
@Service
public class ExcelExportService {

    /** 要員一覧の日本語ヘッダー */
    private static final String[] ENGINEER_HEADERS = {
            "氏名", "フリガナ", "稼動状態", "雇用形態", "経験年数", "希望単価", "稼動可能日"
    };

    /** 契約一覧の日本語ヘッダー */
    private static final String[] CONTRACT_HEADERS = {
            "契約番号", "要員名", "案件名", "顧客名", "契約形態", "開始日", "終了日", "売上単価", "原価", "ステータス"
    };

    /** 月次売上レポートの日本語ヘッダー */
    private static final String[] REVENUE_HEADERS = {
            "対象月", "売上", "粗利", "区分"
    };

    /** エイジングレポートの日本語ヘッダー */
    private static final String[] AGING_HEADERS = {
            "顧客名", "期限内", "1-30日", "31-60日", "61-90日", "91日以上", "期限未設定", "残高計"
    };

    /**
     * 要員一覧をExcel化する。
     */
    public byte[] exportEngineers(List<Engineer> rows) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("要員一覧");
            CellStyle dateStyle = createDateStyle(workbook);
            CellStyle numberStyle = createNumberStyle(workbook);

            createHeaderRow(workbook, sheet, ENGINEER_HEADERS);
            sheet.setColumnWidth(0, 20 * 256);
            sheet.setColumnWidth(1, 20 * 256);
            sheet.setColumnWidth(2, 14 * 256);
            sheet.setColumnWidth(3, 14 * 256);
            sheet.setColumnWidth(4, 12 * 256);
            sheet.setColumnWidth(5, 14 * 256);
            sheet.setColumnWidth(6, 16 * 256);

            int rowIndex = 1;
            for (Engineer e : rows) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(sanitize(e.getFullName()));
                row.createCell(1).setCellValue(sanitize(e.getFullNameKana()));
                row.createCell(2).setCellValue(sanitize(e.getStatus()));
                row.createCell(3).setCellValue(sanitize(e.getEmploymentType()));

                Cell expCell = row.createCell(4);
                if (e.getExperienceYears() != null) {
                    expCell.setCellValue(e.getExperienceYears());
                }

                Cell priceCell = row.createCell(5);
                setBigDecimalCell(priceCell, e.getExpectedUnitPrice(), numberStyle);

                Cell dateCell = row.createCell(6);
                setDateCell(dateCell, e.getAvailableDate(), dateStyle);
            }

            return writeToBytes(workbook);
        } catch (IOException ex) {
            throw new UncheckedIOException("要員一覧Excel生成に失敗しました", ex);
        }
    }

    /**
     * 契約一覧をExcel化する。
     */
    public byte[] exportContracts(List<ContractExportDto> rows) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("契約一覧");
            CellStyle dateStyle = createDateStyle(workbook);
            CellStyle numberStyle = createNumberStyle(workbook);

            createHeaderRow(workbook, sheet, CONTRACT_HEADERS);
            sheet.setColumnWidth(0, 16 * 256);
            sheet.setColumnWidth(1, 16 * 256);
            sheet.setColumnWidth(2, 24 * 256);
            sheet.setColumnWidth(3, 20 * 256);
            sheet.setColumnWidth(4, 12 * 256);
            sheet.setColumnWidth(5, 14 * 256);
            sheet.setColumnWidth(6, 14 * 256);
            sheet.setColumnWidth(7, 14 * 256);
            sheet.setColumnWidth(8, 14 * 256);
            sheet.setColumnWidth(9, 12 * 256);

            int rowIndex = 1;
            for (ContractExportDto dto : rows) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(sanitize(dto.getContractNo()));
                row.createCell(1).setCellValue(sanitize(dto.getEngineerName()));
                row.createCell(2).setCellValue(sanitize(dto.getProjectName()));
                row.createCell(3).setCellValue(sanitize(dto.getCustomerName()));
                row.createCell(4).setCellValue(sanitize(dto.getContractType()));

                setDateCell(row.createCell(5), dto.getStartDate(), dateStyle);
                setDateCell(row.createCell(6), dto.getEndDate(), dateStyle);
                setBigDecimalCell(row.createCell(7), dto.getSellingPrice(), numberStyle);
                setBigDecimalCell(row.createCell(8), dto.getCostPrice(), numberStyle);

                row.createCell(9).setCellValue(sanitize(dto.getStatus()));
            }

            return writeToBytes(workbook);
        } catch (IOException ex) {
            throw new UncheckedIOException("契約一覧Excel生成に失敗しました", ex);
        }
    }

    /**
     * 月次売上レポートをExcel化する。
     */
    public byte[] exportMonthlyRevenue(int fiscalYear, List<MonthlyRevenueDto> rows) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(fiscalYear + "年度 月次売上レポート");
            CellStyle numberStyle = createNumberStyle(workbook);

            createHeaderRow(workbook, sheet, REVENUE_HEADERS);
            sheet.setColumnWidth(0, 16 * 256);
            sheet.setColumnWidth(1, 16 * 256);
            sheet.setColumnWidth(2, 16 * 256);
            sheet.setColumnWidth(3, 12 * 256);

            int rowIndex = 1;
            for (MonthlyRevenueDto dto : rows) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(sanitize(dto.getLabel()));

                Cell salesCell = row.createCell(1);
                salesCell.setCellValue(dto.getSales());
                salesCell.setCellStyle(numberStyle);

                Cell profitCell = row.createCell(2);
                profitCell.setCellValue(dto.getProfit());
                profitCell.setCellStyle(numberStyle);

                row.createCell(3).setCellValue(dto.isActual() ? "実績" : "見込み");
            }

            return writeToBytes(workbook);
        } catch (IOException ex) {
            throw new UncheckedIOException("月次売上レポートExcel生成に失敗しました", ex);
        }
    }

    /**
     * エイジング(債権年齢)レポートをExcel化する。
     * 顧客ごとの未回収残高を経過区分別に並べ、末尾に合計行を出す。
     */
    public byte[] exportAging(AgingReportDto report) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("エイジングレポート");
            CellStyle numberStyle = createNumberStyle(workbook);

            createHeaderRow(workbook, sheet, AGING_HEADERS);
            sheet.setColumnWidth(0, 24 * 256);
            for (int i = 1; i < AGING_HEADERS.length; i++) {
                sheet.setColumnWidth(i, 14 * 256);
            }

            int rowIndex = 1;
            if (report != null && report.getRows() != null) {
                for (AgingReportDto.Row r : report.getRows()) {
                    Row row = sheet.createRow(rowIndex++);
                    row.createCell(0).setCellValue(sanitize(r.getCustomerName()));
                    setBigDecimalCell(row.createCell(1), r.getNotDue(), numberStyle);
                    setBigDecimalCell(row.createCell(2), r.getD1to30(), numberStyle);
                    setBigDecimalCell(row.createCell(3), r.getD31to60(), numberStyle);
                    setBigDecimalCell(row.createCell(4), r.getD61to90(), numberStyle);
                    setBigDecimalCell(row.createCell(5), r.getD91plus(), numberStyle);
                    setBigDecimalCell(row.createCell(6), r.getNoDueDate(), numberStyle);
                    setBigDecimalCell(row.createCell(7), r.getBalance(), numberStyle);
                }
            }

            if (report != null && report.getTotal() != null) {
                AgingReportDto.Row t = report.getTotal();
                Row row = sheet.createRow(rowIndex);
                row.createCell(0).setCellValue("合計");
                setBigDecimalCell(row.createCell(1), t.getNotDue(), numberStyle);
                setBigDecimalCell(row.createCell(2), t.getD1to30(), numberStyle);
                setBigDecimalCell(row.createCell(3), t.getD31to60(), numberStyle);
                setBigDecimalCell(row.createCell(4), t.getD61to90(), numberStyle);
                setBigDecimalCell(row.createCell(5), t.getD91plus(), numberStyle);
                setBigDecimalCell(row.createCell(6), t.getNoDueDate(), numberStyle);
                setBigDecimalCell(row.createCell(7), t.getBalance(), numberStyle);
            }

            return writeToBytes(workbook);
        } catch (IOException ex) {
            throw new UncheckedIOException("エイジングレポートExcel生成に失敗しました", ex);
        }
    }

    /**
     * ヘッダー行(row 0)を作成し、太字フォントを適用する。
     */
    private void createHeaderRow(SXSSFWorkbook workbook, Sheet sheet, String... headers) {
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);

        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFont(headerFont);

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    private CellStyle createDateStyle(SXSSFWorkbook workbook) {
        DataFormat format = workbook.createDataFormat();
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(format.getFormat("yyyy/mm/dd"));
        return style;
    }

    private CellStyle createNumberStyle(SXSSFWorkbook workbook) {
        DataFormat format = workbook.createDataFormat();
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(format.getFormat("#,##0"));
        return style;
    }

    private void setDateCell(Cell cell, LocalDate date, CellStyle dateStyle) {
        if (date != null) {
            cell.setCellValue(date);
            cell.setCellStyle(dateStyle);
        }
    }

    private void setBigDecimalCell(Cell cell, BigDecimal value, CellStyle numberStyle) {
        if (value != null) {
            cell.setCellValue(value.doubleValue());
            cell.setCellStyle(numberStyle);
        }
    }

    /**
     * セル値として書き込む文字列をサニタイズする。
     * null は空文字に変換し、さらに Excel/CSV数式インジェクション対策として
     * セル先頭が「=」「+」「-」「@」「タブ」で始まる場合は先頭にシングルクォートを付与し、
     * Excel側で数式として評価されず文字列として表示されるようにする
     * (要員名・案件名・顧客名などユーザー入力に起因する値を含むため必須の対策)。
     */
    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        if (!value.isEmpty() && "=+-@\t".indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    private byte[] writeToBytes(SXSSFWorkbook workbook) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            workbook.write(baos);
            return baos.toByteArray();
        } finally {
            workbook.dispose();
        }
    }
}
