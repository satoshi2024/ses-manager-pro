package com.ses.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ファイルアップロード設定（app.upload.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.upload")
public class UploadProperties {

    /** 保存先ベースディレクトリ（既定: ./uploads） */
    private String basePath = "./uploads";

    /** 孤児清理の安全マージン（時間）。 */
    private int cleanupSafetyHours = 24;

    /** falseの場合はscanner unavailableとして全uploadを拒否する。 */
    private boolean scannerEnabled = true;

    /**
     * 起動時にquarantine/published導入前の既存ファイルをscan・metadata登録して移行するか。
     * 移行済みの環境では何もしないため通常は有効のままにする。
     */
    private boolean legacyMigrationEnabled = true;

    /** ClamAV daemonの接続先。prod profileで使用する。 */
    private String scannerHost = "localhost";

    private int scannerPort = 3310;

    private int scannerConnectTimeoutMs = 2000;

    private int scannerReadTimeoutMs = 10000;

    /** サービスデスク添付の上限（バイト）。 */
    private long serviceRequestMaxFileSizeBytes = 10 * 1024 * 1024L;

    /** 内部サービスデスク添付の1ユーザーあたり1分間の受付上限。0以下は無制限。 */
    private int serviceRequestUploadsPerMinute = 60;
}
