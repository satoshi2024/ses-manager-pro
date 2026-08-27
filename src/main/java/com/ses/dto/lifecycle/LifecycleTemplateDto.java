package com.ses.dto.lifecycle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * ライフサイクルテンプレートDTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LifecycleTemplateDto {

    private Long id;

    @NotBlank(message = "テンプレート種別は必須です")
    private String templateType;

    @NotBlank(message = "テンプレート名は必須です")
    private String name;

    private String description;

    private Integer versionNo;

    private String status;

    @NotNull(message = "有効開始日は必須です")
    private LocalDate validFrom;

    private LocalDate validTo;

    private List<LifecycleTemplateTaskDto> tasks;
}
