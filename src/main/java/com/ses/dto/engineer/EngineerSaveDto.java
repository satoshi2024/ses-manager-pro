package com.ses.dto.engineer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Min;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class EngineerSaveDto {
    @NotBlank(message = "氏名は必須です")
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

    @Pattern(regexp = "^(稼動中|退場予定|Bench|提案中|)$", message = "ステータスが不正です")
    private String status;

    @PositiveOrZero(message = "希望単価は0以上で入力してください")
    private BigDecimal expectedUnitPrice;
    
    private LocalDate availableDate;

    @Min(value = 0, message = "経験年数は0以上で入力してください")
    private Integer experienceYears;
    
    private String japaneseLevel;
    private String resumeSummary;
    private String photoUrl;
    private String remarks;
}
