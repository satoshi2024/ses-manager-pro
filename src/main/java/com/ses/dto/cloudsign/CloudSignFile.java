package com.ses.dto.cloudsign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 固定OpenAPI 0.36.0 の fileModel の最小allow-list DTO。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudSignFile(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("order") Long order,
        @JsonProperty("total_pages") Long totalPages) {
}
