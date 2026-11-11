package com.ses.importcenter.metadata;

/** service内部で再解決した現在principalの最小snapshot。requestから生成しない。 */
public record ImportAuthorizedContext(String tenantId, long actorUserId, long legalEntityId) {
    public ImportAuthorizedContext {
        if (tenantId == null || tenantId.isBlank() || actorUserId < 1 || legalEntityId < 1) {
            throw new IllegalArgumentException("取込認可contextが不足しています");
        }
    }
}
