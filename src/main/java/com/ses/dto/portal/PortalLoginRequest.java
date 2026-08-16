package com.ses.dto.portal;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * portal loginリクエスト。MFAコードはMFA設定済みの場合のみ。
 */
@Data
public class PortalLoginRequest {

    @NotBlank(message = "error.portal.email.required")
    @Email(message = "error.portal.email.invalid")
    private String email;

    @NotBlank(message = "error.portal.password.required")
    private String password;

    /** TOTP 6桁コードまたは1回限りrecovery code（MFA設定済みの場合） */
    private String mfaCode;
}
