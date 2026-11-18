package com.ses.privacy.foundation;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Set;

/**
 * hold 解除 authority の trust anchor（NF07-REL。D-03 の署名鍵と D-05 の権限者名簿から登録する）。
 *
 * <p>{@code principalRef} は authority の主体で、要求者・発行者・承認者・hold の開始者の分離（SoD）の比較に使う。形は
 * {@link #PRINCIPAL}（{@code sys_user:<sys_user.id>}、先頭の 0・大文字・他の名前空間は不可）に限り、合わなければ登録を拒否する
 * （表記の揺れで同じ人を別人と見なし SoD が素通りすることを防ぐ。要求者・開始者も同じ正規形の値で表し、比較は正規形の
 * 文字列の完全一致だけで行う）。公開鍵は Ed25519 に限る（{@link Ed25519PublicKeys}。他の算法は登録を拒否する）。
 * {@code tenants}・{@code roles}・{@code targetTypes}（{@code SUBJECT} / {@code DATA_ELEMENT}）は
 * 解除権限の範囲で、空欄・不足は拒否として扱う。
 */
public record HoldReleaseTrustAnchor(String authorityId, String keyId, PublicKey publicKey, String principalRef,
                                     Set<String> tenants, Set<HoldReleaseAuthorityClaim.Role> roles,
                                     Set<String> targetTypes, Instant validFrom, Instant validUntil) {

    /** 主体の正規形（{@code sys_user:} と、先頭が 0 でない10進の sys_user.id）。 */
    public static final java.util.regex.Pattern PRINCIPAL = java.util.regex.Pattern.compile("sys_user:[1-9][0-9]{0,18}");

    public HoldReleaseTrustAnchor {
        if (authorityId == null || authorityId.isBlank() || keyId == null || keyId.isBlank() || publicKey == null) {
            throw new IllegalArgumentException("解除 authority の識別子・鍵は必須です");
        }
        if (!Ed25519PublicKeys.isEd25519(publicKey)) {
            // 解除の署名は Ed25519 だけを検証する。他の算法の鍵は登録の時点で拒否する（REL review P3-8）
            throw new IllegalArgumentException("解除 authority の鍵は Ed25519 の公開鍵でなければなりません");
        }
        if (principalRef == null || !PRINCIPAL.matcher(principalRef).matches()) {
            throw new IllegalArgumentException("解除 authority の主体は sys_user:<id> の正規形でなければなりません");
        }
        if (tenants == null || roles == null || targetTypes == null) {
            throw new IllegalArgumentException("解除 authority の範囲は必須です");
        }
        tenants = Set.copyOf(tenants);
        roles = Set.copyOf(roles);
        targetTypes = Set.copyOf(targetTypes);
        if (validFrom == null || validUntil == null || !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("解除 authority の有効期間が不正です");
        }
    }

    /** registry の key（{@code authorityId + ":" + keyId}）。 */
    public String anchorId() {
        return authorityId + ":" + keyId;
    }
}
