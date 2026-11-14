package com.ses.service.lifecycle.evidence;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * ライフサイクル提出へ保存する認可fence値（NF01細粒度epoch計画 §3.3。旧 {@code LifecycleAuthorizationEpochs(global, tenant,
 * submitterActor)} を置き換える。tenant epochは廃止した）。
 *
 * <p>全体epoch・提出者のactor epoch・案件要員のengineer epoch・提出者の有効主所属組織（マネージャーかつ組織scope有効時）
 * の組織部分木epochを持つ。文書のepochは {@link LifecycleCanonicalization.DocumentVersionSnapshot#evidenceEligibilityEpoch()}
 * に残す。確認者のactor epochはsnapshotと比較せず、確認時のS lock下の現在値を確認行に記録する（§5.5）。</p>
 *
 * @param organizations 組織ID → epoch（ID昇順。組織scopeを使わない提出者では空）
 */
public record LifecycleAuthorizationFence(long global, long submitterActor, long engineer,
                                          SortedMap<Long, Long> organizations) {

    /** fenceのどの成分が変わったか（拒否eventの reason_code の区別に使う。§5.8）。 */
    public enum Component {
        GLOBAL,
        ACTOR,
        ENGINEER,
        ORGANIZATION
    }

    public LifecycleAuthorizationFence {
        if (global < 0 || submitterActor < 0 || engineer < 0) {
            throw new IllegalArgumentException("認可epochは0以上で指定してください");
        }
        Objects.requireNonNull(organizations, "organizations");
        TreeMap<Long, Long> copy = new TreeMap<>();
        for (Map.Entry<Long, Long> entry : organizations.entrySet()) {
            Long organizationId = Objects.requireNonNull(entry.getKey(), "organizationId");
            Long epoch = Objects.requireNonNull(entry.getValue(), "organizationEpoch");
            if (organizationId <= 0 || epoch < 0) {
                throw new IllegalArgumentException("組織IDは正数、組織epochは0以上で指定してください");
            }
            copy.put(organizationId, epoch);
        }
        organizations = Collections.unmodifiableSortedMap(copy);
    }

    /** 組織scopeを持たない提出者のfence。 */
    public static LifecycleAuthorizationFence withoutOrganizations(long global, long submitterActor, long engineer) {
        return new LifecycleAuthorizationFence(global, submitterActor, engineer, new TreeMap<>());
    }

    /** 提出時のfenceが現在値と完全に一致するか（組織は集合と各epochの両方）。 */
    public boolean submitterStillCurrent(LifecycleAuthorizationFence current) {
        return firstChange(current) == null;
    }

    /**
     * 最初に一致しなかった成分（全体 → 利用者 → 要員 → 組織の順）。一致すれば {@code null}。
     * 現在値が無い（{@code null}）場合は全体の変化として扱う（fail-closed）。
     */
    public Component firstChange(LifecycleAuthorizationFence current) {
        if (current == null || global != current.global) {
            return Component.GLOBAL;
        }
        if (submitterActor != current.submitterActor) {
            return Component.ACTOR;
        }
        if (engineer != current.engineer) {
            return Component.ENGINEER;
        }
        if (!organizations.equals(current.organizations)) {
            return Component.ORGANIZATION;
        }
        return null;
    }
}
