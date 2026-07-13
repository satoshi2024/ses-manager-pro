package com.ses.service.impl;

import com.ses.config.UploadProperties;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.service.FileCleanupService;
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
 * 参照元は現時点で t_engineer.photo_url / t_proposal.skill_sheet_path の2列のみ。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileCleanupServiceImpl implements FileCleanupService {

    private final UploadProperties uploadProperties;
    private final EngineerMapper engineerMapper;
    private final ProposalMapper proposalMapper;

    @Override
    public int cleanupOrphanFiles() {
        Path base = Paths.get(uploadProperties.getBasePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            return 0;
        }

        Set<String> referenced = collectReferencedFileNames();
        Instant safeBefore = Instant.now().minus(uploadProperties.getCleanupSafetyHours(), ChronoUnit.HOURS);
        int deleted = 0;

        try (Stream<Path> files = Files.list(base)) {
            List<Path> candidates = files.filter(Files::isRegularFile).toList();
            for (Path file : candidates) {
                String fileName = file.getFileName().toString();
                if (referenced.contains(fileName)) {
                    continue;
                }
                try {
                    Instant lastModified = Files.getLastModifiedTime(file).toInstant();
                    if (lastModified.isAfter(safeBefore)) {
                        continue; // アップロード直後・DB紐付け前かもしれないためスキップ
                    }
                    Files.delete(file);
                    deleted++;
                    log.info("孤児ファイルを削除しました: {}", fileName);
                } catch (IOException e) {
                    log.warn("孤児ファイルの削除に失敗しました: {}", fileName, e);
                }
            }
        } catch (IOException e) {
            log.error("アップロードディレクトリの走査に失敗しました: {}", base, e);
        }

        return deleted;
    }

    private Set<String> collectReferencedFileNames() {
        Set<String> referenced = new HashSet<>();
        for (String photoUrl : engineerMapper.selectAllPhotoUrls()) {
            if (photoUrl != null && !photoUrl.isBlank()) {
                referenced.add(photoUrl);
            }
        }
        for (String skillSheetPath : proposalMapper.selectAllSkillSheetPaths()) {
            if (skillSheetPath != null && !skillSheetPath.isBlank()) {
                referenced.add(skillSheetPath);
            }
        }
        return referenced;
    }
}
