package com.ses.dto.engineer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class EngineerSaveDto {
    @NotBlank(message = "氏名は必須です")
    private String fullName;
    private String fullNameKana;
    private String initialName;

    /** 性別: DB ENUM に合わせた allowlist（空値＝未入力を許容） */
    @Pattern(regexp = "^(男性|女性|)$", message = "性別が不正です")
    private String gender;

    private LocalDate birthDate;

    @Size(max = 50, message = "国籍は50文字以内で入力してください")
    private String nationality;

    private String nearestStation;
    private String prefecture;
    private String railwayCompany;

    /** 雇用形態: DB ENUM に合わせた allowlist */
    @Pattern(regexp = "^(正社員|契約社員|BP|)$", message = "雇用形態が不正です")
    private String employmentType;

    @Pattern(regexp = "^(稼動中|退場予定|Bench|提案中|)$", message = "ステータスが不正です")
    private String status;

    @PositiveOrZero(message = "希望単価は0以上で入力してください")
    private BigDecimal expectedUnitPrice;

    private LocalDate availableDate;

    @Min(value = 0, message = "経験年数は0以上で入力してください")
    private Integer experienceYears;

    /** 日本語レベル: VARCHAR(20)。値域は '不問' を含む主要選択肢のみ許可（空値＝未入力を許容） */
    @Pattern(regexp = "^(ネイティブ|ビジネスレベル|日常会話レベル|基礎レベル|不問|)$", message = "日本語レベルが不正です")
    @Size(max = 20, message = "日本語レベルは20文字以内で入力してください")
    private String japaneseLevel;

    private String resumeSummary;
    private String photoUrl;
    private String remarks;
}
