package com.ses.service.impl;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.ses.config.UploadProperties;
import com.ses.service.DocumentTextExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * PDF/docx/xlsx ファイルからプレーンテキストを抽出するサービス実装。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentTextExtractorImpl implements DocumentTextExtractor {

    /** 抽出テキストの上限文字数（AIトークン超過防止） */
    private static final int MAX_CHARS = 60_000;

    private final UploadProperties uploadProperties;

    @Override
    public String extract(String storedFileName, String ext) {
        Path filePath = Paths.get(uploadProperties.getBasePath())
                .toAbsolutePath()
                .normalize()
                .resolve(storedFileName)
                .normalize();

        if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
            log.warn("ファイルが見つかりません: {}", storedFileName);
            return "";
        }

        try {
            String text = switch (ext.toLowerCase()) {
                case "pdf" -> extractPdf(filePath);
                case "docx" -> extractDocx(filePath);
                case "xlsx" -> extractXlsx(filePath);
                case "txt", "eml" -> Files.readString(filePath);
                default -> {
                    log.warn("未対応のファイル形式: {}", ext);
                    yield "";
                }
            };
            // 上限で切り詰め
            if (text != null && text.length() > MAX_CHARS) {
                log.info("テキストが上限を超えたため切り詰めます: fileName={}, chars={}", storedFileName, text.length());
                text = text.substring(0, MAX_CHARS);
            }
            return text != null ? text : "";
        } catch (Exception e) {
            log.error("テキスト抽出に失敗しました: fileName={}", storedFileName, e);
            return "";
        }
    }

    private String extractPdf(Path filePath) throws Exception {
        try (PdfReader reader = new PdfReader(filePath.toString())) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= reader.getNumberOfPages(); i++) {
                sb.append(extractor.getTextFromPage(i)).append("\n");
            }
            return sb.toString();
        }
    }

    private String extractDocx(Path filePath) throws Exception {
        try (InputStream is = Files.newInputStream(filePath);
             XWPFDocument doc = new XWPFDocument(is);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    private String extractXlsx(Path filePath) throws Exception {
        try (InputStream is = Files.newInputStream(filePath);
             XSSFWorkbook workbook = new XSSFWorkbook(is)) {
            StringBuilder sb = new StringBuilder();
            org.apache.poi.ss.usermodel.DataFormatter dataFormatter = new org.apache.poi.ss.usermodel.DataFormatter();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                var sheet = workbook.getSheetAt(i);
                for (var row : sheet) {
                    for (var cell : row) {
                        String cellValue = dataFormatter.formatCellValue(cell);
                        if (cellValue != null && !cellValue.isBlank()) {
                            sb.append(cellValue).append(" ");
                        }
                    }
                    sb.append("\n");
                }
            }
            return sb.toString();
        }
    }
}
