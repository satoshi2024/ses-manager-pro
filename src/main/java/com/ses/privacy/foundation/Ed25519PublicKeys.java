package com.ses.privacy.foundation;

import java.security.PublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.NamedParameterSpec;

/**
 * trust anchor に登録する公開鍵の算法の検査（NF07-REL review P3-8）。policy envelope と hold 解除の署名は Ed25519 だけを使うため、
 * 登録の時点で Ed25519 以外（RSA・EC・Ed448 等）の鍵を拒否する（以前は実行時に {@code SIGNATURE_INVALID} になるだけだった）。
 */
public final class Ed25519PublicKeys {

    private Ed25519PublicKeys() {
    }

    /** {@code key} が Ed25519 の公開鍵か（{@link EdECPublicKey} で、parameter が {@link NamedParameterSpec#ED25519}）。 */
    public static boolean isEd25519(PublicKey key) {
        if (!(key instanceof EdECPublicKey edec)) {
            return false;
        }
        NamedParameterSpec params = edec.getParams();
        return params != null && NamedParameterSpec.ED25519.getName().equalsIgnoreCase(params.getName());
    }
}
