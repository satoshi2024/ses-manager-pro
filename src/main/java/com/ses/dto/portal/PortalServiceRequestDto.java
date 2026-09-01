package com.ses.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 顧客ポータル向けサービスリクエストDTO（非公開フィールド除外、安全化）
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

    private Integer reopenCount;

    private LocalDateTime createdAt;

    /** コメント一覧 (PORTAL_VISIBLEのみ) */
    private List<PortalServiceCommentDto> comments;

    /** CSATスコア (未回答時null) */
    private Integer csatScore;

    /** CSAT回答可能フラグ (RESOLVED/CLOSED かつ未回答) */
    private Boolean csatAnswerable;
}
