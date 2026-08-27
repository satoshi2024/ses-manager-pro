package com.ses.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 顧客ポータル用サービスリクエストDTO（内部メモ・原価・内部担当者IDは構造的に非公開）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalServiceRequestDto {

    private Long id;

    private String requestNo;

    private String category;

    private String priority;

    private String subject;

    private String description;

    private String status;

    private LocalDateTime firstResponseAt;

    private LocalDateTime resolvedAt;

    private LocalDateTime closedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private List<PortalServiceCommentDto> comments;

    private Integer csatScore;

    private String csatComment;

    private boolean csatAnswerable;
}
