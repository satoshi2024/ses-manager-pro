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

    /** 
     * 主要Linuxディストリビューションの日本語フォント標準インストールパス。
     * Windows環境でのテスト時は application.yml の app.pdf.font-path で明示的に指定すること。
     */
    private java.util.List<String> defaultFontCandidates = java.util.List.of(
            "/usr/share/fonts/opentype/ipafont-gothic/ipag.ttf",
            "/usr/share/fonts/truetype/fonts-japanese-gothic.ttf",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc,0",
            "/usr/share/fonts/truetype/noto/NotoSansJP-Regular.ttf"
    );
}
