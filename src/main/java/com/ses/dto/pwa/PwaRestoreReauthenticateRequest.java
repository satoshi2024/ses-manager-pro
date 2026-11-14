package com.ses.dto.pwa;

/** 再認証入力。秘密値を文字列化してログへ出さない。 */
public final class PwaRestoreReauthenticateRequest {
    private String password;
    private String documentNonce;
    private String userScope;

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getDocumentNonce() { return documentNonce; }
    public void setDocumentNonce(String documentNonce) { this.documentNonce = documentNonce; }
    public String getUserScope() { return userScope; }
    public void setUserScope(String userScope) { this.userScope = userScope; }

    @Override
    public String toString() {
        return "PwaRestoreReauthenticateRequest[secret=REDACTED]";
    }
}
