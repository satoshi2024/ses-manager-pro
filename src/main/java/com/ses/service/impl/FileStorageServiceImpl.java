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
            throw BusinessException.of("error.file.empty");
        }
        // サイズ検証
        if (file.getSize() > kind.getMaxBytes()) {
            throw BusinessException.of("error.file.sizeOver", (kind.getMaxBytes() / 1024 / 1024));
        }
        // 拡張子検証
        String originalName = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
        String ext = StringUtils.getFilenameExtension(originalName);
        if (!kind.isExtensionAllowed(ext)) {
            throw BusinessException.of("error.file.extensionInvalid", kind.allowedExtensionsLabel());
        }
        // Content-Type検証
        if (!kind.isContentTypeAllowed(file.getContentType())) {
            throw BusinessException.of("error.file.formatInvalid");
        }

        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext.toLowerCase();
        try {
            Path base = baseDir();
            Files.createDirectories(base);
            Path target = base.resolve(storedName).normalize();
            // 念のため保存先がベースディレクトリ配下であることを確認
            if (!target.startsWith(base)) {
                throw BusinessException.of("error.file.invalidDestination");
            }
            file.transferTo(target);
            return new StoredFile(storedName, originalName, file.getSize());
        } catch (IOException e) {
            log.error("ファイル保存に失敗しました: {}", storedName, e);
            throw BusinessException.of("error.file.saveFailed");
        }
    }

    @Override
    public Resource load(String storedName) {
        // ファイル名に区切り文字や親参照が含まれる場合は拒否
        if (storedName == null || storedName.isBlank()
                || storedName.contains("/") || storedName.contains("\\") || storedName.contains("..")) {
            throw BusinessException.of("error.file.invalidName");
        }
        Path base = baseDir();
        Path target = base.resolve(storedName).normalize();
        if (!target.startsWith(base)) {
            throw BusinessException.of("error.file.invalidPath");
        }
        if (!Files.exists(target) || !Files.isReadable(target)) {
            throw BusinessException.of("error.file.notFound");
        }
        try {
            return new UrlResource(target.toUri());
        } catch (MalformedURLException e) {
            throw BusinessException.of("error.file.readFailed");
        }
    }
}




















