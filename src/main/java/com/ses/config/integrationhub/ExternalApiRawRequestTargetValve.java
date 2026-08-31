package com.ses.config.integrationhub;

import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Tomcat connector境界でservlet正規化前のorigin-form request-targetを捕捉する。
 * getRequestURI()/getQueryString()はcanonicalizerの入力に使わない。
 */
public final class ExternalApiRawRequestTargetValve extends ValveBase {
    public ExternalApiRawRequestTargetValve() {
        super(true);
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, jakarta.servlet.ServletException {
        byte[] rawTarget = rawTarget(request);
        if (rawTarget != null) {
            request.setAttribute(ExternalApiCanonicalRequest.RAW_TARGET_ATTRIBUTE, rawTarget);
        }
        String connectorPeer = request.getRemoteAddr();
        if (connectorPeer != null && !connectorPeer.isBlank()) {
            request.setAttribute(ExternalApiSourceIpResolver.CONNECTOR_PEER_ATTRIBUTE, connectorPeer);
        }
        Valve next = getNext();
        if (next == null) {
            throw new IllegalStateException("external raw request-target valve has no next valve");
        }
        next.invoke(request, response);
    }

    private byte[] rawTarget(Request request) {
        if (request == null || request.getCoyoteRequest() == null) {
            return null;
        }
        MessageBytes uri = request.getCoyoteRequest().requestURI();
        byte[] rawPath = rawBytes(uri);
        if (rawPath == null) {
            return null;
        }
        MessageBytes query = request.getCoyoteRequest().queryString();
        if (query == null || query.isNull()) {
            return rawPath;
        }
        byte[] rawQuery = rawBytes(query);
        if (rawQuery == null) {
            return null;
        }
        ByteArrayOutputStream target = new ByteArrayOutputStream(rawPath.length + rawQuery.length + 1);
        target.writeBytes(rawPath);
        target.write('?');
        target.writeBytes(rawQuery);
        return target.toByteArray();
    }

    private byte[] rawBytes(MessageBytes value) {
        if (value == null || value.isNull() || value.getType() != MessageBytes.T_BYTES) {
            return null;
        }
        ByteChunk chunk = value.getByteChunk();
        if (chunk == null || chunk.getBytes() == null || chunk.getStart() < 0
                || chunk.getEnd() < chunk.getStart() || chunk.getEnd() > chunk.getBytes().length) {
            return null;
        }
        return Arrays.copyOfRange(chunk.getBytes(), chunk.getStart(), chunk.getEnd());
    }
}
