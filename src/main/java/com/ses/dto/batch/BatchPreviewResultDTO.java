package com.ses.dto.batch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 一括操作プレビューレスポンス DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchPreviewResultDTO {

    private int totalCount;
    private int validCount;
    private int invalidCount;
    private String previewToken;

    @Builder.Default
    private List<PreviewItem> items = new ArrayList<>();

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PreviewItem {
        private Long id;
        private String currentStatus;
        private String targetStatus;
        private boolean valid;
        private String reason;
    }
}
