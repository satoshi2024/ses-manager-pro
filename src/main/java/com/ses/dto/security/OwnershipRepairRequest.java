package com.ses.dto.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** repair authority が提示する構造化根拠。tenant/incidentは認証sessionから解決する。 */
@Data
public class OwnershipRepairRequest {
    @NotNull
    private Integer expectedVersion;
    @NotBlank
    private String claimToken;
    @NotBlank
    private String reason;
    @Valid
    @NotNull
    private Evidence evidence;

    @Data
    public static class Evidence {
        @NotBlank
        private String sourceType;
        @NotBlank
        private String sourceReference;
        @NotBlank
        private String statement;
    }
}
