package com.ses.dto.skill;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SkillTagSaveDto {
    @NotBlank(message = "スキル名は必須です")
    @Size(max = 100, message = "スキル名は100文字以内で入力してください")
    private String skillName;

    @NotBlank(message = "カテゴリは必須です")
    @Size(max = 50, message = "カテゴリは50文字以内で入力してください")
    private String category;
}
