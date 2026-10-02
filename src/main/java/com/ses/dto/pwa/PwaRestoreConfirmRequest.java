package com.ses.dto.pwa;

/** 復元確認入力。proofやscopeをtoStringへ含めない。 */
public final class PwaRestoreConfirmRequest {
    private String proof;
    private String documentNonce;
    private String userScope;

    public String getProof() { return proof; }
    public void setProof(String proof) { this.proof = proof; }
    public String getDocumentNonce() { return documentNonce; }
    public void setDocumentNonce(String documentNonce) { this.documentNonce = documentNonce; }
    public String getUserScope() { return userScope; }
    public void setUserScope(String userScope) { this.userScope = userScope; }

    @Override
    public String toString() {
        return "PwaRestoreConfirmRequest[secret=REDACTED]";
    }
}
