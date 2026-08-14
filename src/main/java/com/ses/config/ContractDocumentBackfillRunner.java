package com.ses.config;

import com.ses.entity.FileSecurityMetadata;
import com.ses.mapper.FileSecurityMetadataMapper;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;

/**
 * 起動時に uploads/contracts/ 配下の既存ファイル走査を行い、
 * VirusScan検証を行った上で t_file_security_metadata にバックフィル登録する。
 */
@Component
@ConditionalOnProperty(name = "app.upload.contract-backfill-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ContractDocumentBackfillRunner implements ApplicationRunner {

    private final ObjectProvider<FileSecurityMetadataMapper> metadataMapperProvider;
    private final ObjectProvider<FileScanner> fileScannerProvider;

    @Value("${app.upload.base-path:./uploads}")
    private String uploadBase;

    @Override
    public void run(ApplicationArguments args) {
        FileSecurityMetadataMapper metadataMapper = metadataMapperProvider.getIfAvailable();
        if (metadataMapper == null) {
            return;
        }

        Path contractsDir = Paths.get(uploadBase, "contracts").toAbsolutePath().normalize();
        if (!Files.exists(contractsDir) || !Files.isDirectory(contractsDir)) {
            return;
        }

        Path root = Paths.get(uploadBase).toAbsolutePath().normalize();
        try {
            Files.walkFileTree(contractsDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        backfillFile(metadataMapper, root, file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("契約ドキュメントのバックフィル走査で例外が発生しました", e);
        }
    }

    private void backfillFile(FileSecurityMetadataMapper mapper, Path root, Path file) {
        Path fileAbs = file.toAbsolutePath().normalize();
        String storedName = root.relativize(fileAbs).toString().replace('\\', '/');
        try {
            FileSecurityMetadata existing = mapper.selectByStoredName("default", storedName);
            if (existing == null) {
                Long ownerId = extractOwnerId(file);
                FileScanner scanner = fileScannerProvider.getIfAvailable();
                FileScanResult result = null;
                if (scanner != null) {
                    try {
                        result = scanner.scan(file, com.ses.common.enums.FileKind.SKILL_SHEET);
                    } catch (RuntimeException e) {
                        result = FileScanResult.unavailable("scanner failed");
                    }
                } else {
                    result = FileScanResult.unavailable("scanner is not configured");
                }

                FileSecurityMetadata metadata = new FileSecurityMetadata();
                metadata.setTenantId("default");
                metadata.setStoredName(storedName);
                metadata.setFileKind("CONTRACT_DOCUMENT");
                metadata.setOwnerType("CONTRACT_DOCUMENT");
                metadata.setOwnerId(ownerId);
                metadata.setCreatedBy(com.ses.common.util.SecurityUtils.currentUserId());
                metadata.setCreatedAt(LocalDateTime.now());
                metadata.setUpdatedAt(LocalDateTime.now());

                if (result != null && result.status() == FileScanResult.Status.CLEAN) {
                    metadata.setStorageState("PUBLISHED");
                    metadata.setScanStatus("CLEAN");
                } else {
                    metadata.setStorageState("QUARANTINED");
                    metadata.setScanStatus(result != null ? result.status().name() : "UNAVAILABLE");
                    metadata.setRejectionReason(result != null ? result.reason() : "scanner returned no result");
                }
                mapper.insert(metadata);
            }
        } catch (Exception e) {
            log.warn("契約ファイルのバックフィル登録に失敗しました: {}", storedName, e);
        }
    }

    private Long extractOwnerId(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                return Long.parseLong(parent.getFileName().toString());
            }
        } catch (Exception e) {
            // numeric owner ID not parseable
        }
        return null;
    }
}
