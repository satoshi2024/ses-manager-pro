package com.ses.dto.servicedesk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * サービスリクエストコメントDTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceCommentDto {

    private Long id;

    private Long serviceRequestId;

    private String authorType;

    private Long authorId;

    private String authorName;

    private String visibility;

    private String commentText;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
