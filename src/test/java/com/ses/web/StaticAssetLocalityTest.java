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
}
