package com.ses.dto.projectingestion;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * AI構造化解析結果DTO（案件メール）。
 */
@Data
public class ParsedProjectDto {

    /** 案件基本情報 */
    private ProjectPart project;

    /** 必須スキル一覧 */
    private List<SkillPart> skills;

    /** 抽出時の注意（単価単位不明など） */
    private List<String> warnings;

    @Data
    public static class ProjectPart {
        /** 案件名 */
        private String name;
        /** 単価下限（円単位） */
        private BigDecimal minUnitPrice;
        /** 単価上限（円単位） */
        private BigDecimal maxUnitPrice;
        /** 勤務地 */
        private String location;
        /** リモート可否（可/不可/応相談など） */
        private String remoteAllowed;
        /** 開始日 */
        private LocalDate startDate;
        /** 終了日 */
        private LocalDate endDate;
        /** 商流（元請/一次/二次など） */
        private String commercialFlow;
        /** 募集人数 */
        private Integer headCount;
        /** エンド顧客名 */
        private String endClientName;
        /** 備考・業務内容など */
        private String description;
    }

    @Data
    public static class SkillPart {
        /** スキル名 */
        private String name;
    }
}
