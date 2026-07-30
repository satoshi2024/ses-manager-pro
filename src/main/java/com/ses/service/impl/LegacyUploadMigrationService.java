package com.ses.service.impl;

import com.ses.common.enums.FileKind;
import com.ses.config.UploadProperties;
import com.ses.entity.FileSecurityMetadata;
import com.ses.mapper.FileSecurityMetadataMapper;
import com.ses.service.FileReferenceProvider;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * quarantine/published方式の導入前にupload rootへ直接保存されていたファイルを、
 * scan・metadata登録済みの公開領域へ移行する。
 *
 * <p><b>なぜ必要か</b>: {@code FileStorageServiceImpl#load}は
 * {@code t_file_security_metadata}に{@code PUBLISHED}+{@code CLEAN}の行があり、かつ実体が
 * {@code uploads/published/}配下にあることを要求する（未知参照をfail-closedで拒否するため）。
 * V63はこのテーブルを作るだけで既存ファイルのbackfillを行わないので、移行しないと
 * 既存のスキルシート・要員写真・取込原本が一斉にダウンロード不能（403）になる。
 *
 * <p><b>なぜSQL migrationではないか</b>: 実体ファイルの移動とウイルスscanが必要で、
 * Flywayからは実行できない。
 *
 * <p>scanせずCLEAN扱いにはしない。scanner停止中・INFECTEDの実体はquarantineへ置き、
 * metadataに結果を残す。管理者は復旧後に{@code POST /api/files/{storedName}/rescan}で公開できる。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegacyUploadMigrationService {

    private static final String TENANT_ID = "default";

    private final UploadProperties uploadProperties;
    private final List<FileReferenceProvider> fileReferenceProviders;
    private final ObjectProvider<FileSecurityMetadataMapper> metadataProvider;
    private final ObjectProvider<FileScanner> scannerProvider;

    /** 移行結果。 */
    public record Result(int published, int quarantined, int skipped) {
    }

    public Result migrate() {
        FileSecurityMetadataMapper metadataMapper = metadataProvider.getIfAvailable();
        if (metadataMapper == null) {
            return new Result(0, 0, 0);
        }
        Path base = Paths.get(uploadProperties.getBasePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            return new Result(0, 0, 0);
        }
        Set<String> referenced = collectReferencedFileNames();
        if (referenced.isEmpty()) {
            return new Result(0, 0, 0);
        }

        Path quarantineDir = base.resolve("quarantine").normalize();
        Path publishedDir = base.resolve("published").normalize();
        int published = 0;
        int quarantined = 0;
        int skipped = 0;
        // 走査はupload root直下のみ。published/quarantineは対象外であり、
        // FileCleanupServiceImplの非再帰走査と同じ範囲に揃える。
        try (Stream<Path> files = Files.list(base)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String storedName = file.getFileName().toString();
                if (!referenced.contains(storedName)) {
                    // どのproviderも参照していない実体は孤児清理の担当。ここでは公開しない。
                    continue;
                }
                if (metadataMapper.selectByStoredName(TENANT_ID, storedName) != null) {
                    continue;
                }
                FileKind kind = kindOf(storedName);
                if (kind == null) {
                    // 種別を推測して公開はしない。実体はそのまま残し、運用で判断させる。
                    log.warn("既存アップロードファイルの種別を判定できないため移行を見送りました: {}", storedName);
                    skipped++;
                    continue;
                }
                try {
                    Files.createDirectories(quarantineDir);
                    Files.createDirectories(publishedDir);
                    Path quarantined0 = quarantineDir.resolve(storedName).normalize();
                    if (!quarantined0.startsWith(quarantineDir)) {
                        skipped++;
                        continue;
                    }
                    Files.move(file, quarantined0);
                    if (publish(metadataMapper, storedName, kind, quarantined0, publishedDir)) {
                        published++;
                    } else {
                        quarantined++;
                    }
                } catch (IOException | RuntimeException e) {
                    log.error("既存アップロードファイルの移行に失敗しました: {}", storedName, e);
                    skipped++;
                }
            }
        } catch (IOException e) {
            log.error("アップロードディレクトリの走査に失敗しました: {}", base, e);
        }
        if (published + quarantined + skipped > 0) {
            log.info("既存アップロードファイルを移行しました: 公開={}, 隔離={}, 見送り={}",
                    published, quarantined, skipped);
        }
        return new Result(published, quarantined, skipped);
    }

    /** CLEANならpublishedへ移して公開する。それ以外はquarantineへ残し結果だけ記録する。 */
    private boolean publish(FileSecurityMetadataMapper metadataMapper, String storedName, FileKind kind,
                            Path quarantinedFile, Path publishedDir) throws IOException {
        FileScanner scanner = scannerProvider.getIfAvailable();
        FileScanResult result;
        try {
            result = scanner == null
                    ? FileScanResult.unavailable("scanner is not configured")
                    : scanner.scan(quarantinedFile, kind);
        } catch (RuntimeException e) {
            result = FileScanResult.unavailable("scanner failed");
        }
        boolean clean = result != null && result.status() == FileScanResult.Status.CLEAN;
        FileSecurityMetadata metadata = new FileSecurityMetadata();
        metadata.setTenantId(TENANT_ID);
        metadata.setStoredName(storedName);
        metadata.setFileKind(kind.name());
        metadata.setStorageState(clean ? "PUBLISHED" : "QUARANTINED");
        metadata.setScanStatus(clean ? FileScanResult.Status.CLEAN.name()
                : result == null ? FileScanResult.Status.UNAVAILABLE.name() : result.status().name());
        metadata.setScannedAt(LocalDateTime.now());
        metadata.setScannerVersion(result == null ? null : result.scannerVersion());
        metadata.setRejectionReason(clean ? null : reason(result));
        if (clean) {
            Path target = publishedDir.resolve(storedName).normalize();
            if (!target.startsWith(publishedDir)) {
                throw new IOException("公開先のpathが不正です: " + storedName);
            }
            Files.move(quarantinedFile, target);
        }
        if (metadataMapper.insert(metadata) != 1) {
            // metadataを残せない場合は公開状態にしない。実体をquarantineへ戻す。
            if (clean) {
                Files.move(publishedDir.resolve(storedName), quarantinedFile);
            }
            throw new IOException("file metadataを登録できませんでした: " + storedName);
        }
        return clean;
    }

    private String reason(FileScanResult result) {
        String reason = result == null ? "scanner returned no result" : result.reason();
        if (!StringUtils.hasText(reason)) {
            return null;
        }
        return reason.substring(0, Math.min(reason.length(), 500));
    }

    /**
     * 拡張子からFileKindを決める。判定できない拡張子はnullを返し、移行対象から外す。
     * eml/txtはPROJECT_EMAILとBP_EMAILで拡張子・サイズ規則が同一なので、scan規則としては等価である。
     */
    private FileKind kindOf(String storedName) {
        String extension = StringUtils.getFilenameExtension(storedName);
        if (extension == null) {
            return null;
        }
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "pdf", "xlsx", "docx" -> FileKind.SKILL_SHEET;
            case "png", "jpg", "jpeg" -> FileKind.PHOTO;
            case "eml", "txt" -> FileKind.PROJECT_EMAIL;
            default -> null;
        };
    }

    private Set<String> collectReferencedFileNames() {
        Set<String> referenced = new HashSet<>();
        for (FileReferenceProvider provider : fileReferenceProviders) {
            try {
                referenced.addAll(provider.referencedFileNames());
            } catch (RuntimeException e) {
                // 1つのproviderが落ちた状態で移行を進めると、参照済みファイルを
                // 「孤児」と誤認して放置する。安全側で移行自体を中止する。
                log.error("ファイル参照の集約に失敗したため既存ファイルの移行を中止します", e);
                return Set.of();
            }
        }
        return referenced;
    }
}
