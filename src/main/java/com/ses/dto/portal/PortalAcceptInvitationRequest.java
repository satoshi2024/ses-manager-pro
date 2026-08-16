package com.ses.dto.portal;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 招待受諾リクエスト。token・email・組織・期限・未使用の4条件を検証する（design §6.1・R5）。
 */
@Data
public class PortalAcceptInvitationRequest {

    @NotBlank(message = "error.portal.invite.tokenRequired")
    private String token;

    @NotBlank(message = "error.portal.email.required")
    @Email(message = "error.portal.email.invalid")
    private String email;

    @NotBlank(message = "error.portal.displayName.required")
    @Size(max = 255, message = "error.portal.displayName.tooLong")
    private String displayName;

    @NotBlank(message = "error.portal.password.required")
    @Size(min = 8, max = 128, message = "error.portal.password.length")
    private String password;
}
