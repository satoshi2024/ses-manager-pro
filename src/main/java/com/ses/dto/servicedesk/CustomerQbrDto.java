package com.ses.dto.servicedesk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 定例会(QBR)記録DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerQbrDto {

    private Long id;

    private Long customerId;

    private String customerName;

    private LocalDate meetingDate;

    private String title;

    private String agenda;

    private String minutes;

    private String actionItems;

    private String attendees;

    private Long createdBy;

    private String createdByName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
