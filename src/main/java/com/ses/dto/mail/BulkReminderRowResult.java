package com.ses.dto.mail;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 一括督促の請求書単位の結果行（R3R-20）。
 * 請求書IDとreasonを保持し、送信/失敗/skipを行と1対1で対応付けられるようにする。
 */
@Data
@AllArgsConstructor
public class BulkReminderRowResult {
    private Long invoiceId;
    /** SENT / DRY_RUN / SKIPPED / FAILED */
    private String status;
    /** 失敗・skip の理由（成功時は null） */
    private String reason;
    /** 送信受付ID（skip/失敗時は null） */
    private Long deliveryId;
}
