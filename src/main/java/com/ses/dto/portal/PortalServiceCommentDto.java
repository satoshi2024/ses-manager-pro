package com.ses.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 顧客ポータル向けコメントDTO（非公開フィールド除外）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalServiceCommentDto {

    private Long id;

    private Long serviceRequestId;

    /** 投稿者表示名（顧客向けにマスクまたは一般化） */
    private String authorName;

    /** 投稿者種別 (PORTAL_USER, INTERNAL_USER) */
    private String authorType;

    private String commentText;

    private LocalDateTime createdAt;
}
