package com.ses.dto.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDto {
    private Long id;
    private Boolean isRead;
    private String linkUrl;
    private String type;
    private String icon;
    private String message;
    private String date;
    private LocalDateTime sortDate;
}
