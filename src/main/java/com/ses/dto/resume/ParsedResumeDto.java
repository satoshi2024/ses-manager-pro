package com.ses.dto.resume;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * AI構造化解析結果DTO。
 * スキルシート・PDF/docx/xlsxから抽出された情報。
 */
@Data
public class ParsedResumeDto {

    /** 要員基本情報 */
    private EngineerPart engineer;

    /** スキル一覧 */
    private List<SkillPart> skills;

    /** 経歴一覧 */
    private List<CareerPart> careers;

    /** 抽出時の注意（単価単位不明・日付曘昧等） */
    private List<String> warnings;

    @Data
    public static class EngineerPart {
        /** 氏名 */
        private String fullName;
        /** 氏名カナ */
        private String fullNameKana;
        /** 性別（男性/女性） */
        private String gender;
        /** 生年月日 */
        private LocalDate birthDate;
        /** 国籍 */
        private String nationality;
        /** 最寄り駅 */
        private String nearestStation;
        /** 都道府県 */
        private String prefecture;
        /** 鱊道会社・路線 */
        private String railwayCompany;
        /** 経験年数 */
        private Integer experienceYears;
        /** 日本語レベル */
        private String japaneseLevel;
        /** 希望単価（円単位） */
        private BigDecimal expectedUnitPrice;
        /** 経歴サマリ */
        private String resumeSummary;
    }

    @Data
    public static class SkillPart {
        /** スキル名（正式名。例: React, AWS） */
        private String name;
        /** ι̎レベル（初級/中級/上級） */
        private String proficiency;
        /** 経験年数 */
        private Integer experienceYears;
    }

    @Data
    public static class CareerPart {
        /** 従事開始日 */
        private LocalDate periodFrom;
        /** 従事終了日 */
        private LocalDate periodTo;
        /** プロジェクト名 */
        private String projectName;
        /** クライアント業界 */
        private String clientIndustry;
        /** 役割 */
        private String role;
        /** 技術スタック */
        private String techStack;
        /** 業務内容 */
        private String description;
        /** チーム山 */
        private Integer teamSize;
    }
}
