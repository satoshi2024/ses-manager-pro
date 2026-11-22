package com.ses.featureenablement.catalog;

import java.util.List;
import java.util.Objects;

/**
 * 署名の組の種類（design §2.6.1。互いに束縛し合う署名の集合）。組の登録・確認・差替えは組の種類に依らない形で行い
 * （FE-A12）、組の種類ごとの整合の検査（NF07 の digest 等）は {@code BundleConsistencyCheck} の hook で足す（FE-C01）。
 *
 * @param code            組の種類（{@code t_feature_sign_off_bundle.bundle_kind}）
 * @param requiredMembers 必ず含める種別
 * @param optionalMembers 含めてもよい種別（例: D-09 が dsar を承認する時の D-06）
 * @param privacy         NF07 の組か（確認者を privacy-admin に限る。段階 C で有効にする）
 */
public record BundleKind(String code, List<String> requiredMembers, List<String> optionalMembers, boolean privacy) {

    public BundleKind {
        Objects.requireNonNull(code, "code");
        requiredMembers = List.copyOf(requiredMembers);
        optionalMembers = optionalMembers == null ? List.of() : List.copyOf(optionalMembers);
    }

    /** 組に属し得る全ての種別。 */
    public List<String> allMembers() {
        java.util.List<String> all = new java.util.ArrayList<>(requiredMembers);
        all.addAll(optionalMembers);
        return List.copyOf(all);
    }
}
