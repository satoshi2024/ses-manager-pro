package com.ses.dto.cloudsign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 固定OpenAPI 0.36.0 の accessTokenModel。token値はメモリのみで永続化・ログ出力しない。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudSignAccessToken(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") Long expiresIn,
        @JsonProperty("token_type") String tokenType) {
}
