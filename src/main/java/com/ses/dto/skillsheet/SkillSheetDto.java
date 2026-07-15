package com.ses.dto.skillsheet;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class SkillSheetDto {
    // 基本情報
    private String fullName;
    private String nearestStation;
    private LocalDate availableDate;

    // スキル一覧
    private List<SkillSheetSkillDto> skills;

    // 職務経歴一覧
    private List<SkillSheetCareerDto> careers;
}
