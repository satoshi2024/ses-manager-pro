package com.ses.dto.projectingestion;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 確定APIの入力DTO（案件）。
 * レビュー後に編集された内容を持つ。
 */
@Data
public class ReviewedProjectDto {

    /** 案件基本情報 */
    private ProjectPart project;

    /** 必須スキル一覧 */
    private List<SkillPart> skills;

    /** レビューメモ */
    private String reviewNote;

    @Data
    public static class ProjectPart {
        private String name;
        private BigDecimal minUnitPrice;
        private BigDecimal maxUnitPrice;
        private String location;
        private String remoteAllowed;
        private LocalDate startDate;
        private LocalDate endDate;
        private String commercialFlow;
        private Integer headCount;
        private String endClientName;
        private String description;
    }

    @Data
    public static class SkillPart {
        private String name;
    }
}
