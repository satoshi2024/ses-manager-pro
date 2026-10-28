package com.ses.service.provider.impl.localcandidate;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LocalKeycloakExternalAccountProviderCandidateTest {
    @Test
    void boundedSequenceKeepsDisableLogoutAndQueriesSeparate() throws Exception {
        try (LocalKeycloakLoopbackFixture fixture = new LocalKeycloakLoopbackFixture()) {
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.noContent());
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.noContent());
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.json(200, "{\"enabled\":false}"));
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.json(200, "[]"));

            var run = candidate(fixture, "subject-1", Duration.ofSeconds(4)).runBoundedSequence();

            assertEquals(List.of(LocalKeycloakCandidateResult.State.ACKNOWLEDGED,
                            LocalKeycloakCandidateResult.State.ACKNOWLEDGED,
                            LocalKeycloakCandidateResult.State.ACKNOWLEDGED,
                            LocalKeycloakCandidateResult.State.ACKNOWLEDGED),
                    run.operations().stream().map(LocalKeycloakCandidateResult::state).toList());
            assertEquals(Boolean.FALSE, run.userObservation().enabled());
            assertEquals(0, run.sessionObservation().activeSessionCount());
            assertEquals(List.of("PUT", "POST", "GET", "GET"),
                    fixture.requests().stream().map(LocalKeycloakLoopbackFixture.Request::method).toList());
            assertTrue(fixture.requests().get(0).path().endsWith("/users/subject-1"));
            assertTrue(fixture.requests().get(1).path().endsWith("/users/subject-1/logout"));
            assertEquals("{\"enabled\":false}", new String(fixture.requests().get(0).body(), StandardCharsets.UTF_8));
            assertEquals("Bearer synthetic-dummy-token", fixture.requests().get(0).headers().get("authorization"));
            assertFalse(run.toString().contains("synthetic-dummy-token"));
            assertFalse(run.toString().contains("subject-1"));
        }
    }

    @Test
    void pathSegmentsAreEncodedAndNeverAcceptSeparatorsOrMalformedUnicode() throws Exception {
        try (LocalKeycloakLoopbackFixture fixture = new LocalKeycloakLoopbackFixture()) {
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.noContent());
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.noContent());
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.json(200, "{\"enabled\":false}"));
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.json(200, "[]"));
            candidate(fixture, "subject-α", Duration.ofSeconds(4)).runBoundedSequence();
            assertTrue(fixture.requests().get(0).path().contains("subject-%CE%B1"));
            assertThrows(IllegalArgumentException.class, () -> LocalKeycloakCandidateGuard.validatePathSegment("../x", 256, "id"));
            assertThrows(IllegalArgumentException.class, () -> LocalKeycloakCandidateGuard.validatePathSegment("x%2Fy", 256, "id"));
            assertThrows(IllegalArgumentException.class, () -> LocalKeycloakCandidateGuard.validatePathSegment("x\\y", 256, "id"));
            assertThrows(IllegalArgumentException.class, () -> LocalKeycloakCandidateGuard.validatePathSegment("\uD800", 256, "id"));
        }
    }

    @Test
    void invalidEndpointAndTokenAreRejectedBeforeAnyRequest() throws Exception {
        try (LocalKeycloakLoopbackFixture fixture = new LocalKeycloakLoopbackFixture()) {
            var allowlist = fixtureAllowlist(fixture);
            assertThrows(IllegalArgumentException.class, () -> LocalKeycloakCandidateGuard.target(URI.create("http://localhost:" + fixture.port()), allowlist));
            assertThrows(IllegalArgumentException.class, () -> LocalKeycloakCandidateGuard.target(URI.create("http://127.0.0.1"), allowlist));
            assertThrows(IllegalArgumentException.class, () -> LocalKeycloakCandidateGuard.target(
                    URI.create("http://127.0.0.1:" + (fixture.port() + 1)), allowlist));
            assertThrows(IllegalArgumentException.class, () -> new SyntheticBearerToken("synthetic-token\r\nX: y"));
            assertThrows(IllegalArgumentException.class, () -> new SyntheticBearerToken("real-looking-token"));
            assertTrue(fixture.requests().isEmpty());
        }
    }

    @Test
    void actualPeerMustMatchTheExactRequestTarget() throws Exception {
        try (LocalKeycloakLoopbackFixture fixture = new LocalKeycloakLoopbackFixture()) {
            var origin = URI.create("http://127.0.0.1:" + fixture.port());
            var allowlist = fixtureAllowlist(fixture);
            var target = LocalKeycloakCandidateGuard.target(origin, allowlist);
            assertTrue(target.matches(target.peer()));
            assertFalse(target.matches(new InetSocketAddress(target.peer().getAddress(), target.peer().getPort() + 1)));
            InetAddress otherLoopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 2});
            assertFalse(target.matches(new InetSocketAddress(otherLoopback, target.peer().getPort())));
        }
    }

    @Test
    void noContentWithoutJsonTypeIsAcknowledgementOnly() throws Exception {
        try (LocalKeycloakLoopbackFixture fixture = new LocalKeycloakLoopbackFixture()) {
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.noContent());
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.close());
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.close());
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.close());
            var run = candidate(fixture, "subject-2", Duration.ofSeconds(4)).runBoundedSequence();
            assertEquals(LocalKeycloakCandidateResult.State.ACKNOWLEDGED, run.disable().state());
            assertEquals("EXPECTED_204_ACK", run.disable().safeCode());
            assertNotEquals(LocalKeycloakCandidateResult.State.ACKNOWLEDGED, run.logout().state());
        }
    }

    @Test
    void lateAndTruncatedResponsesRemainUnknownOrMayHaveBeenSent() throws Exception {
        try (LocalKeycloakLoopbackFixture fixture = new LocalKeycloakLoopbackFixture()) {
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.raw(204, Map.of(), new byte[0], 250, -1));
            var run = candidate(fixture, "subject-3", Duration.ofMillis(100)).runBoundedSequence();
            assertEquals(LocalKeycloakCandidateResult.State.MAY_HAVE_BEEN_SENT, run.disable().state());
            assertEquals(LocalKeycloakCandidateResult.State.NOT_STARTED, run.logout().state());
            assertEquals(1, fixture.requests().size());
        }

        try (LocalKeycloakLoopbackFixture fixture = new LocalKeycloakLoopbackFixture()) {
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.raw(204, Map.of("Content-Length", "1"), new byte[] {1}, 0, -1));
            var run = candidate(fixture, "subject-4", Duration.ofSeconds(2)).runBoundedSequence();
            assertEquals(LocalKeycloakCandidateResult.State.UNKNOWN, run.disable().state());
        }

        try (LocalKeycloakLoopbackFixture fixture = new LocalKeycloakLoopbackFixture()) {
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.raw(200,
                    Map.of("Content-Type", "application/json", "Content-Length", "8"),
                    "{}".getBytes(StandardCharsets.UTF_8), 0, -1));
            var run = candidate(fixture, "subject-5", Duration.ofSeconds(2)).runBoundedSequence();
            assertEquals(LocalKeycloakCandidateResult.State.UNKNOWN, run.disable().state());
        }
    }

    @Test
    void parserRejectsAmbiguousAndUnboundedHttpFraming() {
        assertThrows(BoundedHttp1ResponseParser.ProtocolException.class,
                () -> parse("HTTP/1.1 200 OK\r\nContent-Length: 0\r\ncontent-length: 0\r\n\r\n"));
        assertThrows(BoundedHttp1ResponseParser.ProtocolException.class,
                () -> parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n"));
        assertThrows(BoundedHttp1ResponseParser.ProtocolException.class,
                () -> parse("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\nx"));
        assertThrows(BoundedHttp1ResponseParser.ProtocolException.class,
                () -> parse("HTTP/1.1 200 OK\nContent-Length: 0\n\n"));
        assertThrows(BoundedHttp1ResponseParser.ProtocolException.class,
                () -> parse("HTTP/1.1 200 OK\r\nContent-Length: 65537\r\n\r\n"));
        assertThrows(BoundedHttp1ResponseParser.ProtocolException.class,
                () -> parse("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n"));
    }

    @Test
    void redirectsAndUnclassifiableResponsesNeverBecomeAcknowledged() throws Exception {
        try (LocalKeycloakLoopbackFixture fixture = new LocalKeycloakLoopbackFixture()) {
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.raw(302,
                    Map.of("Location", "http://192.0.2.1/steal", "Content-Length", "0"), new byte[0], 0, -1));
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.json(500, "{\"error\":\"synthetic\"}"));
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.json(200, "{\"enabled\":false,\"enabled\":true}"));
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.json(200, "{\"not\":\"an array\"}"));
            var run = candidate(fixture, "subject-redirect", Duration.ofSeconds(4)).runBoundedSequence();
            assertTrue(run.operations().stream().allMatch(result -> result.state() == LocalKeycloakCandidateResult.State.UNKNOWN));
            assertEquals(4, fixture.requests().size());
            assertTrue(java.util.Arrays.stream(LocalKeycloakCandidateResult.State.values())
                    .noneMatch(state -> state.name().equals("CONFIRMED")));
        }
    }

    @Test
    void selectorWaitUsesTheExistingDeadlineWithoutProgressReset() throws Exception {
        Pipe pipe = Pipe.open();
        try (Selector selector = Selector.open(); Pipe.SourceChannel source = pipe.source();
             Pipe.SinkChannel sink = pipe.sink()) {
            source.configureBlocking(false);
            SelectionKey key = source.register(selector, SelectionKey.OP_READ);
            long started = System.nanoTime();
            var helper = new BoundedLoopbackHttp();
            assertThrows(BoundedLoopbackHttp.DeadlineExceeded.class,
                    () -> helper.awaitReady(selector, key, SelectionKey.OP_READ,
                            started + Duration.ofMillis(80).toNanos()));
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(1).toNanos());
        }
    }

    @Test
    void deterministicZeroProgressWriterExpiresInsideTheSharedDeadline() throws Exception {
        try (LocalKeycloakLoopbackFixture fixture = new LocalKeycloakLoopbackFixture()) {
            URI origin = URI.create("http://127.0.0.1:" + fixture.port());
            var target = LocalKeycloakCandidateGuard.target(origin, fixtureAllowlist(fixture));
            var request = new BoundedLoopbackHttp.WireRequest("GET",
                    "/admin/realms/synthetic-realm/users/subject-write-stall",
                    Map.of("Authorization", "Bearer synthetic-dummy-token"), new byte[0]);
            var clock = (BoundedLoopbackHttp.NanoClock) System::nanoTime;
            long deadline = BoundedLoopbackHttp.deadline(Duration.ofMillis(80), clock);
            var helper = new BoundedLoopbackHttp((channel, source) -> 0);
            var result = helper.execute(target, request, deadline);
            assertEquals(LocalKeycloakCandidateResult.State.MAY_HAVE_BEEN_SENT, result.state());
            assertEquals("TOTAL_DEADLINE_EXCEEDED", result.safeCode());
            assertTrue(result.elapsed().compareTo(Duration.ofSeconds(1)) < 0);
            assertTrue(fixture.requests().isEmpty());
        }
    }

    @Test
    void frameAndJsonCompletionAfterTheSharedDeadlineCannotReturnSuccess() throws Exception {
        try (LocalKeycloakLoopbackFixture fixture = new LocalKeycloakLoopbackFixture()) {
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.noContent());
            URI origin = URI.create("http://127.0.0.1:" + fixture.port());
            AtomicLong offset = new AtomicLong();
            BoundedLoopbackHttp.NanoClock clock = () -> System.nanoTime() + offset.get();
            var helper = new BoundedLoopbackHttp(SocketChannel::write,
                    () -> offset.set(Duration.ofSeconds(1).toNanos()), clock);
            var wire = new BoundedLoopbackHttp.WireRequest("POST",
                    "/admin/realms/synthetic-realm/users/subject-frame/logout",
                    Map.of("Authorization", "Bearer synthetic-dummy-token"), new byte[0]);
            var response = helper.execute(LocalKeycloakCandidateGuard.target(origin, fixtureAllowlist(fixture)),
                    wire, BoundedLoopbackHttp.deadline(Duration.ofMillis(500), clock));
            assertEquals(LocalKeycloakCandidateResult.State.UNKNOWN, response.state());
            assertEquals("TOTAL_DEADLINE_EXCEEDED", response.safeCode());
        }

        try (LocalKeycloakLoopbackFixture fixture = new LocalKeycloakLoopbackFixture()) {
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.noContent());
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.noContent());
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.json(200, "{\"enabled\":false}"));
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.json(200, "[]"));
            AtomicLong offset = new AtomicLong();
            BoundedLoopbackHttp.NanoClock clock = () -> System.nanoTime() + offset.get();
            var run = candidate(fixture, "subject-json-deadline", Duration.ofMillis(500),
                    () -> offset.set(Duration.ofSeconds(1).toNanos()), clock)
                    .runBoundedSequence();
            assertEquals(LocalKeycloakCandidateResult.State.UNKNOWN, run.userObservation().state());
            assertEquals("TOTAL_DEADLINE_EXCEEDED", run.userObservation().safeCode());
            assertEquals(LocalKeycloakCandidateResult.State.NOT_STARTED, run.sessionObservation().state());
        }
    }

    @Test
    void rawNulInContentLengthIsRejectedBeforeOwsRemoval() throws Exception {
        List<String> malformedValues = List.of(String.valueOf((char) 0) + "0",
                "0" + (char) 0, String.valueOf((char) 1) + "0");
        for (int i = 0; i < malformedValues.size(); i++) {
            try (LocalKeycloakLoopbackFixture fixture = new LocalKeycloakLoopbackFixture()) {
                fixture.enqueue(LocalKeycloakLoopbackFixture.Response.raw(204,
                        Map.of("Content-Length", malformedValues.get(i)), new byte[0], 0, -1));
                var run = candidate(fixture, "subject-nul-" + i, Duration.ofSeconds(2)).runBoundedSequence();
                assertEquals(LocalKeycloakCandidateResult.State.UNKNOWN, run.disable().state());
                assertEquals("RESPONSE_FRAMING_UNKNOWN", run.disable().safeCode());
            }
        }
        assertThrows(BoundedHttp1ResponseParser.ProtocolException.class,
                () -> parse("HTTP/1.1 204 No Content\r\nContent-Length: "
                        + (char) 0 + "0\r\n\r\n"));
    }

    @Test
    void jsonTextAndFieldNamesAreBoundedByUtf8Bytes() throws Exception {
        try (LocalKeycloakLoopbackFixture fixture = new LocalKeycloakLoopbackFixture()) {
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.noContent());
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.noContent());
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.json(200,
                    "{\"enabled\":false,\"" + "項".repeat(50) + "\":1}"));
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.json(200, "[]"));
            var run = candidate(fixture, "subject-json-name", Duration.ofSeconds(3)).runBoundedSequence();
            assertEquals(LocalKeycloakCandidateResult.State.UNKNOWN, run.userObservation().state());
        }
        try (LocalKeycloakLoopbackFixture fixture = new LocalKeycloakLoopbackFixture()) {
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.noContent());
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.noContent());
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.json(200,
                    "{\"enabled\":false,\"extra\":\"" + "漢".repeat(1400) + "\"}"));
            fixture.enqueue(LocalKeycloakLoopbackFixture.Response.json(200, "[]"));
            var run = candidate(fixture, "subject-json-string", Duration.ofSeconds(3)).runBoundedSequence();
            assertEquals(LocalKeycloakCandidateResult.State.UNKNOWN, run.userObservation().state());
        }
    }

    private static BoundedHttp1ResponseParser.Parsed parse(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        BoundedHttp1ResponseParser parser = new BoundedHttp1ResponseParser();
        parser.feed(bytes, 0, bytes.length);
        return parser.response();
    }

    private static LocalKeycloakExternalAccountProviderCandidate candidate(LocalKeycloakLoopbackFixture fixture,
                                                                             String userId, Duration timeout) {
        return candidate(fixture, userId, timeout, () -> { });
    }

    private static LocalKeycloakExternalAccountProviderCandidate candidate(LocalKeycloakLoopbackFixture fixture,
                                                                             String userId, Duration timeout,
                                                                             Runnable afterJsonParsed) {
        return candidate(fixture, userId, timeout, afterJsonParsed, System::nanoTime);
    }

    private static LocalKeycloakExternalAccountProviderCandidate candidate(LocalKeycloakLoopbackFixture fixture,
                                                                             String userId, Duration timeout,
                                                                             Runnable afterJsonParsed,
                                                                             BoundedLoopbackHttp.NanoClock clock) {
        URI origin = URI.create("http://127.0.0.1:" + fixture.port());
        return new LocalKeycloakExternalAccountProviderCandidate(new LocalKeycloakCandidateRequest(
                origin, fixtureAllowlist(fixture), "synthetic-realm", userId,
                new SyntheticBearerToken("synthetic-dummy-token"), timeout), afterJsonParsed, clock);
    }

    private static LocalKeycloakCandidateGuard.FixtureAllowlist fixtureAllowlist(LocalKeycloakLoopbackFixture fixture) {
        try {
            return new LocalKeycloakCandidateGuard.FixtureAllowlist(java.util.Set.of(
                    new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), fixture.port())));
        } catch (java.net.UnknownHostException impossible) {
            throw new IllegalStateException("loopback fixture address unavailable", impossible);
        }
    }

}
