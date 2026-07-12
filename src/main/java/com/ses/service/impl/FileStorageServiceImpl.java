package com.ses.service.impl;

import com.ses.common.enums.FileKind;
import com.ses.common.exception.BusinessException;
import com.ses.config.UploadProperties;
import com.ses.dto.file.StoredFile;
import com.ses.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * ファイル保存サービス実装（ローカルディレクトリ保存）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageServiceImpl implements FileStorageService {

    private final UploadProperties uploadProperties;

    private Path baseDir() {
        return Paths.get(uploadProperties.getBasePath()).toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(MultipartFile file, FileKind kind) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("ファイルが空です");
        }
        // サイズ検証
        if (file.getSize() > kind.getMaxBytes()) {
            throw new BusinessException("ファイルサイズが上限（" + (kind.getMaxBytes() / 1024 / 1024) + "MB）を超えています");
        }
        // 拡張子検証
        String originalName = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
        String ext = StringUtils.getFilenameExtension(originalName);
        if (!kind.isExtensionAllowed(ext)) {
            throw new BusinessException("許可されていない拡張子です（許可: " + kind.allowedExtensionsLabel() + "）");
        }
        // Content-Type検証
        if (!kind.isContentTypeAllowed(file.getContentType())) {
            throw new BusinessException("許可されていないファイル形式です");
        }

        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext.toLowerCase();
        try {
            Path base = baseDir();
            Files.createDirectories(base);
            Path target = base.resolve(storedName).normalize();
            // 念のため保存先がベースディレクトリ配下であることを確認
            if (!target.startsWith(base)) {
                throw new BusinessException("不正な保存先です");
            }
            file.transferTo(target);
            return new StoredFile(storedName, originalName, file.getSize());
        } catch (IOException e) {
            log.error("ファイル保存に失敗しました: {}", storedName, e);
            throw new BusinessException("ファイルの保存に失敗しました");
        }
    }

    @Override
    public Resource load(String storedName) {
        // ファイル名に区切り文字や親参照が含まれる場合は拒否
        if (storedName == null || storedName.isBlank()
                || storedName.contains("/") || storedName.contains("\\") || storedName.contains("..")) {
            throw new BusinessException("不正なファイル名です");
        }
        Path base = baseDir();
        Path target = base.resolve(storedName).normalize();
        if (!target.startsWith(base)) {
            throw new BusinessException("不正なファイルパスです");
        }
        if (!Files.exists(target) || !Files.isReadable(target)) {
            throw new BusinessException("ファイルが見つかりません");
        }
        try {
            return new UrlResource(target.toUri());
        } catch (MalformedURLException e) {
            throw new BusinessException("ファイルの読み込みに失敗しました");
        }
    }
}
