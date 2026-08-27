package com.ses.service.pwa;

import com.fasterxml.jackson.databind.JsonNode;

/** オフラインqueueから再送される最小command。baseVersionはheaderからcontrollerが注入する。 */
public record PwaMutationCommand(String screen, String month, Integer baseVersion, JsonNode payload) {
    public String payloadHash(PwaCanonicalizer canonicalizer) {
        return canonicalizer.hash(screen, month, baseVersion, payload);
    }
}
