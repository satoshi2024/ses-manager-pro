package com.ses.importcenter.metadata;

import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;

import java.io.InputStream;

/** 認可後の検証streamとimmutable metadata。CSV行やPIIを含まない。 */
public record ImportSourceContent(InputStream stream, String originalFileName, ImportEncoding encoding,
                                  ImportEntity entity, String sha256Hex) implements AutoCloseable {
    public ImportSourceContent {
        if (stream == null || originalFileName == null || encoding == null || entity == null
                || sha256Hex == null || !sha256Hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("取込source content snapshotが不正です");
        }
    }

    @Override
    public void close() throws java.io.IOException {
        stream.close();
    }
}
