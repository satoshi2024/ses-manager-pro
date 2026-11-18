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
            // Files.copyは途中まで書き込んだファイルを残し得るため、失敗時は
            // quarantine/publishedの後続処理へ到達する前に対象を消す。
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupError) {
                log.error("[LocalDocumentStorage] 部分書込みのcleanup失敗: target={} error={}",
                        target, cleanupError.getMessage());
            }
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

    /**
     * 失敗を握りつぶさない削除（NF-06 N6-2）。quarantine・公開の両方を必ず試み、最初の失敗を投げる。
     * 削除後に実体が残っていないことも確認する。例外messageにはkeyだけを入れ、絶対pathは原因側にだけ残す。
     */
    @Override
    public void deleteStrict(String key) throws IOException {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("削除対象のkeyが必要です");
        }
        Path qFile = resolvePath(key, true);
        Path pFile = resolvePath(key, false);
        IOException failure = null;
        for (Path target : new Path[]{qFile, pFile}) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException e) {
                if (failure == null) {
                    failure = new IOException("Storage削除失敗: " + key, e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        if (Files.exists(qFile) || Files.exists(pFile)) {
            throw new IOException("Storage実体が削除後も残っています: " + key);
        }
        log.debug("[LocalDocumentStorage] deleteStrict: key={}", key);
    }

    private Path resolvePath(String key, boolean quarantine) {
        // セキュリティのためディレクトリトラバーサル防止
        String safeKey = key.replace("..", "").replace("\\", "/");
        Path base = quarantine ? quarantinePath : publishedPath;
        return base.resolve(safeKey);
    }
}
