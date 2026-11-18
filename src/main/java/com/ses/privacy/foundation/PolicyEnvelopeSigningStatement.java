package com.ses.privacy.foundation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * policy envelope の digest 声明（NF07-TRUST。D-03 = AWS KMS 非対称署名）。
 *
 * <p>AWS KMS の Ed25519（key spec {@code ECC_NIST_EDWARDS25519}、署名算法 {@code ED25519_SHA_512}、{@code MessageType=RAW}）は
 * 署名する message を 4096 bytes までしか受け付けない。policy envelope の canonical bytes（{@link ApprovalEnvelopeCanonicalizer}
 * の v1 形式）は、目的・trigger・保持規則等の文字列の長さで決まり、登録の上限は 64KB で 4096 bytes を超え得る。そこで本番の
 * verifier（説明照会・登録・有効化・NF08 の内部判定）は、canonical bytes の SHA-256 を持つ固定長（100 bytes）の本声明への
 * 純粋な Ed25519（RFC 8032）署名<b>だけ</b>を受け付ける。canonical bytes そのものへの署名と、prehash 版（Ed25519ph、KMS の
 * {@code ED25519_PH_SHA_512}）の署名は拒否する（RB-NF07-TRUST-KMS review P3-2: 4096 bytes 以下なら直接の署名が KMS で作れて
 * しまい、超えると作れない運用の罠を避け、受け付ける message を1つにする）。
 *
 * <p>正規形（{@code HoldReleaseBinding} と同じ {@code <UTF-8 の byte 長>:<値>} の field を区切り無しで連結）:
 * {@value #DOMAIN} の field、canonical bytes の SHA-256（小文字 hex 64桁）の field。例:
 * {@code 30:NF07-POLICY-ENVELOPE-DIGEST-V164:<hex>}。digest は登録の一覧・詳細の {@code envelopeDigest}（D-02 の決定書の digest）と
 * 同じ値。
 *
 * <p>canonical bytes そのものへの署名を検証するのは、本番ではない合成の {@link ApprovalEnvelopeVerifier}（第1期の合成層と試験）
 * だけ。その形と本声明は先頭で区別できる（canonical bytes は4 byte の長さ 20 と {@code SES-PRIVACY-APPROVAL} で始まり、声明は
 * ASCII の {@code 30:NF07-POLICY-ENVELOPE-DIGEST-V1} で始まる。{@code PolicyEnvelopeSigningStatementTest} が先頭の bytes を固定する）
 * ため、一方への署名が他方の正しい署名として読まれることはない。声明は digest を通して canonical bytes の全体（tenant・
 * audience・authority・key・時刻・保持規則等）に束縛される。状態を持たない。
 */
public final class PolicyEnvelopeSigningStatement {

    /** 声明の domain。 */
    public static final String DOMAIN = "NF07-POLICY-ENVELOPE-DIGEST-V1";

    /** 声明の長さ（byte）。domain の field（3 + 30）と digest の field（3 + 64）。 */
    public static final int LENGTH = 100;

    /** AWS KMS の {@code MessageType=RAW} の message の上限（byte）。 */
    public static final int KMS_RAW_MESSAGE_LIMIT = 4096;

    private PolicyEnvelopeSigningStatement() {
    }

    /**
     * canonical bytes の digest 声明（署名の対象）を組み立てる。
     *
     * @param canonical envelope の canonical bytes（空でない）
     * @throws IllegalArgumentException canonical bytes が無い
     */
    public static byte[] of(byte[] canonical) {
        if (canonical == null || canonical.length == 0) {
            throw new IllegalArgumentException("canonical bytes が必要です");
        }
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDKにSHA-256がありません", impossible);
        }
        StringBuilder statement = new StringBuilder(LENGTH);
        field(statement, DOMAIN);
        field(statement, digest);
        return statement.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static void field(StringBuilder target, String value) {
        target.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
    }
}
