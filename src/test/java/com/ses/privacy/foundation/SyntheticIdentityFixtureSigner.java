package com.ses.privacy.foundation;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;

/** W3a用生成署名。test source以外に鍵を置かない。 */
final class SyntheticIdentityFixtureSigner {
    static final String KEY_ID = "w3a-ephemeral-key";
    final KeyPair keyPair;
    SyntheticIdentityFixtureSigner() {
        try { keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    SyntheticIdentityEnvelope sign(String tenant, String requester, String subject, String request, String revision,
                                   Instant expires, String audience) {
        Instant issued = expires.minusSeconds(3600);
        return signCustom(tenant,requester,subject,request,revision,KEY_ID,audience,issued,expires);
    }
    SyntheticIdentityEnvelope signCustom(String tenant,String requester,String subject,String request,String revision,
                                         String keyId,String audience,Instant issued,Instant expires) {
        SyntheticIdentityEnvelope unsigned = new SyntheticIdentityEnvelope(tenant, requester, subject, request, revision,
                keyId, audience, issued, expires, new byte[0]);
        try {
            Signature s=Signature.getInstance("Ed25519"); s.initSign(keyPair.getPrivate()); s.update(unsigned.canonicalBytes());
            return new SyntheticIdentityEnvelope(tenant,requester,subject,request,revision,keyId,audience,issued,expires,s.sign());
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
}
