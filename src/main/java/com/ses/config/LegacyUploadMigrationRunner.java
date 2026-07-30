package com.ses.config;

import com.ses.service.impl.LegacyUploadMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 起動時に、quarantine/published方式導入前のupload rootへ直接保存されたファイルを
 * scan・metadata登録済みの状態へ移行する。
 *
 * <p>移行しないと{@code FileStorageServiceImpl#load}のfail-closed判定により
 * 既存ファイルが全て403になる。処理は冪等で、metadataが既にある実体は触らない。
 * 起動を止めないよう、失敗はlogだけ残す。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "app.upload.legacy-migration-enabled", matchIfMissing = true)
public class LegacyUploadMigrationRunner implements ApplicationRunner {

    private final LegacyUploadMigrationService legacyUploadMigrationService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            legacyUploadMigrationService.migrate();
        } catch (RuntimeException e) {
            log.error("既存アップロードファイルの移行に失敗しました。"
                    + "既存ファイルのダウンロードは復旧まで拒否されます", e);
        }
    }
}
