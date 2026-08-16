package com.ses.dto.portal;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 利用規約同意リクエスト。クライアントが同意したversionを送り、現行versionと一致する場合のみ記録する。
 */
@Data
public class PortalConsentRequest {

    @NotBlank(message = "error.portal.terms.versionRequired")
    private String termsVersion;
}
