package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 条件の指紋（design §4.5）。申請の時と承認の時に、開放単位ごとに次の正規形（UTF-8、各行は LF で終わる）の SHA-256 を計算する:
 *
 * <pre>
 * fe-condition-v1
 * unit=&lt;開放単位の key&gt;
 * type=&lt;OPEN|SETTING_CHANGE&gt;
 * value=&lt;提案の値か空&gt;
 * signoff.&lt;種別&gt;=&lt;id&gt;:&lt;version&gt;   … 要る署名の種別の昇順（無ければ値は -）
 * bundle.&lt;種類&gt;=&lt;id&gt;:&lt;version&gt;    … 要る組の種類の昇順（無ければ値は -）
 * parent=&lt;key&gt;:&lt;state&gt;              … 親がある時だけ
 * </pre>
 *
 * <p>自動検査の結果（人数等）は入れない（成否は承認の時に評価し直すため、人数の増減で陳腐化させない）。承認の時の指紋が申請の時と
 * 違えば申請を {@code STALE} で終える（FE-RQ-003）。</p>
 */
public final class FeatureConditionFingerprint {

    /** 正規形の版（1行目）。 */
    public static final String VERSION = "fe-condition-v1";

    private FeatureConditionFingerprint() {
    }

    /** 状態（書込みの transaction の中で読み直した snapshot）から指紋を計算する。 */
    public static String of(FeatureEnablementCatalog catalog, Feature feature, String type, String value,
                            FeatureEnablementSnapshot snapshot) {
        return sha256(canonical(catalog, feature, type, value, snapshot));
    }

    /** 状態から正規形を作る。 */
    public static String canonical(FeatureEnablementCatalog catalog, Feature feature, String type, String value,
                                   FeatureEnablementSnapshot snapshot) {
        SortedMap<String, String> signOffs = new TreeMap<>();
        for (String kind : SignOffRequirements.applicableKinds(catalog, feature, snapshot.settings())) {
            FeatureEnablementSnapshot.ConfirmedSignOff signOff = snapshot.signOffs().get(kind);
            signOffs.put(kind, signOff == null ? "-" : signOff.id() + ":" + signOff.version());
        }
        SortedMap<String, String> bundles = new TreeMap<>();
        for (String bundleKind : SignOffRequirements.applicableBundles(catalog, feature, snapshot.settings())) {
            FeatureEnablementSnapshot.BundleView bundle = snapshot.bundles().get(bundleKind);
            bundles.put(bundleKind, bundle == null ? "-" : bundle.id() + ":" + bundle.version());
        }
        String parent = feature.parent()
                .map(p -> p.name() + ":" + snapshot.unit(p).state().name())
                .orElse(null);
        return canonical(feature.name(), type, value, signOffs, bundles, parent);
    }

    /** 正規形（試験の golden vector と同じ規則）。 */
    public static String canonical(String unitKey, String type, String value, Map<String, String> signOffs,
                                   Map<String, String> bundles, String parent) {
        StringBuilder text = new StringBuilder();
        text.append(VERSION).append('\n');
        text.append("unit=").append(unitKey).append('\n');
        text.append("type=").append(type).append('\n');
        text.append("value=").append(value == null ? "" : value).append('\n');
        new TreeMap<>(signOffs).forEach((kind, ref) -> text.append("signoff.").append(kind).append('=').append(ref)
                .append('\n'));
        new TreeMap<>(bundles).forEach((kind, ref) -> text.append("bundle.").append(kind).append('=').append(ref)
                .append('\n'));
        if (parent != null) {
            text.append("parent=").append(parent).append('\n');
        }
        return text.toString();
    }

    /** 正規形の SHA-256（小文字 hex 64 桁）。 */
    public static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 を使えません", e);
        }
    }
}
