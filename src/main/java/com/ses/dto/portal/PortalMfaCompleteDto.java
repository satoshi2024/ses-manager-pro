package com.ses.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MFA有効化応答。recovery codeはこの1回だけ返す。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PortalMfaCompleteDto {
    private String recoveryCode;
}
