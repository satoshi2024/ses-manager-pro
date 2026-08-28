package com.ses.service.pwa;

import com.fasterxml.jackson.databind.JsonNode;

/** オフラインqueueから再送される最小command。baseVersionはheaderからcontrollerが注入する。 */
public record PwaMutationCommand(String operation, String screen, String month, Integer baseVersion, JsonNode payload) {
    /** 既存のサービス単体テスト・内部呼出しとの互換用。HTTP経路ではoperationを必ず設定する。 */
    public PwaMutationCommand(String screen, String month, Integer baseVersion, JsonNode payload) {
        this(null, screen, month, baseVersion, payload);
    }

    public String payloadHash(PwaCanonicalizer canonicalizer) {
        return operation == null
                ? legacyPayloadHash(canonicalizer)
                : canonicalizer.hash(operation, screen, month, baseVersion, payload);
    }

    /** V112以前の保存済みqueue/ledgerをV113以降で再送するための旧hash。 */
    public String legacyPayloadHash(PwaCanonicalizer canonicalizer) {
        return canonicalizer.hash(screen, month, baseVersion, payload);
    }
}
