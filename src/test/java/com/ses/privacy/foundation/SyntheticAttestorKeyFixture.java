package com.ses.privacy.foundation;

import org.springframework.core.env.Environment;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;

/** test source内でのみ生成・保持する合成attestor鍵。 */
final class SyntheticAttestorKeyFixture {
    static final String AUDIENCE = "TEST/SYNTHETIC";
    private final KeyPair keyPair;

    private SyntheticAttestorKeyFixture(KeyPair keyPair) { this.keyPair = keyPair; }

    static SyntheticAttestorKeyFixture generate(org.springframework.core.env.Environment environment) {
        W2bTestEnvironment.requireTest(environment);
        try {
            return new SyntheticAttestorKeyFixture(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519合成鍵を生成できません", e);
        }
    }

    String audience() { return AUDIENCE; }
    java.security.PublicKey publicKey(Environment environment) {
        W2bTestEnvironment.requireTest(environment);
        return keyPair.getPublic();
    }

    byte[] sign(Environment environment, byte[] canonicalBytes) {
        W2bTestEnvironment.requireTest(environment);
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
