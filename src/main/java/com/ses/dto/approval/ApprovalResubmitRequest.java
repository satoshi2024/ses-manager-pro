package com.ses.dto.approval;

import java.math.BigDecimal;
import java.util.Map;

/** 差戻し後の再申請入力。未指定fieldは元の値を維持する。 */
public record ApprovalResubmitRequest(Map<String, Object> payload, Map<String, Object> diff,
                                       BigDecimal amountSnapshot) {
}
