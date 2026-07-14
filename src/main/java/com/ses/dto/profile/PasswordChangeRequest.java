package com.ses.dto.profile;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PasswordChangeRequest {
    @NotBlank(message = "現在のパスワードは必須です")
    private String currentPassword;

    @NotBlank(message = "新しいパスワードは必須です")
    private String newPassword;
}

