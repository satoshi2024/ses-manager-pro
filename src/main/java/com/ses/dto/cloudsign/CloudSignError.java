package com.ses.dto.cloudsign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 固定OpenAPI 0.36.0 の errorModel。PIIを含むmessageは保存・ログしない。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudSignError(
        @JsonProperty("error") String error,
        @JsonProperty("message") String message) {
}
