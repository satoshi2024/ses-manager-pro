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

    /** ClamAV daemonの接続先。prod profileで使用する。 */
    private String scannerHost = "localhost";

    private int scannerPort = 3310;

    private int scannerConnectTimeoutMs = 2000;

    private int scannerReadTimeoutMs = 10000;
}
