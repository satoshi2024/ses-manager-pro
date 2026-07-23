package com.ses.dto.resume;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 確定APIの入力DTO。
 * レビュー後に編集された ParsedResumeDto の内容を持つ。
 */
@Data
public class ReviewedResumeDto {

    /** 要員基本情報 */
    private EngineerPart engineer;

    /** スキル一覧 */
    private List<SkillPart> skills;

    /** 経歴一覧 */
    private List<CareerPart> careers;

    /** レビューメモ */
    private String reviewNote;

    @Data
    public static class EngineerPart {
        private String fullName;
        private String fullNameKana;
        private String initialName;
        private String gender;
        private LocalDate birthDate;
        private String nationality;
        private String nearestStation;
        private String prefecture;
        private String railwayCompany;
        private String employmentType;
        private String status;
        private BigDecimal expectedUnitPrice;
        private LocalDate availableDate;
        private Integer experienceYears;
        private String japaneseLevel;
        private String resumeSummary;
    }

    @Data
    public static class SkillPart {
        private String name;
        private String proficiency;
        private Integer experienceYears;
    }

    @Data
    public static class CareerPart {
        private LocalDate periodFrom;
        private LocalDate periodTo;
        private String projectName;
        private String clientIndustry;
        private String role;
        private String techStack;
        private String description;
        private Integer teamSize;
    }
}
