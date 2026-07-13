package com.ses.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PDF生成設定（app.pdf.*）。
 * OpenPDFで日本語を描画するには埋め込み用のCJKフォントファイルが必要。
 * 未設定の場合は主要Linuxディストリビューションでよく利用される
 * 日本語フォントパッケージ（fonts-japanese-gothic 等）のパスを順に試す。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.pdf")
public class PdfProperties {

    /** 明示的に指定するCJKフォントファイルパス（空なら既定候補を順に探索） */
    private String fontPath = "";
}
