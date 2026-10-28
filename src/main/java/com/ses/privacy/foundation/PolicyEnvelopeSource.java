package com.ses.privacy.foundation;

/**
 * テナントおよびポリシー識別子に基づいて現在版エンベロープを検索するソースインターフェース。
 */
interface PolicyEnvelopeSource {
    PolicyLookupResult lookupCurrentPolicy(String tenantId, String policyId, String dataElementId, String action, String providerId);
}
