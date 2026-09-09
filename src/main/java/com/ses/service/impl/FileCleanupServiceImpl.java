package com.ses.service.impl;

import com.ses.config.UploadProperties;
import com.ses.service.FileCleanupService;
import com.ses.service.FileReferenceProvider;
import com.ses.service.scheduler.TenantAwareBatchRunner;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * アップロード済みファイルの孤児清理サービス実装。
 * FileReferenceProvider の全実装を集約して参照集合を構築する。
 * 新機能がファイルを追加する際は FileReferenceProvider を1つ実装することで
 * 清理対象から自動的に除外される（足し忘れ防止）。
 */
@Slf4j
@Service
public class FileCleanupServiceImpl implements FileCleanupService {

    private final UploadProperties uploadProperties;
    private final List<FileReferenceProvider> fileReferenceProviders;
    private final TenantAwareBatchRunner tenantAwareBatchRunner;

    /** 既存の単体テスト用。通常のSpring生成経路はtenant runner付きコンストラクタを使用する。 */
    public FileCleanupServiceImpl(UploadProperties uploadProperties,
                                  List<FileReferenceProvider> fileReferenceProviders) {
        this(uploadProperties, fileReferenceProviders, null);
    }

    @Autowired
    public FileCleanupServiceImpl(UploadProperties uploadProperties,
                                  List<FileReferenceProvider> fileReferenceProviders,
                                  TenantAwareBatchRunner tenantAwareBatchRunner) {
        this.uploadProperties = uploadProperties;
        this.fileReferenceProviders = fileReferenceProviders;
        this.tenantAwareBatchRunner = tenantAwareBatchRunner;
    }

    @Override
    public int cleanupOrphanFiles() {
        Path base = Paths.get(uploadProperties.getBasePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            return 0;
        }

        Set<String> referenced = collectTenantAwareReferencedFileNames();
        Instant safeBefore = Instant.now().minus(uploadProperties.getCleanupSafetyHours(), ChronoUnit.HOURS);
        int deleted = 0;

        try (Stream<Path> roots = Stream.of(base, base.resolve("quarantine"), base.resolve("published"))) {
            List<Path> directories = roots.filter(Files::isDirectory).toList();
            for (Path directory : directories) {
                try (Stream<Path> files = Files.list(directory)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        String fileName = file.getFileName().toString();
                        if (referenced.contains(fileName)) {
                            continue;
                        }
                        try {
                            Instant lastModified = Files.getLastModifiedTime(file).toInstant();
                            if (lastModified.isAfter(safeBefore)) {
                                continue;
                            }
                            Files.delete(file);
                            deleted++;
                            log.info("孤児ファイルを削除しました: {}", fileName);
                        } catch (IOException e) {
                            log.warn("孤児ファイルの削除に失敗しました: {}", fileName, e);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("アップロードディレクトリの走査に失敗しました: {}", base, e);
        }

        return deleted;
    }

    /**
     * 全 FileReferenceProvider から参照ファイル名を集約する。
     */
    private Set<String> collectReferencedFileNames() {
        Set<String> referenced = new HashSet<>();
        for (FileReferenceProvider provider : fileReferenceProviders) {
            referenced.addAll(provider.referencedFileNames());
        }
        return referenced;
    }

    private Set<String> collectTenantAwareReferencedFileNames() {
        if (tenantAwareBatchRunner == null) {
            return collectReferencedFileNames();
        }
        Set<String> referenced = new HashSet<>();
        tenantAwareBatchRunner.run(tenantId -> referenced.addAll(collectReferencedFileNames()));
        return referenced;
    }
}
