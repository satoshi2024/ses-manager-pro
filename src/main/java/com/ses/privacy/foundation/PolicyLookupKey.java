package com.ses.privacy.foundation;

/**
 * production policy を一意に引く key（policy-source-catalog-wiring-plan.md §2）。
 *
 * <p>tenant と actor は認証済み caller から取り、照会要求の値をそのまま信頼しない。
 * {@code legalEntityId = 0} は tenant-wide を表す（P-LE 未決のため第1期は tenant-wide だけを扱う）。
 */
public record PolicyLookupKey(String moduleCode, String tenantId, String actorId, long legalEntityId,
                              String dataElementId, String action, String providerId) {

    /** tenant-wide（法人を特定しない）policy の法人ID。m_retention_policy の current_key と同じ表現。 */
    public static final long TENANT_WIDE = 0L;

    public PolicyLookupKey {
        moduleCode = required(moduleCode, "moduleCode");
        tenantId = required(tenantId, "tenantId");
        actorId = required(actorId, "actorId");
        dataElementId = required(dataElementId, "dataElementId");
        action = required(action, "action");
        providerId = required(providerId, "providerId");
        if (legalEntityId < 0L) {
            throw new IllegalArgumentException("legalEntityIdが不正です");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "は必須です");
        }
        return value;
    }
}
