package com.ses.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * フロントエンドライブラリがローカル配信であることの検証。
 *
 * <p>以前は Bootstrap・jQuery・Chart.js 等を CDN から読み込んでいた。日本企業の閉じた
 * ネットワークではこれらのドメインが遮断されていることがあり、その場合 jQuery が読めず
 * 一覧が真っ白、Bootstrap が読めずモーダルが開かない——つまり「表示が崩れる」ではなく
 * <b>システム全体が使用不能</b>になる。CDN障害でも同じことが起きる。
 * そのため全て {@code static/lib} 配下へ同梱した。この状態を戻さないよう固定する。
 */
class StaticAssetLocalityTest {

    /** テンプレートから外部ホストを読み込んでいないか（画像等も含めて全て禁止）。 */
    private static final Pattern EXTERNAL_ASSET = Pattern.compile(
            "(?:src|href)\\s*=\\s*[\"'](https?://[^\"']+)[\"']");

    /** テンプレートが参照するローカルアセット。 */
    private static final Pattern LOCAL_LIB = Pattern.compile(
            "(?:src|href)\\s*=\\s*[\"'](/lib/[^\"']+)[\"']");

    private List<Resource> templates() throws Exception {
        return List.of(new PathMatchingResourcePatternResolver()
                .getResources("classpath:templates/**/*.html"));
    }

    private String read(Resource r) throws Exception {
        try (InputStream in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void テンプレートは外部CDNからアセットを読み込まない() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Resource r : templates()) {
            Matcher m = EXTERNAL_ASSET.matcher(read(r));
            while (m.find()) {
                offenders.add(r.getFilename() + " -> " + m.group(1));
            }
        }
        assertTrue(offenders.isEmpty(),
                "外部ホストからのアセット読み込みが残っています。閉域網・CDN障害時に画面が機能しなくなるため、"
                        + "static/lib へ同梱してローカル参照にしてください: " + offenders);
    }

    /**
     * 自前CSSの外部参照（{@code @import} / {@code url()}）。
     *
     * <p>テンプレート走査だけでは漏れる。実際 common.css の
     * {@code @import url('…fonts.googleapis.com…')} がこの穴をすり抜けていた。
     * CSS の {@code @import} は解決までレンダリングをブロックするため、
     * 閉域網では接続タイムアウトまで全ページが白画面になる（link より重い障害）。
     */
    private static final Pattern EXTERNAL_CSS_REF = Pattern.compile(
            "(?:@import\\s+|url\\s*\\(\\s*)[\"']?(https?://[^\"')\\s]+)");

    @Test
    void 自前CSSは外部ホストを参照しない() throws Exception {
        List<String> offenders = new ArrayList<>();
        Resource[] sheets = new PathMatchingResourcePatternResolver()
                .getResources("classpath:static/css/**/*.css");
        for (Resource r : sheets) {
            Matcher m = EXTERNAL_CSS_REF.matcher(read(r));
            while (m.find()) {
                offenders.add(r.getFilename() + " -> " + m.group(1));
            }
        }
        assertTrue(offenders.isEmpty(),
                "CSSからの外部参照が残っています。@import は描画をブロックするため閉域網で白画面になります: "
                        + offenders);
    }

    @Test
    void テンプレートが参照する_libのファイルが実在する() throws Exception {
        Set<String> referenced = new LinkedHashSet<>();
        for (Resource r : templates()) {
            Matcher m = LOCAL_LIB.matcher(read(r));
            while (m.find()) {
                referenced.add(m.group(1));
            }
        }
        assertTrue(!referenced.isEmpty(), "テンプレートが /lib のアセットを1つも参照していません");

        List<String> missing = new ArrayList<>();
        for (String path : referenced) {
            String cleaned = path.split("[?#]")[0];
            Resource res = new PathMatchingResourcePatternResolver()
                    .getResource("classpath:static" + cleaned);
            if (!res.exists()) {
                missing.add(cleaned);
            }
        }
        assertTrue(missing.isEmpty(), "参照されている同梱アセットが存在しません: " + missing);
    }

    /** 素の {@code src}/{@code href} と、{@code th:src}/{@code th:href} の {@code @{...}} リンク式の両方を拾う。 */
    private static final Pattern ASSET_ATTRIBUTE = Pattern.compile(
            "\\s(th:src|th:href|src|href)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern LINK_EXPRESSION_PATH = Pattern.compile("^@\\{(/[^(}]*)");
    /** 静的ファイルではなくcontrollerが返すjs（locale別の文言）。 */
    private static final Set<String> DYNAMIC_ASSETS = Set.of("/js/i18n.js");

    /**
     * templateと静的HTMLが参照する同梱資源（{@code /lib/}・{@code /js/}・{@code /css/}・{@code /portal/js/} 等、static直下の
     * ディレクトリ・ファイルで始まり拡張子を持つpath）が実在すること。
     *
     * <p>上の {@code /lib} の検査は素の {@code src="/lib/…"} だけを見ており、{@code th:href="@{/lib/…}"} を見ていなかった。
     * そのため顧客ポータル全画面のBootstrap参照（{@code /lib/bootstrap/css/…}・{@code /lib/bootstrap/js/…}）が実体と違って
     * 404のまま残り、モーダル・{@code d-none} が効かなかった（RB-I18N）。classpathではなくsrcを読むのは、削除したtemplateが
     * {@code target/classes} に残っていても誤検出しないため。</p>
     */
    @Test
    void templateと静的HTMLが参照する同梱資源は全て実在する() throws Exception {
        java.nio.file.Path resources = java.nio.file.Path.of("src/main/resources");
        java.nio.file.Path staticRoot = resources.resolve("static");
        Set<String> staticTop = new java.util.TreeSet<>();
        try (java.util.stream.Stream<java.nio.file.Path> top = java.nio.file.Files.list(staticRoot)) {
            top.forEach(path -> staticTop.add(path.getFileName().toString()));
        }
        List<java.nio.file.Path> pages = new ArrayList<>();
        for (java.nio.file.Path root : List.of(resources.resolve("templates"), staticRoot)) {
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root)) {
                walk.filter(path -> path.toString().endsWith(".html")).forEach(pages::add);
            }
        }
        int checked = 0;
        List<String> missing = new ArrayList<>();
        for (java.nio.file.Path page : pages) {
            Matcher attribute = ASSET_ATTRIBUTE.matcher(java.nio.file.Files.readString(page, StandardCharsets.UTF_8));
            while (attribute.find()) {
                String value = attribute.group(2).trim();
                String path;
                if (attribute.group(1).startsWith("th:")) {
                    Matcher expression = LINK_EXPRESSION_PATH.matcher(value);
                    if (!expression.find()) {
                        continue;
                    }
                    path = expression.group(1);
                } else if (value.startsWith("/") && !value.startsWith("//")) {
                    path = value;
                } else {
                    continue;
                }
                path = path.split("[?#]")[0];
                String[] segments = path.split("/");
                if (path.contains("{") || segments.length < 2 || !staticTop.contains(segments[1])
                        || !path.matches(".*\\.[A-Za-z0-9]+$") || DYNAMIC_ASSETS.contains(path)) {
                    continue;
                }
                checked++;
                if (!java.nio.file.Files.isRegularFile(staticRoot.resolve(path.substring(1)))) {
                    missing.add(resources.relativize(page).toString().replace('\\', '/') + " -> " + path);
                }
            }
        }
        assertTrue(checked > 100, "検査した参照が少なすぎます（抽出の不具合）: " + checked);
        assertTrue(missing.isEmpty(), "参照先の同梱資源が存在しません（404になります）: " + missing);
    }
}
