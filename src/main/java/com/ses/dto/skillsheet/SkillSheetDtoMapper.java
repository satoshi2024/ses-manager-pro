package com.ses.dto.skillsheet;

import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerCareer;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class SkillSheetDtoMapper {

    public static SkillSheetDto toDto(Engineer engineer, List<EngineerSkillDetailDto> skills, List<EngineerCareer> careers, SkillSheetOptions options) {
        if (engineer == null) {
            return null;
        }

        if (options == null) {
            options = new SkillSheetOptions();
        }

        String displayName = engineer.getFullName();
        if (options.isAnonymize()) {
            if (StringUtils.hasText(engineer.getInitialName())) {
                displayName = engineer.getInitialName();
            } else {
                String source = StringUtils.hasText(engineer.getFullNameKana()) ? engineer.getFullNameKana() : engineer.getFullName();
                if (StringUtils.hasText(source)) {
                    displayName = source.substring(0, 1) + ".";
                } else {
                    displayName = "No Name";
                }
            }
        }

        List<SkillSheetSkillDto> skillDtos = skills != null ? skills.stream()
                .map(s -> SkillSheetSkillDto.builder()
                        .skillName(s.getSkillName())
                        .proficiency(s.getProficiency())
                        .experienceYears(s.getExperienceYears())
                        .build())
                .collect(Collectors.toList()) : List.of();

        List<SkillSheetCareerDto> careerDtos = careers != null ? careers.stream()
                .sorted(Comparator.comparing(EngineerCareer::getPeriodFrom, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(c -> SkillSheetCareerDto.builder()
                        .periodFrom(c.getPeriodFrom())
                        .periodTo(c.getPeriodTo())
                        .clientIndustry(c.getClientIndustry())
                        .role(c.getRole())
                        .description(c.getDescription())
                        .techStack(c.getTechStack())
                        .teamSize(c.getTeamSize())
                        .build())
                .collect(Collectors.toList()) : List.of();

        return SkillSheetDto.builder()
                .fullName(displayName)
                .nearestStation(options.isAnonymize() ? null : engineer.getNearestStation())
                .availableDate(engineer.getAvailableDate())
                .skills(skillDtos)
                .careers(careerDtos)
                .build();
    }
    
    public static SkillSheetDto toDto(Engineer engineer, List<EngineerSkillDetailDto> skills, List<EngineerCareer> careers) {
        return toDto(engineer, skills, careers, new SkillSheetOptions());
    }
}
