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
}
