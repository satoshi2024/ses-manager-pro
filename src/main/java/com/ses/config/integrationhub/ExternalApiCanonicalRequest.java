package com.ses.config.integrationhub;

import com.ses.service.integrationhub.IntegrationHubDigest;
import jakarta.servlet.http.HttpServletRequest;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** NF-05 HMACのraw request-target / canonical byte生成。 */
public final class ExternalApiCanonicalRequest {
    public static final String RAW_TARGET_ATTRIBUTE = "external.raw-request-target";
    public static final int MAX_RAW_TARGET_BYTES = 4096;
    public static final int MAX_RAW_PATH_OR_QUERY_BYTES = 2048;
    public static final int MAX_CANONICAL_PATH_BYTES = 2048;
    public static final int MAX_QUERY_PAIRS = 50;
    public static final int MAX_QUERY_COMPONENT_BYTES = 256;
    private static final byte[] PREFIX = "IH-HMAC-SHA256-V1\n".getBytes(StandardCharsets.US_ASCII);

    private ExternalApiCanonicalRequest() {
    }

    public record Parsed(String canonicalTarget, String canonicalPath, byte[] rawBody, String bodySha256) {
        public Parsed {
            rawBody = rawBody.clone();
        }
    }

    public static Parsed parse(HttpServletRequest request, byte[] rawBody) {
        if (request == null || rawBody == null) {
            throw ExternalApiSecurityException.invalid("REQUEST_TARGET_INVALID");
        }
        Object raw = request.getAttribute(RAW_TARGET_ATTRIBUTE);
        if (!(raw instanceof byte[] rawTarget)) {
            throw ExternalApiSecurityException.invalid("RAW_REQUEST_TARGET_UNAVAILABLE");
        }
        String target = decodeAscii(rawTarget);
        String canonicalTarget = canonicalizeTarget(target);
        String canonicalPath = canonicalTarget.substring(0, canonicalTarget.indexOf('?') >= 0
                ? canonicalTarget.indexOf('?') : canonicalTarget.length());
        if (canonicalPath.getBytes(StandardCharsets.UTF_8).length > MAX_CANONICAL_PATH_BYTES) {
            throw ExternalApiSecurityException.invalid("REQUEST_TARGET_TOO_LARGE");
        }
        return new Parsed(canonicalTarget, canonicalPath, rawBody, IntegrationHubDigest.sha256Hex(rawBody));
    }

    /** Design golden vectorを含む、署名対象の完全なbyte列。 */
    public static byte[] signedBytes(String clientId, String credentialVersion, String keyId,
                                     String timestamp, String nonce, String method,
                                     String canonicalTarget, String bodySha256) {
        List<Field> fields = List.of(
                new Field("clientId", clientId),
                new Field("credentialVersion", credentialVersion),
                new Field("keyId", keyId),
                new Field("timestamp", timestamp),
                new Field("nonce", nonce),
                new Field("method", method),
                new Field("canonicalTarget", canonicalTarget),
                new Field("bodySha256", bodySha256));
        ByteArrayOutputStream output = new ByteArrayOutputStream(PREFIX.length + 512);
        output.writeBytes(PREFIX);
        for (Field field : fields) {
            byte[] value = field.value().getBytes(StandardCharsets.UTF_8);
            output.writeBytes((field.name() + ":" + value.length + ":").getBytes(StandardCharsets.US_ASCII));
            output.writeBytes(value);
            output.write('\n');
        }
        return output.toByteArray();
    }

    private record Field(String name, String value) {
    }

    private static String decodeAscii(byte[] rawTarget) {
        if (rawTarget.length == 0 || rawTarget.length > MAX_RAW_TARGET_BYTES) {
            throw ExternalApiSecurityException.invalid("REQUEST_TARGET_TOO_LARGE");
        }
        for (byte value : rawTarget) {
            int unsigned = value & 0xff;
            if (unsigned > 0x7f || unsigned == 0 || unsigned == '\r' || unsigned == '\n'
                    || unsigned == '#' || unsigned == '\\') {
                throw ExternalApiSecurityException.invalid("REQUEST_TARGET_INVALID");
            }
        }
        return new String(rawTarget, StandardCharsets.US_ASCII);
    }

