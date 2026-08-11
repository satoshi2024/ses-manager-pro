package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 法定帳票の交付・受領確認履歴。交付ごとに行を追加し、過去のsnapshotを上書きしない。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_document_delivery")
public class DocumentDelivery extends BaseEntity {

    private String tenantId;
    private Long contractId;
    private Long documentId;
    private String documentType;
    private String templateVersion;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String snapshotHash;
    private Long recipientContactId;
    private String recipientNameSnapshot;
    private String recipientEmailSnapshot;
    private String deliveryMethod;
    private String deliveryStatus;
    private LocalDateTime deliveredAt;
    private LocalDateTime confirmedAt;
    private String confirmationNote;
    private String idempotencyKey;
    private Long mappingVersionId;
    private String mappingVersion;
    private String mappingHash;
    private String reviewPolicyHash;
    private LocalDateTime gateEvaluatedAt;
    private String gateSnapshotHash;
    private Long profileSnapshotId;
    private String profileSnapshotHash;
    private Long workerSnapshotId;
    private String workerSnapshotHash;
    private Long workplaceId;
    private String renderInputHash;
    private String recipientDisplaySnapshotHash;
    private String companyConfigSnapshotHash;
    private String fieldMaskPolicyHash;
    private String renderEngineVersion;
    private String renditionGroupId;
    private Long fullDocumentVersionId;
    private String fullDocumentSha256;
    private Long maskDocumentVersionId;
    private String maskDocumentSha256;
    private Long limitedDocumentVersionId;
    private String limitedDocumentSha256;
    private String deliveryBusinessKey;
    private String generationState;

    @Version
    private Integer version;
}
