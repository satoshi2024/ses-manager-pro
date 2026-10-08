package com.ses.privacy.foundation;

import org.springframework.context.ApplicationContext;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;

/** test source内でのみ生成・保持する合成attestor鍵。 */
final class SyntheticAttestorKeyFixture {
    static final String AUDIENCE = "TEST/SYNTHETIC";
    private final KeyPair keyPair;
    private final ApplicationContext context;

    private SyntheticAttestorKeyFixture(KeyPair keyPair, ApplicationContext context) {
        this.keyPair = keyPair;
        this.context = context;
    }

    static SyntheticAttestorKeyFixture generateForHarness(TemporaryW2bHarness harness) {
        if (harness == null) throw new SecurityException("W2b harnessが必要です");
        ApplicationContext context = harness.contextForKeyFixture();
        W2bTestEnvironment.requireTest(context);
        try {
            return new SyntheticAttestorKeyFixture(KeyPairGenerator.getInstance("Ed25519").generateKeyPair(), context);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519合成鍵を生成できません", e);
        }
    }

    String audience() { return AUDIENCE; }
    java.security.PublicKey publicKey() {
        W2bTestEnvironment.requireTest(context);
        return keyPair.getPublic();
    }

    byte[] sign(byte[] canonicalBytes) {
        W2bTestEnvironment.requireTest(context);
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(keyPair.getPrivate());
            signature.update(canonicalBytes.clone());
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519合成署名を生成できません", e);
        }
    }
}
