package com.ses.service.provider.impl.localcandidate;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** application providerへ接続しない、ローカル限定Keycloak候補。 */
final class LocalKeycloakExternalAccountProviderCandidate {
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(4096).maxNameLength(128).maxNumberLength(64).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final ObjectMapper JSON = new ObjectMapper(JSON_FACTORY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final LocalKeycloakCandidateRequest request;
    private final LocalKeycloakCandidateGuard.Target target;
    private final BoundedLoopbackHttp transport = new BoundedLoopbackHttp();

    LocalKeycloakExternalAccountProviderCandidate(LocalKeycloakCandidateRequest request) {
        this.request = request;
        this.target = LocalKeycloakCandidateGuard.target(request.origin(), request.fixtureAllowlist());
    }

    CandidateRun runBoundedSequence() {
        long deadline = BoundedLoopbackHttp.deadline(request.totalTimeout());
        LocalKeycloakCandidateResult disable = perform(LocalKeycloakCandidateResult.Operation.DISABLE,
                "PUT", userPath(), "{\"enabled\":false}".getBytes(StandardCharsets.UTF_8), deadline);
        LocalKeycloakCandidateResult logout = perform(LocalKeycloakCandidateResult.Operation.LOGOUT,
                "POST", userPath() + "/logout", new byte[0], deadline);
        LocalKeycloakCandidateResult user = perform(LocalKeycloakCandidateResult.Operation.READ_USER,
                "GET", userPath(), new byte[0], deadline);
        LocalKeycloakCandidateResult sessions = perform(LocalKeycloakCandidateResult.Operation.READ_SESSIONS,
                "GET", userPath() + "/sessions", new byte[0], deadline);
        return new CandidateRun(disable, logout, user, sessions);
    }

    private LocalKeycloakCandidateResult perform(LocalKeycloakCandidateResult.Operation operation,
                                                  String method, String path, byte[] body, long deadline) {
        long started = System.nanoTime();
        if (deadline - started <= 0) return outcome(operation, LocalKeycloakCandidateResult.State.NOT_STARTED,
                "SERIES_DEADLINE_EXHAUSTED", null, null, null, 0, started);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", request.token().authorizationValue());
        BoundedLoopbackHttp.WireRequest wire = new BoundedLoopbackHttp.WireRequest(method, path, headers, body);
        BoundedLoopbackHttp.TransportResult response = transport.execute(target, wire, deadline);
        if (response.response() == null) return outcome(operation, response.state(), response.safeCode(),
                null, null, null, response.responseBytes(), started);

        BoundedHttp1ResponseParser.Parsed parsed = response.response();
        if (deadline - System.nanoTime() <= 0) {
            return outcome(operation, LocalKeycloakCandidateResult.State.UNKNOWN,
                    "TOTAL_DEADLINE_EXCEEDED", parsed.status(), null, null, parsed.responseBytes(), started);
        }
        if ((operation == LocalKeycloakCandidateResult.Operation.DISABLE
                || operation == LocalKeycloakCandidateResult.Operation.LOGOUT)
                && parsed.status() == 204 && parsed.body().length == 0) {
            return outcome(operation, LocalKeycloakCandidateResult.State.ACKNOWLEDGED,
                    "EXPECTED_204_ACK", parsed.status(), null, null, parsed.responseBytes(), started);
        }
        if ((operation == LocalKeycloakCandidateResult.Operation.READ_USER
                || operation == LocalKeycloakCandidateResult.Operation.READ_SESSIONS)
                && parsed.status() == 200 && isJson(parsed.header("content-type"))) {
            try {
                String jsonText = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(parsed.body())).toString();
                JsonNode root = JSON.readTree(jsonText);
                if (operation == LocalKeycloakCandidateResult.Operation.READ_USER) {
                    JsonNode enabled = root == null ? null : root.get("enabled");
                    if (root == null || !root.isObject() || enabled == null || !enabled.isBoolean()) {
                        return outcome(operation, LocalKeycloakCandidateResult.State.UNKNOWN,
                                "USER_REPRESENTATION_UNCLASSIFIED", parsed.status(), null, null,
                                parsed.responseBytes(), started);
                    }
                    return outcome(operation, LocalKeycloakCandidateResult.State.ACKNOWLEDGED,
                            "USER_STATE_OBSERVED", parsed.status(), enabled.booleanValue(), null,
                            parsed.responseBytes(), started);
                }
                if (root == null || !root.isArray()) {
                    return outcome(operation, LocalKeycloakCandidateResult.State.UNKNOWN,
                            "SESSION_REPRESENTATION_UNCLASSIFIED", parsed.status(), null, null,
                            parsed.responseBytes(), started);
                }
                return outcome(operation, LocalKeycloakCandidateResult.State.ACKNOWLEDGED,
                        "ACTIVE_SESSIONS_OBSERVED", parsed.status(), null, root.size(),
                        parsed.responseBytes(), started);
            } catch (Exception invalidJson) {
                return outcome(operation, LocalKeycloakCandidateResult.State.UNKNOWN, "JSON_UNCLASSIFIED",
                        parsed.status(), null, null, parsed.responseBytes(), started);
            }
        }
        return outcome(operation, LocalKeycloakCandidateResult.State.UNKNOWN,
                "UNEXPECTED_HTTP_RESPONSE", parsed.status(), null, null, parsed.responseBytes(), started);
    }

    private String userPath() {
        return "/admin/realms/" + LocalKeycloakCandidateGuard.encodePathSegment(request.realm())
                + "/users/" + LocalKeycloakCandidateGuard.encodePathSegment(request.userId());
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) return false;
        String[] parts = contentType.split(";", -1);
        if (!"application/json".equalsIgnoreCase(parts[0].trim())) return false;
        for (int i = 1; i < parts.length; i++) {
            String parameter = parts[i].trim();
            if (!parameter.isEmpty() && !"charset=utf-8".equalsIgnoreCase(parameter)) return false;
        }
        return true;
    }

    private static LocalKeycloakCandidateResult outcome(LocalKeycloakCandidateResult.Operation operation,
                                                          LocalKeycloakCandidateResult.State state, String code,
                                                          Integer status, Boolean enabled, Integer sessionCount,
                                                          int responseBytes, long started) {
        return new LocalKeycloakCandidateResult(operation, state, code, status, enabled, sessionCount,
                responseBytes, Duration.ofNanos(Math.max(0, System.nanoTime() - started)));
    }

    static String safeCorrelationRef(String syntheticUserId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(syntheticUserId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    record CandidateRun(LocalKeycloakCandidateResult disable, LocalKeycloakCandidateResult logout,
                        LocalKeycloakCandidateResult userObservation,
                        LocalKeycloakCandidateResult sessionObservation) {
        List<LocalKeycloakCandidateResult> operations() {
            return List.of(disable, logout, userObservation, sessionObservation);
        }

        @Override public String toString() {
            return "CandidateRun[operations=" + operations() + ", scope=LOCAL_SYNTHETIC_ONLY]";
        }
    }
}
