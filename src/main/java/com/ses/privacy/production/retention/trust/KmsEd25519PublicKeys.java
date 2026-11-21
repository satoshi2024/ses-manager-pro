package com.ses.privacy.production.retention.trust;

import com.ses.privacy.foundation.Ed25519PublicKeys;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * AWS KMS の {@code GetPublicKey} が返す公開鍵（DER の SubjectPublicKeyInfo、CLI の出力は base64）を Ed25519 の公開鍵として
 * 厳格に読む（NF07-TRUST。D-03 = AWS KMS 非対称署名）。
 *
 * <p>受け付けるのは、key spec {@code ECC_NIST_EDWARDS25519} の鍵の SubjectPublicKeyInfo（RFC 8410。algorithm OID
 * {@code 1.3.101.112}、parameter 無し、公開鍵 32 bytes、全体 44 bytes）だけ。RSA・ECC（NIST P 曲線・secp256k1）・Ed448・
 * 末尾の余分な bytes・PEM の文字列・base64url・改行や空白を含む base64・正規形でない base64 は拒否する。読めた鍵を JDK で
 * 再 encoding した bytes が入力と1 byte でも違えば拒否する。状態を持たない。
 */
final class KmsEd25519PublicKeys {

    /** Ed25519 の SubjectPublicKeyInfo の先頭（SEQUENCE、AlgorithmIdentifier {1.3.101.112}、BIT STRING 33 bytes）。 */
    static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    /** Ed25519 の SubjectPublicKeyInfo の長さ（先頭 12 bytes と公開鍵 32 bytes）。 */
    static final int SPKI_LENGTH = 44;

    private KmsEd25519PublicKeys() {
    }

    /**
     * base64（標準の alphabet、padding 付きの正規形）の SubjectPublicKeyInfo を Ed25519 の公開鍵にする。
     *
     * @throws IllegalArgumentException Ed25519 の SubjectPublicKeyInfo として読めない（値は message に含めない）
     */
    static PublicKey parse(String base64) {
        if (base64 == null || base64.isEmpty()) {
            throw new IllegalArgumentException("公開鍵がありません");
        }
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("公開鍵が base64 ではありません");
        }
        if (!Base64.getEncoder().encodeToString(der).equals(base64)) {
            throw new IllegalArgumentException("公開鍵の base64 が正規形ではありません");
        }
        if (der.length != SPKI_LENGTH || !Arrays.equals(der, 0, SPKI_PREFIX.length, SPKI_PREFIX, 0, SPKI_PREFIX.length)) {
            throw new IllegalArgumentException("公開鍵が Ed25519 の SubjectPublicKeyInfo ではありません");
        }
        PublicKey key;
        try {
            key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalArgumentException("公開鍵を Ed25519 として読めません");
        }
        if (!Ed25519PublicKeys.isEd25519(key) || !Arrays.equals(key.getEncoded(), der)) {
            throw new IllegalArgumentException("公開鍵が Ed25519 の正規形ではありません");
        }
        return key;
    }
}
