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

    /**
     * 孤児ファイル清理の安全マージン（時間）。
     * アップロード直後、DBへの紐付け（PUT /api/engineers 等）が完了するまでの
     * 短い時間差でファイルだけ存在する状態と誤判定しないよう、
     * 最終更新からこの時間が経過したファイルのみ削除対象にする。
     */
    private int cleanupSafetyHours = 24;
}
