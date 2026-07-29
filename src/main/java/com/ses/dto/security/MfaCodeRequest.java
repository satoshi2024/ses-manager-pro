package com.ses.dto.security;

import jakarta.validation.constraints.NotBlank;

public record MfaCodeRequest(
        @NotBlank(message = "MFAコードは必須です") String code) {
}
