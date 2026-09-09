package com.ses.dto.security;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** ownership修復時に管理者が提示する根拠。tenantは認証contextと一致しなければならない。 */
@Data
public class OwnershipRepairRequest {
    @NotBlank
    private String tenantId;
    @NotBlank
    private String reason;
    @NotBlank
    private String evidence;
}
