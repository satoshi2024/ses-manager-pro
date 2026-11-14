package com.ses.privacy.foundation;

import java.util.Optional;

/**
 * production policy catalog の読み取り専用 port（policy-source-catalog-wiring-plan.md §2）。
 *
 * <p>(module, tenant, 法人, data element, action, provider) に完全一致する登録済み entry を一意に返す。
 * 未登録・重複・不整合は {@link Optional#empty()}（呼び出し側が {@code POLICY_UNKNOWN} に閉じる）。
 * 実装は書き込みを行わず、構築時に永続層へ触れない。
 */
public interface ProductionPolicyCatalogPort {
    Optional<ProductionCatalogBinding> resolve(PolicyLookupKey key);
}
