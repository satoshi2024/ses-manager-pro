package com.ses.dto.asset;

import com.ses.common.audit.ActorType;
import com.ses.common.audit.ConfirmationSource;
import com.ses.entity.ExternalAccountReference;
import lombok.Data;

import java.time.LocalDateTime;

/** 外部アカウントAPIの公開DTO。確認主体と確認チャネルを別フィールドで返す。 */
@Data
public class ExternalAccountReferenceDto {
    private Long id;
    private Long systemId;
    private String accountIdentifier;
    private String assigneeType;
    private Long assigneeId;
    private String permissionLevel;
    private String status;
    private LocalDateTime provisionedAt;
    private String idempotencyKey;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String lastErrorMessage;
    private LocalDateTime revokeRequestedAt;
    private Long revokeRequestedBy;
    private LocalDateTime revokeConfirmedAt;
    private Long revokeConfirmedBy;
    private Long humanUserId;
    private String actorType;
    private String confirmationSource;
    private String actorTypeDisplay;
    private String confirmationSourceDisplay;
    private String externalSyncStatus;
    private String syncErrorMessage;
    private Integer version;

    public static ExternalAccountReferenceDto from(ExternalAccountReference source) {
        ExternalAccountReferenceDto dto = new ExternalAccountReferenceDto();
        dto.id = source.getId();
        dto.systemId = source.getSystemId();
        dto.accountIdentifier = source.getAccountIdentifier();
        dto.assigneeType = source.getAssigneeType();
        dto.assigneeId = source.getAssigneeId();
        dto.permissionLevel = source.getPermissionLevel();
        dto.status = source.getStatus();
        dto.provisionedAt = source.getProvisionedAt();
        dto.idempotencyKey = source.getIdempotencyKey();
        dto.retryCount = source.getRetryCount();
        dto.nextRetryAt = source.getNextRetryAt();
        dto.lastErrorMessage = source.getLastErrorMessage();
        dto.revokeRequestedAt = source.getRevokeRequestedAt();
        dto.revokeRequestedBy = source.getRevokeRequestedBy();
        dto.revokeConfirmedAt = source.getRevokeConfirmedAt();
        dto.revokeConfirmedBy = source.getRevokeConfirmedBy();
        dto.humanUserId = source.getRevokeConfirmedBy();
        dto.externalSyncStatus = source.getExternalSyncStatus();
        dto.syncErrorMessage = source.getSyncErrorMessage();
        dto.version = source.getVersion();

        String rawSource = source.getConfirmationSource();
        if (rawSource == null || rawSource.isBlank()) rawSource = source.getRevokeConfirmedSource();
        boolean confirmed = "REVOKED".equalsIgnoreCase(source.getStatus()) || source.getRevokeConfirmedAt() != null;
        boolean hasAttribution = (source.getActorType() != null && !source.getActorType().isBlank())
                || (rawSource != null && !rawSource.isBlank());
        if (!confirmed && !hasAttribution) {
            setDisplayValues(dto);
            return dto;
        }
        try {
            if ("MANUAL".equalsIgnoreCase(rawSource)) rawSource = ConfirmationSource.MANUAL_API.name();
            if ("SYSTEM".equalsIgnoreCase(rawSource)) rawSource = ConfirmationSource.SCHEDULER_POLL.name();
            ConfirmationSource parsed = ConfirmationSource.valueOf(rawSource);
            dto.confirmationSource = parsed.name();
            dto.actorType = source.getActorType();
            if (dto.actorType == null || dto.actorType.isBlank()) {
                dto.actorType = switch (parsed) {
                    case MANUAL_API -> dto.humanUserId != null ? ActorType.HUMAN.name() : ActorType.LEGACY_UNRESOLVED.name();
                    case SCHEDULER_POLL -> ActorType.SYSTEM.name();
                    case PROVIDER_SYNC, PROVIDER_CALLBACK -> ActorType.PROVIDER.name();
                    case LEGACY_UNRESOLVED -> ActorType.LEGACY_UNRESOLVED.name();
                };
            }
            ActorType actor = ActorType.valueOf(dto.actorType);
            boolean validPair = switch (actor) {
                case HUMAN -> parsed == ConfirmationSource.MANUAL_API && dto.humanUserId != null && dto.humanUserId > 0;
                case SYSTEM -> parsed == ConfirmationSource.SCHEDULER_POLL && dto.humanUserId == null;
                case PROVIDER -> (parsed == ConfirmationSource.PROVIDER_SYNC
                        || parsed == ConfirmationSource.PROVIDER_CALLBACK) && dto.humanUserId == null;
                case LEGACY_UNRESOLVED -> parsed == ConfirmationSource.LEGACY_UNRESOLVED && dto.humanUserId == null;
            };
            if (!validPair) {
                throw new IllegalArgumentException("invalid actor/source pair");
            }
        } catch (IllegalArgumentException | NullPointerException ex) {
            if (confirmed || hasAttribution) {
                dto.actorType = ActorType.LEGACY_UNRESOLVED.name();
                dto.confirmationSource = ConfirmationSource.LEGACY_UNRESOLVED.name();
            }
        }
        if (confirmed && dto.actorType == null) dto.actorType = ActorType.LEGACY_UNRESOLVED.name();
        if (confirmed && dto.confirmationSource == null) dto.confirmationSource = ConfirmationSource.LEGACY_UNRESOLVED.name();
        setDisplayValues(dto);
        return dto;
    }

    private static void setDisplayValues(ExternalAccountReferenceDto dto) {
        dto.actorTypeDisplay = dto.actorType == null ? "未確認" : dto.actorType;
        dto.confirmationSourceDisplay = dto.confirmationSource == null ? "未確認" : dto.confirmationSource;
    }
}
