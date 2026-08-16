package com.ses.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * BP portalの提出物（請求書/作業報告書。archive CLEAN後のみdownload可: R4.4）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalBpSubmissionDto {
    private Long documentId;
    private String title;
    private String originalName;
    private LocalDateTime createdAt;
    private boolean downloadable;
}
