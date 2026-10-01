package com.ses.dto.candidate;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 候補者と要員の紐付け入力。tenantは認証コンテキストから解決する。 */
@Data
public class CandidateEngineerLinkDto {
    @NotNull
    private Long engineerId;
    @NotNull
    private Integer expectedVersion;
}
