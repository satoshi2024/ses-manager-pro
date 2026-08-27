package com.ses.dto.servicedesk;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerQbrCreateRequest {
    @NotNull(message = "顧客IDは必須です")
    private Long customerId;

    @NotNull(message = "開催日は必須です")
    private LocalDate meetingDate;

    @NotBlank(message = "タイトルは必須です")
    private String title;

    private String agenda;
    private String minutes;
    private String actionItems;

    @Min(value = 1, message = "スコアは1〜5の間で指定してください")
    @Max(value = 5, message = "スコアは1〜5の間で指定してください")
    private Integer csatScore;

    private String attendees;
}
