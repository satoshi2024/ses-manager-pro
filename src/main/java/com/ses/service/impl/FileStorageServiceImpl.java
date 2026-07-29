package com.ses.service.impl;

import com.ses.common.enums.FileKind;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.config.UploadProperties;
import com.ses.dto.file.StoredFile;
import com.ses.entity.FileSecurityMetadata;
import com.ses.mapper.FileSecurityMetadataMapper;
import com.ses.service.FileStorageService;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import com.ses.service.security.impl.LocalSignatureFileScanner;
import com.ses.service.security.impl.UnavailableFileScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.LocalDateTime;
import java.util.UUID;

/** quarantine保存後にmagic/MIME/scanを行い、CLEANだけを公開領域へ移す。 */
@Slf4j
@Service
public class FileStorageServiceImpl implements FileStorageService {

    private final UploadProperties uploadProperties;
    private final FileScanner fileScanner;
    private final FileSecurityMetadataMapper metadataMapper;

    /** 既存の単体テストとローカル利用向けの安全な直接生成経路。 */
    public FileStorageServiceImpl(UploadProperties uploadProperties) {
        this(uploadProperties,
                uploadProperties.isScannerEnabled() ? new LocalSignatureFileScanner() : new UnavailableFileScanner(),
                null);
    }

    @Autowired
    public FileStorageServiceImpl(UploadProperties uploadProperties,
                                  ObjectProvider<FileScanner> scannerProvider,
                                  ObjectProvider<FileSecurityMetadataMapper> metadataProvider) {
        this(uploadProperties,
                scannerProvider.getIfAvailable(UnavailableFileScanner::new),
                metadataProvider.getIfAvailable());
    }

    FileStorageServiceImpl(UploadProperties uploadProperties,
                           FileScanner fileScanner,
                           FileSecurityMetadataMapper metadataMapper) {
        this.uploadProperties = uploadProperties;
        this.fileScanner = fileScanner;
        this.metadataMapper = metadataMapper;
    }

    private Path baseDir() {
        return Paths.get(uploadProperties.getBasePath()).toAbsolutePath().normalize();
    }

    private Path quarantineDir() {
        return baseDir().resolve("quarantine").normalize();
    }

    private Path publishedDir() {
        return baseDir().resolve("published").normalize();
    }

