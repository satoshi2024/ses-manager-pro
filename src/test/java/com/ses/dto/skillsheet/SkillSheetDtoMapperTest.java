package com.ses.dto.skillsheet;

import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerCareer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillSheetDtoMapperTest {

    @Test
    void testToDto_WhiteListCheck() {
        // Arrange
        Engineer engineer = new Engineer();
        engineer.setId(1L);
        engineer.setFullName("山田 太郎");
        engineer.setNearestStation("東京");
        engineer.setAvailableDate(LocalDate.of(2024, 1, 1));
        
        // 機微情報・対象外情報
        engineer.setExpectedUnitPrice(new BigDecimal("800000"));
        engineer.setStatus("稼動中");
        engineer.setRemarks("連絡先: 090-XXXX-XXXX");

        EngineerSkillDetailDto skill = new EngineerSkillDetailDto();
        skill.setSkillName("Java");
        skill.setProficiency("上級");
        skill.setExperienceYears(5);

        EngineerCareer career = new EngineerCareer();
        career.setPeriodFrom(LocalDate.of(2020, 4, 1));
        career.setPeriodTo(LocalDate.of(2023, 3, 31));
        career.setProjectName("極秘プロジェクト"); // 出力対象外
        career.setClientIndustry("金融");
        career.setRole("PL");
        career.setDescription("要件定義からリリースまで");
        career.setTechStack("Java, Spring Boot");
        career.setTeamSize(10);

        // Act
        SkillSheetDto result = SkillSheetDtoMapper.toDto(engineer, List.of(skill), List.of(career));

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getFullName()).isEqualTo("山田 太郎");
        assertThat(result.getNearestStation()).isEqualTo("東京");
        assertThat(result.getAvailableDate()).isEqualTo(LocalDate.of(2024, 1, 1));

        // スキル検証
        assertThat(result.getSkills()).hasSize(1);
        SkillSheetSkillDto resultSkill = result.getSkills().get(0);
        assertThat(resultSkill.getSkillName()).isEqualTo("Java");
        assertThat(resultSkill.getProficiency()).isEqualTo("上級");
        assertThat(resultSkill.getExperienceYears()).isEqualTo(5);

        // 経歴検証
        assertThat(result.getCareers()).hasSize(1);
        SkillSheetCareerDto resultCareer = result.getCareers().get(0);
        assertThat(resultCareer.getPeriodFrom()).isEqualTo(LocalDate.of(2020, 4, 1));
        assertThat(resultCareer.getPeriodTo()).isEqualTo(LocalDate.of(2023, 3, 31));
        assertThat(resultCareer.getClientIndustry()).isEqualTo("金融");
        assertThat(resultCareer.getRole()).isEqualTo("PL");
        assertThat(resultCareer.getDescription()).isEqualTo("要件定義からリリースまで");
        assertThat(resultCareer.getTechStack()).isEqualTo("Java, Spring Boot");
        assertThat(resultCareer.getTeamSize()).isEqualTo(10);

        // 機微情報が含まれていないことをアサーション (クラス定義に存在しないことをリフレクション等で確認するより、そもそもフィールドがないことを以て担保されるが、念の為テストコード上で明示的にコメントを残す)
        // ExpectedUnitPrice, ProjectName, Remarks etc. shouldn't be mapped.
        // Also verify JSON string doesn't contain these sensitive data.
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            String json = mapper.writeValueAsString(result);
            assertThat(json).doesNotContain("800000");
            assertThat(json).doesNotContain("稼動中");
            assertThat(json).doesNotContain("090-XXXX-XXXX");
            assertThat(json).doesNotContain("極秘プロジェクト");
        } catch (Exception e) {
            org.junit.jupiter.api.Assertions.fail("JSON mapping failed", e);
        }
    }

    @Test
    void testToDto_WithAnonymize_ShouldUseInitialName() {
        Engineer engineer = new Engineer();
        engineer.setId(1L);
        engineer.setFullName("鈴木 一郎");
        engineer.setInitialName("S.I");
        engineer.setNearestStation("横浜");

        SkillSheetOptions options = new SkillSheetOptions();
        options.setAnonymize(true);

        SkillSheetDto result = SkillSheetDtoMapper.toDto(engineer, List.of(), List.of(), options);

        assertThat(result).isNotNull();
        assertThat(result.getFullName()).isEqualTo("S.I");
        // 匿名化時は最寄駅（居住地の手がかり）も伏せる
        assertThat(result.getNearestStation()).isNull();
    }

    @Test
    void testToDto_WithAnonymizeButNoInitial_ShouldGenerateInitial() {
        Engineer engineer = new Engineer();
        engineer.setId(1L);
        engineer.setFullName("高橋");
        engineer.setFullNameKana("タカハシ");

        SkillSheetOptions options = new SkillSheetOptions();
        options.setAnonymize(true);

        SkillSheetDto result = SkillSheetDtoMapper.toDto(engineer, List.of(), List.of(), options);

        assertThat(result).isNotNull();
        assertThat(result.getFullName()).isEqualTo("タ.");
    }
}
