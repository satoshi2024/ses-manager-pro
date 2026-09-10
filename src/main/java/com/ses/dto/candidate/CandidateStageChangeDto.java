package com.ses.dto.candidate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 候補者ステージ変更入力。expectedVersionは必須。 */
@Data
public class CandidateStageChangeDto {
    @NotBlank
    private String stage;
    @NotNull
    private Integer expectedVersion;
    private String reason;
    private String remarks;
}
