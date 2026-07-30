package com.ses.service.storage.impl;

import com.ses.service.storage.DocumentStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ローカルファイルシステムを使ったDocumentStorage実装（開発・テスト用）。
 * T023でS3/ローカル切替可能なStorageAdapterを実装する。
 * production profileではS3実装が優先されるため、このBeanは {@code @ConditionalOnMissingBean} とする。
 *
 * <p>隔離（quarantine）はサブディレクトリ {@code .quarantine/} で代替する。</p>
 */
@Slf4j
@Service
@ConditionalOnMissingBean(name = "s3DocumentStorage")
public class LocalDocumentStorage implements DocumentStorage {

    private final Path basePath;
    /** テスト用: メモリストア（in-memory fallback）。T023でファイルシステムのみに変更予定 */
    private final ConcurrentHashMap<String, byte[]> memStore = new ConcurrentHashMap<>();

    public LocalDocumentStorage(@Value("${app.upload.base-path:./uploads/documents}") String basePath) {
        this.basePath = Paths.get(basePath);
    }

    @Override
    public void put(String key, byte[] content, boolean quarantine) {
        // テスト環境: memoryに保存（ファイルシステム不要）
        memStore.put(quarantine ? "q:" + key : key, content);
        log.debug("[LocalDocumentStorage] put: quarantine={} key={} size={}B", quarantine, key, content.length);
    }

    @Override
    public void promote(String key) {
        byte[] content = memStore.remove("q:" + key);
        if (content != null) {
            memStore.put(key, content);
        }
        // ファイルシステムへの永続化はT023で実装
        log.debug("[LocalDocumentStorage] promote: key={}", key);
    }

    @Override
    public InputStream open(String key) {
        byte[] content = memStore.get(key);
        if (content == null) {
            // ファイルシステムから読む（永続化後）
            try {
                content = Files.readAllBytes(basePath.resolve(key.replace("/", "_")));
            } catch (IOException e) {
                throw new RuntimeException("storage読込失敗: " + key, e);
            }
        }
        return new ByteArrayInputStream(content);
    }

    @Override
    public byte[] readAll(String key) throws IOException {
        byte[] content = memStore.get(key);
        if (content == null) {
            try {
                content = Files.readAllBytes(basePath.resolve(key.replace("/", "_")));
            } catch (IOException e) {
                throw new IOException("storage読込失敗: " + key, e);
            }
        }
        return content;
    }

    @Override
    public void delete(String key) {
        memStore.remove(key);
        memStore.remove("q:" + key);
        log.debug("[LocalDocumentStorage] delete: key={}", key);
    }
}
