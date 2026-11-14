package com.ses.privacy.foundation;

/**
 * catalog が解決した、key に対して期待する policy の binding（policy-source-catalog-wiring-plan.md §2）。
 *
 * <p>{@code actorBinding} は署名済み policy の適用対象。現行の署名 schema v1 は actor・法人の binding を持たないため、
 * 第1期は {@link #TENANT_WIDE_ACTOR_BINDING}（tenant 内の全 privacy 照会者に共通）だけを受け付け、
 * それ以外は {@code ProductionPolicyReadonlyQueryService} が {@code POLICY_DRIFT} に閉じる。
 */
public record ProductionCatalogBinding(String policyId, String scope, String actorBinding,
                                       String dataElementId, String action, String providerId,
                                       long legalEntityId) {

    /** tenant-wide の actor binding。P-ACT の署名後に actor 別 binding を追加する。 */
    public static final String TENANT_WIDE_ACTOR_BINDING = "TENANT_WIDE";
}
