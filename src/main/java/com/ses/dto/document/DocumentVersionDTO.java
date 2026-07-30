package com.ses.dto.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 版情報DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVersionDTO {
    private Long id;
    private Long documentId;
    private Integer versionNo;
    private String storageKey;
    private String originalName;
    private String contentType;
    private Long sizeBytes;
    private String sha256;
    private String sourceType;
    private String businessKey;
    private String versionDiscriminator;
    private String externalId;
    private String scanStatus;
    private String changeReason;
    private Long createdBy;
    private LocalDateTime createdAt;
}
