package com.ses.service.integrationhub;

/** 外部API監査へ保存可能なbounded metadataだけを表すrecord。 */
public record ExternalApiAuditRecord(
        String preAuthPrincipal,
        String postAuthPrincipal,
        String clientId,
        Integer credentialVersion,
        String keyId,
        String correlationId,
        String method,
        String routeTemplate,
        String authenticationDecision,
        String scopeDecision,
        String dataScopeDecision,
        String commandDecision,
        String rateDecision,
        int status,
        String resultCode,
        boolean successFlag) {
}
