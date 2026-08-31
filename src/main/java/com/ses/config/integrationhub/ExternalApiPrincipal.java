package com.ses.config.integrationhub;

import java.security.Principal;

/** 内部role/portal userへ変換しない、公開client専用principal。 */
public record ExternalApiPrincipal(
        String clientId,
        Long clientDatabaseId,
        String tenantId,
        Long legalEntityId,
        String dataScopeJson,
        int credentialVersion,
        String keyId,
        String clientTier) implements Principal {
    @Override
    public String getName() {
        return clientId;
    }
}
