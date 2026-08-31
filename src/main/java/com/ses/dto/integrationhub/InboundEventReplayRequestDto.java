package com.ses.dto.integrationhub;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** replay reasonだけを受け付ける。operator reference/generationはserver-sideで導出する。 */
public record InboundEventReplayRequestDto(
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}")
        String reasonCode) {
}
