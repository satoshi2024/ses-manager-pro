package com.ses.dto.skillsheet;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillSheetOptions {
    @Builder.Default
    private boolean anonymize = false;
    @Builder.Default
    private String template = "STANDARD";
}
