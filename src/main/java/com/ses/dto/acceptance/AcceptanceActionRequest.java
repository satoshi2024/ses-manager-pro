package com.ses.dto.acceptance;

import lombok.Data;

/** 検収アクション共通リクエスト（検収時は顧客確認者、差戻し時は理由が必須）。 */
@Data
public class AcceptanceActionRequest {
    /** 顧客確認者ID（検収時） */
    private Long customerContactId;
    /** 差戻し理由（差戻し時） */
    private String comment;
}
