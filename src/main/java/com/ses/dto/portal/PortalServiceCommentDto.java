package com.ses.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 顧客ポータル用コメントDTO（内部メモは構造的に非公開）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalServiceCommentDto {

    private Long id;

    private Long serviceRequestId;

    private String authorType;

    private String authorName;

    private String commentText;

    private LocalDateTime createdAt;
}
