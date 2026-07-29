package com.ses.dto.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 管理者承認によるOIDC外部identity link申請。 */
@Data
public class ExternalIdentityProvisionRequest {

    @NotNull
    private Long userId;

    @NotBlank
    @Size(max = 255)
    private String subject;

    @Size(max = 255)
    private String emailSnapshot;
}
