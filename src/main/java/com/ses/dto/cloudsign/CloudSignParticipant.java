package com.ses.dto.cloudsign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 固定OpenAPI 0.36.0 の participantModel の最小allow-list DTO。
 * access_code等の機微値は保持しない（参照判定は id/email/name/order のみ）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudSignParticipant(
        @JsonProperty("id") String id,
        @JsonProperty("email") String email,
        @JsonProperty("name") String name,
        @JsonProperty("organization") String organization,
        @JsonProperty("order") Long order,
        @JsonProperty("status") Integer status) {
}
