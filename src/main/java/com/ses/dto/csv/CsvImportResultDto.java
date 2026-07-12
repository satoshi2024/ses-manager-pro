package com.ses.dto.csv;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * CSVインポート結果。成功件数と失敗行（行番号・理由）を保持する。
 */
@Data
public class CsvImportResultDto {

    private int successCount;
    private final List<RowError> errors = new ArrayList<>();

    public void addError(int line, String message) {
        errors.add(new RowError(line, message));
    }

    public void incrementSuccess() {
        this.successCount++;
    }

    @Data
    public static class RowError {
        private final int line;
        private final String message;
    }
}