    @Override
    public StoredFile store(MultipartFile file, FileKind kind) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.of("error.file.empty");
        }
        if (file.getSize() > kind.getMaxBytes()) {
            throw BusinessException.of("error.file.sizeOver", (kind.getMaxBytes() / 1024 / 1024));
        }
        String cleanName = StringUtils.cleanPath(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
        String extension = StringUtils.getFilenameExtension(cleanName);
        if (!kind.isExtensionAllowed(extension)) {
            throw BusinessException.of("error.file.extensionInvalid", kind.allowedExtensionsLabel());
        }
        if (!kind.isContentTypeAllowed(extension, file.getContentType())) {
            throw BusinessException.of("error.file.formatInvalid");
        }
        try {
            return storeBytes(file.getBytes(), file.getOriginalFilename(), kind);
        } catch (IOException e) {
            throw BusinessException.of("error.file.readFailed");
        }
    }

    @Override
    public StoredFile store(byte[] data, String originalName, FileKind kind) {
        return storeBytes(data, originalName, kind);
    }

    private StoredFile storeBytes(byte[] data, String originalName, FileKind kind) {
        if (kind == null || data == null || data.length == 0) {
            throw BusinessException.of("error.file.empty");
        }
        if (data.length > kind.getMaxBytes()) {
            throw BusinessException.of("error.file.sizeOver", (kind.getMaxBytes() / 1024 / 1024));
        }
        String cleanName = StringUtils.cleanPath(originalName == null ? "" : originalName);
        String extension = StringUtils.getFilenameExtension(cleanName);
        if (!kind.isExtensionAllowed(extension)) {
            throw BusinessException.of("error.file.extensionInvalid", kind.allowedExtensionsLabel());
        }
        if (!kind.isMagicBytesAllowed(extension, data)) {
            throw BusinessException.of("error.file.formatInvalid");
        }

        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + extension.toLowerCase();
        Path quarantine = quarantineDir().resolve(storedName).normalize();
        Path published = publishedDir().resolve(storedName).normalize();
        boolean scanRejectionRecorded = false;
        try {
            Files.createDirectories(quarantineDir());
            Files.createDirectories(publishedDir());
            if (!quarantine.startsWith(quarantineDir()) || !published.startsWith(publishedDir())) {
                throw BusinessException.of("error.file.invalidDestination");
            }
            Files.write(quarantine, data);
            FileSecurityMetadata metadata = createPendingMetadata(storedName, kind);
            FileScanResult result;
            try {
                result = fileScanner == null
                        ? FileScanResult.unavailable("scanner is not configured")
                        : fileScanner.scan(quarantine, kind);
            } catch (RuntimeException e) {
                result = FileScanResult.unavailable("scanner failed");
            }
            if (result == null || result.status() != FileScanResult.Status.CLEAN) {
                updateMetadata(metadata, "QUARANTINED",
                        result == null ? FileScanResult.Status.UNAVAILABLE.name() : result.status().name(),
                        result, result == null ? "scanner returned no result" : result.reason());
                // 再scan可能なよう、拒否結果を記録できた実体はquarantineへ保持する。
                scanRejectionRecorded = true;
                throw BusinessException.of("error.file.scanRejected");
            }
            Files.move(quarantine, published);
            updateMetadata(metadata, "PUBLISHED", FileScanResult.Status.CLEAN.name(), result, null);
            return new StoredFile(storedName, cleanName, data.length);
        } catch (BusinessException e) {
            if (!scanRejectionRecorded) {
                deleteQuietly(quarantine);
            }
            throw e;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(quarantine);
            log.error("ファイル保存またはscanに失敗しました: {}", storedName, e);
            throw BusinessException.of("error.file.scanRejected");
        }
    }

    private FileSecurityMetadata createPendingMetadata(String storedName, FileKind kind) {
        if (metadataMapper == null) {
            return null;
        }
        FileSecurityMetadata metadata = new FileSecurityMetadata();
        metadata.setTenantId("default");
        metadata.setStoredName(storedName);
        metadata.setFileKind(kind.name());
        metadata.setStorageState("QUARANTINED");
        metadata.setScanStatus("PENDING");
        metadata.setCreatedBy(SecurityUtils.currentUserId());
        if (metadataMapper.insert(metadata) != 1) {
            throw BusinessException.of("error.file.scanRejected");
        }
        return metadata;
    }

    private void updateMetadata(FileSecurityMetadata metadata, String storageState,
                                String scanStatus, FileScanResult result, String reason) {
        if (metadata == null || metadataMapper == null) {
            return;
        }
        int updated = metadataMapper.updateScanResult(metadata.getId(), storageState, scanStatus,
                LocalDateTime.now(), result == null ? null : result.scannerVersion(),
                reason == null ? null : reason.substring(0, Math.min(reason.length(), 500)));
        if (updated != 1) {
            throw BusinessException.of("error.file.scanRejected");
        }
    }

    @Override
    public Resource load(String storedName) {
        if (storedName == null || storedName.isBlank()
                || storedName.contains("/") || storedName.contains("\\") || storedName.contains("..")) {
            throw BusinessException.of("error.file.invalidName");
        }
        if (metadataMapper != null) {
            FileSecurityMetadata metadata = metadataMapper.selectByStoredName("default", storedName);
            if (metadata == null || !"PUBLISHED".equals(metadata.getStorageState())
                    || !"CLEAN".equals(metadata.getScanStatus())) {
                throw BusinessException.of(403, "error.file.scanNotReady");
            }
        }
        Path published = publishedDir();
        Path target = published.resolve(storedName).normalize();
        if (!target.startsWith(published)) {
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

    @Override
    public boolean rescan(String storedName) {
        validateStoredName(storedName);
        if (metadataMapper == null) {
            throw BusinessException.of(403, "error.file.scanNotReady");
        }
        FileSecurityMetadata metadata = metadataMapper.selectByStoredName("default", storedName);
        if (metadata == null || "PUBLISHED".equals(metadata.getStorageState())) {
            throw BusinessException.of(404, "error.file.unknownReference");
        }
        FileKind kind;
        try {
            kind = FileKind.valueOf(metadata.getFileKind());
        } catch (RuntimeException e) {
            throw BusinessException.of(403, "error.file.scanNotReady");
        }
        Path quarantine = quarantineDir().resolve(storedName).normalize();
        Path published = publishedDir().resolve(storedName).normalize();
        if (!quarantine.startsWith(quarantineDir()) || !Files.isRegularFile(quarantine)) {
            throw BusinessException.of(404, "error.file.notFound");
        }
        FileScanResult result;
        try {
            result = fileScanner == null
                    ? FileScanResult.unavailable("scanner is not configured")
                    : fileScanner.scan(quarantine, kind);
        } catch (RuntimeException e) {
            result = FileScanResult.unavailable("scanner failed");
        }
        if (result == null || result.status() != FileScanResult.Status.CLEAN) {
            updateMetadata(metadata, "QUARANTINED",
                    result == null ? FileScanResult.Status.UNAVAILABLE.name() : result.status().name(),
                    result, result == null ? "scanner returned no result" : result.reason());
            return false;
        }
        try {
            Files.createDirectories(publishedDir());
            Files.move(quarantine, published);
            try {
                updateMetadata(metadata, "PUBLISHED", FileScanResult.Status.CLEAN.name(), result, null);
            } catch (RuntimeException e) {
                Files.move(published, quarantine);
                throw e;
            }
            return true;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw BusinessException.of("error.file.scanRejected");
        }
    }

    private void validateStoredName(String storedName) {
        if (storedName == null || storedName.isBlank()
                || storedName.contains("/") || storedName.contains("\\") || storedName.contains("..")) {
            throw BusinessException.of("error.file.invalidName");
        }
    }

    private void deleteQuietly(Path path) {
        try {
            if (path != null) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            log.warn("quarantine fileの削除に失敗しました");
        }
    }
}
