package com.ses.config.integrationhub;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/** canonical検証で読み取ったbodyをcontrollerへ同じraw byte列で再提示するwrapper。 */
final class ExternalApiCachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    ExternalApiCachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return input.read();
            }

            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                if (readListener == null) {
                    throw new IllegalArgumentException("readListener is required");
                }
                try {
                    if (isFinished()) {
                        readListener.onAllDataRead();
                    } else {
                        readListener.onDataAvailable();
                    }
                } catch (IOException e) {
                    readListener.onError(e);
                }
            }
        };
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }
}
