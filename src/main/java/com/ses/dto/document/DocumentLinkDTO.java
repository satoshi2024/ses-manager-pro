package com.ses.dto.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文書リンク情報DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentLinkDTO {
    private Long id;
    private Long documentId;
    private String targetType;
    private Long targetId;
    private String targetName;
    private LocalDateTime createdAt;
}
