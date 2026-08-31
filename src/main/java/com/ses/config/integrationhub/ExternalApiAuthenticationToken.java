package com.ses.config.integrationhub;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

/** 公開client専用Authentication。GrantedAuthorityへ内部roleを入れない。 */
public final class ExternalApiAuthenticationToken extends AbstractAuthenticationToken {
    private final ExternalApiPrincipal principal;

    public ExternalApiAuthenticationToken(ExternalApiPrincipal principal) {
        super(List.of());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public ExternalApiPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.getName();
    }
}
