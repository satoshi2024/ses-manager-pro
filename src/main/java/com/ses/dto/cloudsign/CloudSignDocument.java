package com.ses.dto.cloudsign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 固定OpenAPI 0.36.0 の documentModel の最小allow-list DTO。
 * 未知のresponse fieldは許容（ignoreUnknown）し、必須field(id/status)欠落は client 側で schema error にする。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudSignDocument(
        @JsonProperty("id") String id,
        @JsonProperty("title") String title,
        @JsonProperty("status") Integer status,
        @JsonProperty("sent_at") String sentAt,
        @JsonProperty("last_processed_at") String lastProcessedAt,
        @JsonProperty("decline_comment") String declineComment,
        @JsonProperty("files") List<CloudSignFile> files,
        @JsonProperty("participants") List<CloudSignParticipant> participants) {

    public boolean hasFileId(String fileId) {
        if (fileId == null || files == null) {
            return false;
        }
        return files.stream().anyMatch(f -> fileId.equals(f.id()));
    }

    public boolean hasParticipantId(String participantId) {
        if (participantId == null || participants == null) {
            return false;
        }
        return participants.stream().anyMatch(p -> participantId.equals(p.id()));
    }
}
