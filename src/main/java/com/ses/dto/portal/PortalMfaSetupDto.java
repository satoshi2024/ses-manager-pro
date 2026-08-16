package com.ses.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MFA設定応答（設定画面用）。secret原文は初回のみ返し、以後は保持しない。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PortalMfaSetupDto {
    private String secret;
    private String otpauthUri;
}
