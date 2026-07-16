package com.ses.dto.salesactivity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class SalesActivityCreateRequest {
    @NotBlank(message = "活動種別は必須です")
    @Size(max = 20, message = "活動種別は20文字以内で入力してください")
    private String activityType;

    @NotNull(message = "活動日は必須です")
    private LocalDate activityDate;

    @NotBlank(message = "タイトルは必須です")
    @Size(max = 200, message = "タイトルは200文字以内で入力してください")
    private String title;

    private String content;
    private LocalDate nextActionDate;
}
