package com.ses.service.provider.impl.localcandidate;

import java.net.URI;
import java.time.Duration;

/** 環境変数やアプリケーション設定から値を受け取らない、変更不能なローカルfixture要求。 */
record LocalKeycloakCandidateRequest(URI origin, LocalKeycloakCandidateGuard.FixtureAllowlist fixtureAllowlist,
                                     String realm, String userId,
                                     SyntheticBearerToken token, Duration totalTimeout) {
    LocalKeycloakCandidateRequest {
        if (origin == null || fixtureAllowlist == null || token == null || totalTimeout == null
                || totalTimeout.isNegative() || totalTimeout.isZero()) {
            throw new IllegalArgumentException("invalid local candidate request");
        }
        LocalKeycloakCandidateGuard.validatePathSegment(realm, 128, "realm");
        LocalKeycloakCandidateGuard.validatePathSegment(userId, 256, "user id");
        if (totalTimeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("total timeout exceeds local candidate limit");
        }
    }

    @Override
    public String toString() {
        return "LocalKeycloakCandidateRequest[origin=<loopback>, allowlistSize=" + fixtureAllowlist.peers().size()
                + ", realm=<synthetic>, userId=<synthetic>, token=redacted]";
    }
}
