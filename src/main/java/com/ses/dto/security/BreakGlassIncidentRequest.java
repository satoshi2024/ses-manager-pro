package com.ses.dto.security;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.Set;

public record BreakGlassIncidentRequest(
        @NotBlank String reason,
        @NotNull Boolean idpOutageConfirmed,
        @Min(1) @Max(120) int durationMinutes,
        @NotBlank String correlationId,
        @NotEmpty Set<@Pattern(regexp = "[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+") String> allowedActions) {
}
