package com.ses.privacy.foundation;

/**
 * 失効状態の照会（{@link RevocationStatusProvider#statusOf}）の名前空間の規則（NF07-TRUST、RB-NF07-TRUST-KMS review P2-1）。
 *
 * <p>照会は鍵（trust anchor の {@code authorityId:keyId}）と policy envelope の {@code revocationReference} を同じ文字列の
 * 名前空間で受け取る。anchor ID は authority ID・key ID が {@code :} を含まないため必ず {@code :} を1つ含む。そこで envelope の
 * 参照は {@code :} を含まないものだけを envelope の参照として扱い、2つの名前空間を文法で分ける。{@code :} を含む envelope の
 * 参照は鍵の ID と区別できない（anchor ID と同じ値の参照が、envelope を登録していないのに鍵の状態で ACTIVE になる）ため、
 * 本番の verifier は照会せずに失効状態を UNKNOWN とし、trust 文書もその参照を受け付けない。状態を持たない。
 */
public final class RevocationReferences {

    /** 鍵の ID の区切り（envelope の参照には含めない）。 */
    public static final char KEY_SEPARATOR = ':';

    private RevocationReferences() {
    }

    /** envelope の参照として扱える値か（空でなく、{@value #KEY_SEPARATOR} を含まない）。 */
    public static boolean isEnvelopeReference(String reference) {
        return reference != null && !reference.isBlank() && reference.indexOf(KEY_SEPARATOR) < 0;
    }

    /** 鍵の ID の形か（{@value #KEY_SEPARATOR} を含む）。 */
    public static boolean isKeyReference(String reference) {
        return reference != null && reference.indexOf(KEY_SEPARATOR) >= 0;
    }
}