    static String canonicalizeTarget(String target) {
        int queryIndex = target.indexOf('?');
        String rawPath = queryIndex < 0 ? target : target.substring(0, queryIndex);
        String rawQuery = queryIndex < 0 ? null : target.substring(queryIndex + 1);
        if (!rawPath.startsWith("/") || rawPath.isEmpty() || rawPath.contains("://") || rawPath.contains("#")) {
            throw ExternalApiSecurityException.invalid("REQUEST_TARGET_INVALID");
        }
        if (rawPath.getBytes(StandardCharsets.US_ASCII).length > MAX_RAW_PATH_OR_QUERY_BYTES
                || (rawQuery != null && rawQuery.getBytes(StandardCharsets.US_ASCII).length > MAX_RAW_PATH_OR_QUERY_BYTES)) {
            throw ExternalApiSecurityException.invalid("REQUEST_TARGET_TOO_LARGE");
        }
        String path = canonicalizePath(rawPath);
        if (path.getBytes(StandardCharsets.UTF_8).length > MAX_CANONICAL_PATH_BYTES) {
            throw ExternalApiSecurityException.invalid("REQUEST_TARGET_TOO_LARGE");
        }
        if (rawQuery == null || rawQuery.isEmpty()) {
            return path;
        }
        List<QueryPair> pairs = new ArrayList<>();
        String[] rawPairs = rawQuery.split("&", -1);
        if (rawPairs.length > MAX_QUERY_PAIRS) {
            throw ExternalApiSecurityException.invalid("QUERY_TOO_LARGE");
        }
        for (int index = 0; index < rawPairs.length; index++) {
            String rawPair = rawPairs[index];
            if (rawPair.isEmpty()) {
                throw ExternalApiSecurityException.invalid("QUERY_INVALID");
            }
            int equals = rawPair.indexOf('=');
            String rawName = equals < 0 ? rawPair : rawPair.substring(0, equals);
            String rawValue = equals < 0 ? "" : rawPair.substring(equals + 1);
            if (rawName.isEmpty()) {
                throw ExternalApiSecurityException.invalid("QUERY_INVALID");
            }
            String name = canonicalizeComponent(rawName.getBytes(StandardCharsets.US_ASCII));
            String value = canonicalizeComponent(rawValue.getBytes(StandardCharsets.US_ASCII));
            if (name.isEmpty()
                    || name.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_COMPONENT_BYTES
                    || value.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_COMPONENT_BYTES) {
                throw ExternalApiSecurityException.invalid("QUERY_TOO_LARGE");
            }
            pairs.add(new QueryPair(name, value, index));
        }
        pairs.sort(Comparator.comparing(QueryPair::name).thenComparing(QueryPair::value)
                .thenComparingInt(QueryPair::originalIndex));
        StringBuilder query = new StringBuilder();
        for (QueryPair pair : pairs) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(pair.name()).append('=').append(pair.value());
        }
        return path + "?" + query;
    }

    private record QueryPair(String name, String value, int originalIndex) {
    }

    private static String canonicalizePath(String rawPath) {
        StringBuilder result = new StringBuilder(rawPath.length());
        byte[] bytes = rawPath.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            if (value == '/') {
                result.append('/');
            } else if (isUnreserved(value)) {
                result.append((char) value);
            } else if (value == '%') {
                i = appendPercentNormalized(bytes, i, result);
            } else {
                appendPercent(value, result);
            }
        }
        return result.toString();
    }

    private static String canonicalizeComponent(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            if (isUnreserved(value)) {
                result.append((char) value);
            } else if (value == '%') {
                i = appendPercentNormalized(bytes, i, result);
            } else {
                appendPercent(value, result);
            }
        }
        return result.toString();
    }

    private static int appendPercentNormalized(byte[] bytes, int index, StringBuilder result) {
        if (index + 2 >= bytes.length) {
            throw ExternalApiSecurityException.invalid("REQUEST_TARGET_INVALID");
        }
        int high = hex(bytes[index + 1]);
        int low = hex(bytes[index + 2]);
        if (high < 0 || low < 0) {
            throw ExternalApiSecurityException.invalid("REQUEST_TARGET_INVALID");
        }
        int decoded = (high << 4) | low;
        if (isUnreserved(decoded)) {
            result.append((char) decoded);
        } else {
            appendPercent(decoded, result);
        }
        return index + 2;
    }

    private static boolean isUnreserved(int value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9' || value == '-' || value == '.'
                || value == '_' || value == '~';
    }

    private static void appendPercent(int value, StringBuilder result) {
        result.append('%').append(Character.toUpperCase(Character.forDigit((value >>> 4) & 0xf, 16)))
                .append(Character.toUpperCase(Character.forDigit(value & 0xf, 16)));
    }

    private static int hex(byte value) {
        int c = value & 0xff;
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }
}
