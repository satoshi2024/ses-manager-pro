package com.ses.dto.mail;

import lombok.AllArgsConstructor;
import lombok.Data;

/** メール送信受付結果。画面は deliveryId で送信状態を追跡できる。 */
@Data
@AllArgsConstructor
public class MailDispatchResult {
    private Long deliveryId;
    private String status;
}
