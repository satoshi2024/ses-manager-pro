package com.ses.dto.attendance.sync;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 同期実行の結果（error UI・status APIへ返す）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceSyncResultDto {
    private String provider;
    private String direction;
    private String workMonth;
    private String correlationId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** push送信成功件数（外部レコード件数） */
    private Integer pushedCount;
    /** push重複スキップ件数（冪等キー一致で外部1件） */
    private Integer duplicateSkippedCount;
    /** pull取得レコード件数 */
    private Integer pulledCount;
    /** pullで登録した件数（source='freee'行） */
    private Integer registeredCount;
    /** 締め済み・承認済み月への外部更新を拒否した件数（finding） */
    private Integer rejectedCount;
    /** 部分失敗・エラー詳細 */
    private List<String> errors;
    /** 次の差分取得に使うcursor（外部updated_at） */
    private String cursor;
    /** 実行が成功したか（エラーがあっても部分成功はtrue） */
    private boolean success;

    // ===== R5-P2-02: read-only照合の実体（外部レコードと本システム日次の比較結果） =====
    /** 外部レコードが本システム日次と一致した件数 */
    private Integer matchedCount;
    /** 外部レコードと本システム日次に差異がある件数 */
    private Integer diffCount;
    /** 本システムに対応する日次が無い外部レコードの件数 */
    private Integer unmatchedCount;
    /** 差異レコードの要約（直近最大20件。照合の実体としてerror UI/statusで表示） */
    private List<ReconciliationItem> differences;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReconciliationItem {
        private String sourceExternalId;
        private Long engineerId;
        private String workDate;
        private String externalValue;
        private String internalValue;
    }

    public static AttendanceSyncResultDto empty() {
        return AttendanceSyncResultDto.builder()
                .pushedCount(0)
                .duplicateSkippedCount(0)
                .pulledCount(0)
                .registeredCount(0)
                .rejectedCount(0)
                .matchedCount(0)
                .diffCount(0)
                .unmatchedCount(0)
                .errors(new ArrayList<>())
                .differences(new ArrayList<>())
                .success(true)
                .build();
    }
}
