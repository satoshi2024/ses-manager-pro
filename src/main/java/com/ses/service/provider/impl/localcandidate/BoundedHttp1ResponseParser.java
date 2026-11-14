package com.ses.service.provider.impl.localcandidate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** ローカル候補が使うHTTP/1.1応答subsetの厳密・有界parser。 */
final class BoundedHttp1ResponseParser {
    static final int MAX_HEADER_COUNT = 32;
    static final int MAX_HEADER_LINE_BYTES = 4096;
    static final int MAX_HEADER_BYTES = 16 * 1024;
    static final int MAX_BODY_BYTES = 64 * 1024;

    private final ByteArrayOutputStream received = new ByteArrayOutputStream();
    private Parsed parsed;

    void feed(byte[] bytes, int offset, int length) {
        if (bytes == null || offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IllegalArgumentException("invalid parser input");
        }
        if (parsed != null && length != 0) throw new ProtocolException("bytes after response");
        if ((long) received.size() + length > MAX_HEADER_BYTES + MAX_BODY_BYTES) {
            throw new ProtocolException("response cap exceeded");
        }
        received.write(bytes, offset, length);
        parseIfComplete();
    }

    boolean isComplete() {
        return parsed != null;
    }

    Parsed response() {
        if (parsed == null) throw new IllegalStateException("response incomplete");
        return parsed;
    }

    private void parseIfComplete() {
        byte[] all = received.toByteArray();
        int delimiter = headerDelimiter(all);
        if (delimiter < 0) {
            if (all.length > MAX_HEADER_BYTES) throw new ProtocolException("header cap exceeded");
            checkPartialHeaderLine(all);
            return;
        }
        int headerLength = delimiter + 4;
        if (headerLength > MAX_HEADER_BYTES) throw new ProtocolException("header cap exceeded");
        HeaderBlock block = parseHeaders(all, delimiter);
        int bodyLength = bodyLength(block);
        if (bodyLength > MAX_BODY_BYTES) throw new ProtocolException("body cap exceeded");
        int expected = headerLength + bodyLength;
        if (all.length > expected) throw new ProtocolException("bytes after framed response");
        if (all.length == expected) {
            byte[] body = new byte[bodyLength];
            System.arraycopy(all, headerLength, body, 0, bodyLength);
            parsed = new Parsed(block.status, block.headers, body, all.length);
        }
    }

    private static HeaderBlock parseHeaders(byte[] all, int delimiter) {
        for (int i = 0; i < delimiter; i++) {
            if (all[i] == '\n' && (i == 0 || all[i - 1] != '\r')) {
                throw new ProtocolException("bare LF");
            }
            if (all[i] == '\r' && (i + 1 >= delimiter || all[i + 1] != '\n')) {
                throw new ProtocolException("bare CR");
            }
        }
        String text = new String(all, 0, delimiter, StandardCharsets.ISO_8859_1);
        String[] lines = text.split("\r\n", -1);
        if (lines.length < 1 || lines[0].length() > MAX_HEADER_LINE_BYTES
                || lines[0].chars().anyMatch(c -> c < 0x20 || c > 0x7e)) {
            throw new ProtocolException("invalid status line");
        }
        String[] statusParts = lines[0].split(" ", 3);
        if (statusParts.length < 2 || !"HTTP/1.1".equals(statusParts[0])
                || !statusParts[1].matches("[1-5][0-9]{2}") || statusParts[1].startsWith("1")) {
            throw new ProtocolException("unsupported status line");
        }
        int status = Integer.parseInt(statusParts[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        if (lines.length - 1 > MAX_HEADER_COUNT) throw new ProtocolException("header count exceeded");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            if (line.length() > MAX_HEADER_LINE_BYTES || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new ProtocolException("invalid header line");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) throw new ProtocolException("invalid header");
            String name = line.substring(0, colon);
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) throw new ProtocolException("invalid header name");
            String rawValue = line.substring(colon + 1);
            for (int j = 0; j < rawValue.length(); j++) {
                char c = rawValue.charAt(j);
                if ((c < 0x20 && c != '\t') || c > 0x7e) throw new ProtocolException("invalid header value");
            }
            String value = stripOptionalWhitespace(rawValue);
            String key = name.toLowerCase(Locale.ROOT);
            if (headers.putIfAbsent(key, value) != null) throw new ProtocolException("duplicate header");
        }
        if (headers.containsKey("transfer-encoding")) throw new ProtocolException("transfer encoding unsupported");
        if (headers.containsKey("content-length") && headers.get("content-length").contains(",")) {
            throw new ProtocolException("multiple content lengths unsupported");
        }
        return new HeaderBlock(status, Map.copyOf(headers));
    }

    private static int bodyLength(HeaderBlock block) {
        String rawLength = block.headers.get("content-length");
        if (block.status == 204) {
            if (rawLength == null) return 0;
            if (!rawLength.matches("[0-9]{1,10}") || parseLength(rawLength) != 0) {
                throw new ProtocolException("204 body forbidden");
            }
            return 0;
        }
        if (rawLength == null || !rawLength.matches("[0-9]{1,10}")) {
            throw new ProtocolException("content length required");
        }
        return parseLength(rawLength);
    }

    private static int parseLength(String value) {
        try {
            long length = Long.parseLong(value);
            if (length > MAX_BODY_BYTES) throw new ProtocolException("body cap exceeded");
            return (int) length;
        } catch (NumberFormatException invalid) {
            throw new ProtocolException("invalid content length");
        }
    }

    private static String stripOptionalWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) start++;
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) end--;
        return value.substring(start, end);
    }

    private static int headerDelimiter(byte[] bytes) {
        for (int i = 0; i + 3 < bytes.length; i++) {
            if (bytes[i] == '\r' && bytes[i + 1] == '\n' && bytes[i + 2] == '\r' && bytes[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static void checkPartialHeaderLine(byte[] bytes) {
        int lineStart = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                if (i == 0 || bytes[i - 1] != '\r') throw new ProtocolException("bare LF");
                lineStart = i + 1;
            }
        }
        if (bytes.length - lineStart > MAX_HEADER_LINE_BYTES) throw new ProtocolException("header line exceeded");
    }

    record Parsed(int status, Map<String, String> headers, byte[] body, int responseBytes) {
        Parsed {
            headers = Map.copyOf(headers);
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        @Override public String toString() {
            return "Parsed[status=" + status + ", headers=" + headers.keySet() + ", bodyBytes=" + body.length + "]";
        }
    }

    private record HeaderBlock(int status, Map<String, String> headers) { }

    static final class ProtocolException extends RuntimeException {
        ProtocolException(String safeMessage) { super(safeMessage); }
    }
}
