package com.ses.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * portal login応答。status:
 * <ul>
 *   <li>OK — 認証完了（session cookie発行済み）</li>
 *   <li>MFA_SETUP — MFA未設定。mfaSetup（secret/URI）を表示して有効化へ進む</li>
 *   <li>MFA_REQUIRED — MFA設定済み。mfaCodeを再送する</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalLoginResponse {
    private String status;
    private PortalMfaSetupDto mfaSetup;
    private boolean termsPending;
}
