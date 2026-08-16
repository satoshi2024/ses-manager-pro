package com.ses.dto.payroll;

import lombok.Builder;
import lombok.Data;

/**
 * freee接続状態DTO。company ID、token有効期限、token等の秘密は返さない。
 * status: DISCONNECTED / CONNECTED / REAUTH_REQUIRED / MISCONFIGURED
 */
@Data
@Builder
public class FreeeConnectionStatusDto {

    private String status;

    /** status == CONNECTED から導出（S11/S15/CashFlowのboolean contract維持）。 */
    private boolean connected;

    /** 接続事業所名（非接続時はnull）。 */
    private String companyName;

    /** 日本語の次アクション。 */
    private String action;
}
