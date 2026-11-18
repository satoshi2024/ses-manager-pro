package com.ses.service;

import com.ses.entity.IdentityProvider;

import java.util.List;

/** OIDCログインが照合する {@code m_identity_provider} の登録・参照（管理者専用）。 */
public interface IdentityProviderRegistrationService {

    /** 設定tenantのIdentity Provider一覧。 */
    List<IdentityProvider> list();

    /**
     * 設定済みのOIDC provider（{@code app.security.oidc.*} のtenant・issuer・client ID）を登録する。
     * 同じtenant・issuerの有効な行が既にあればそれを返す（冪等）。client secretはDBへ保存しない。
     */
    IdentityProvider registerConfiguredOidcProvider();
}
