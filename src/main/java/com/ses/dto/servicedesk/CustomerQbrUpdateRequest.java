package com.ses.dto.servicedesk;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 定例会(QBR)更新リクエスト
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerQbrUpdateRequest {

    @NotNull(message = "開催日は必須です")
    private LocalDate meetingDate;

    @NotBlank(message = "タイトルは必須です")
    private String title;

    private String agenda;

    private String minutes;

    private String actionItems;

    private String attendees;
}
