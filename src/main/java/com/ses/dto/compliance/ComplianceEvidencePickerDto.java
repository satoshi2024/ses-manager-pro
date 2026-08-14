package com.ses.dto.compliance;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * R23-P1-01 §5: evidence pickerのallow-list DTO。
 * document/version/title/originalName/SHA-256/scan/createdAtだけを返す（§5）。
 */
@Data
public class ComplianceEvidencePickerDto {
    private Long documentId;
    private Long versionId;
    private String documentTitle;
    private String originalName;
    private String sha256;
    private String scanStatus;
    private LocalDateTime createdAt;
}
