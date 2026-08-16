package com.ses.dto.portal;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 顧客portalの請求書登録リクエスト（受領確認・支払予定日・問い合わせ。R2.3）。
 * 入金済状態の変更はできない（変更APIが存在しない）。
 */
@Data
public class PortalInvoiceRegisterRequest {
    /** 受領確認（初回のみ記録。既に確認済みなら無視） */
    private Boolean receivedConfirmed;
    /** 支払予定日 */
    private LocalDate paymentExpectedDate;
    /** 問い合わせ */
    @Size(max = 1000, message = "error.portal.invoice.inquiryTooLong")
    private String inquiry;
}
