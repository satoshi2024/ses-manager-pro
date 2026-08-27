package com.ses.dto.lifecycle;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 退社ゲート検証結果DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResignationGateResultDto {

    /**
     * 全チェック項目が通過したか
     */
    private boolean passed;

    /**
     * 阻害理由サマリー
     */
    private String summary;

    /**
     * 個別チェック項目一覧
     */
    private List<GateItemResult> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GateItemResult {
        private String code;
        private String name;
        private boolean passed;
        private boolean autoExecutable;
        private String message;
        private boolean waived;
        private Long approvalRequestId;
    }
}
