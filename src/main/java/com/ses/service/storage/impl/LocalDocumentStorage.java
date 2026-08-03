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

/**
 * ローカルファイルシステムを使ったDocumentStorage実装（R5.1）。
 * 実ファイルシステム上の `documents/quarantine` および `documents/published` に保存・昇格を行う。
 */
@Slf4j
@Service
@ConditionalOnMissingBean(name = "s3DocumentStorage")
public class LocalDocumentStorage implements DocumentStorage {

    private final Path quarantinePath;
    private final Path publishedPath;

    public LocalDocumentStorage(@Value("${app.upload.base-path:./uploads}") String basePath) {
        Path base = Paths.get(basePath, "documents");
        this.quarantinePath = base.resolve("quarantine");
        this.publishedPath = base.resolve("published");
        try {
            Files.createDirectories(quarantinePath);
            Files.createDirectories(publishedPath);
        } catch (IOException e) {
            log.error("[LocalDocumentStorage] ディレクトリ作成失敗: {}", e.getMessage());
        }
    }

    @Override
    public void put(String key, InputStream content, boolean quarantine) {
        Path target = resolvePath(key, quarantine);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            log.debug("[LocalDocumentStorage] put stream: quarantine={} target={}", quarantine, target);
        } catch (IOException e) {
            throw new RuntimeException("Storage書き込み失敗: " + key, e);
        }
    }

    @Override
    public void put(String key, byte[] content, boolean quarantine) {
        put(key, new ByteArrayInputStream(content), quarantine);
    }

    @Override
    public void promote(String key) {
        Path src = resolvePath(key, true);
        Path dest = resolvePath(key, false);
        try {
            if (Files.exists(src)) {
                Files.createDirectories(dest.getParent());
                Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                log.debug("[LocalDocumentStorage] promote: src={} -> dest={}", src, dest);
            }
        } catch (IOException e) {
            throw new RuntimeException("Storage昇格失敗: " + key, e);
        }
    }

    @Override
    public InputStream open(String key) {
        Path target = resolvePath(key, false);
        if (!Files.exists(target)) {
            throw new RuntimeException("Storageファイルが存在しません: " + key);
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new RuntimeException("Storageオープン失敗: " + key, e);
        }
    }

    @Override
    public byte[] readAll(String key) throws IOException {
        Path target = resolvePath(key, false);
        if (!Files.exists(target)) {
            throw new IOException("Storageファイルが存在しません: " + key);
        }
        return Files.readAllBytes(target);
    }

    @Override
    public void delete(String key) {
        Path qFile = resolvePath(key, true);
        Path pFile = resolvePath(key, false);
        try {
            Files.deleteIfExists(qFile);
            Files.deleteIfExists(pFile);
            log.debug("[LocalDocumentStorage] delete: key={}", key);
        } catch (IOException e) {
            log.warn("[LocalDocumentStorage] delete失敗: key={} error={}", key, e.getMessage());
        }
    }

    private Path resolvePath(String key, boolean quarantine) {
        // セキュリティのためディレクトリトラバーサル防止
        String safeKey = key.replace("..", "").replace("\\", "/");
        Path base = quarantine ? quarantinePath : publishedPath;
        return base.resolve(safeKey);
    }
}
