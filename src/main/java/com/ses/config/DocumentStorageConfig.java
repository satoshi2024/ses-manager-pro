package com.ses.config;

import com.ses.service.storage.DocumentStorage;
import com.ses.service.storage.impl.LocalDocumentStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Storage Adapter 設定クラス（F2: Storage adapterとstream download）。
 * {@code app.storage.type=s3} の場合は S3 用のモック/アダプタ Bean を生成し、
 * それ以外（既定: local）の場合は LocalDocumentStorage をプライマリとして有効化する。
 */
@Slf4j
@Configuration
public class DocumentStorageConfig {

    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
    public DocumentStorage s3DocumentStorage() {
        log.info("[DocumentStorageConfig] S3 Storage Adapter が有効化されました");
        return new S3MockDocumentStorage();
    }

    /** S3Fake/Mock Storage 実装 */
    private static class S3MockDocumentStorage implements DocumentStorage {
        private final ConcurrentHashMap<String, byte[]> s3Bucket = new ConcurrentHashMap<>();

        @Override
        public void put(String key, byte[] content, boolean quarantine) {
            s3Bucket.put(quarantine ? "s3-q:" + key : "s3:" + key, content);
            log.debug("[S3MockDocumentStorage] put S3 key={}", key);
        }

        @Override
        public void promote(String key) {
            byte[] c = s3Bucket.remove("s3-q:" + key);
            if (c != null) {
                s3Bucket.put("s3:" + key, c);
            }
            log.debug("[S3MockDocumentStorage] promote S3 key={}", key);
        }

        @Override
        public InputStream open(String key) {
            byte[] c = s3Bucket.get("s3:" + key);
            if (c == null) {
                throw new RuntimeException("S3 object not found: " + key);
            }
            return new ByteArrayInputStream(c);
        }

        @Override
        public byte[] readAll(String key) throws IOException {
            byte[] c = s3Bucket.get("s3:" + key);
            if (c == null) {
                throw new IOException("S3 object not found: " + key);
            }
            return c;
        }

        @Override
        public void delete(String key) {
            s3Bucket.remove("s3:" + key);
            s3Bucket.remove("s3-q:" + key);
            log.debug("[S3MockDocumentStorage] delete S3 key={}", key);
        }
    }
}
