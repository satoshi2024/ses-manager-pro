package com.ses.dto.security;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record BreakGlassIncidentRequest(
        @NotBlank String reason,
        @NotNull Boolean idpOutageConfirmed,
        @Min(1) @Max(120) int durationMinutes,
        @NotBlank String correlationId) {
}
