package com.ses.featureenablement.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 段階 A〜B の間だけの暫定の拒否（prod。実装 review rb-fe-a2 d5c96f60 の P2-1・P2-2。{@link FeatureEnablementProductionValidator}
 * から呼ぶ）。FE-A11 で親・本体を DB 管理にした後も、子・設定がまだ property の源のため、開放の条件の判定と実際の挙動が食い違う2件を
 * 起動の時に止める。
 *
 * <ol>
 *   <li><b>NF01 二者確認</b>（FE-B01 で外す）: {@code FEATURE_NF01_DUAL_CONFIRMATION_ENABLED}
 *       （{@code app.features.nf01-dual-confirmation.enabled}）が true。NF01 本体は画面で運転中に開くため、子の起動時検査
 *       （{@code LifecycleDualConfirmationReleaseGate}）が親の閉じた起動で Privacy の署名の参照を見ずに通り、後から本体を開くと署名
 *       無しで二者確認が有効になる。FE-B01 が二者確認を DB 管理にし、この変数を旧い変数として正式に拒否するまで、prod では二者確認を
 *       開けない。</li>
 *   <li><b>NF02 顧客ポータルの公開範囲</b>（FE-B03 で外す）: {@code FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE}
 *       （{@code app.features.nf02-customer-portal.visible-since}）が空でない。開放の条件（棚卸しの署名 {@code NF02_PORTAL_INVENTORY}
 *       の要否）は DB の設定（段階 A では常に空）で判定するのに、実際の公開範囲は property から読むため、棚卸しの署名なしに社内で
 *       登録した問い合わせが顧客へ出る。FE-B03 が公開範囲を二人の承認の設定へ移し、この変数を旧い変数として正式に拒否するまで、prod
 *       の portal は portal 起票の問い合わせだけを出す。</li>
 * </ol>
 *
 * <p>読む範囲は旧い変数の拒否と同じ配備の設定（OS の環境変数・system property・{@code SPRING_APPLICATION_JSON}）で、名前は
 * {@link FeatureEnablementProductionValidator#nameKey} で正規化して照らす（yml の placeholder の変数名と relaxed な property 名の
 * どちらの綴りも当たる）。jar の中の {@code application*.yml} の既定値（false・空）は code で、配備の設定ではない（試験の
 * {@code application-test.yml} の値で prod の context の試験を止めないため）。既定値が変わらないことは
 * {@code FeatureEnablementProductionValidatorTest} が確かめる。照合の {@code validate-task-definition.ps1} は task definition の
 * environment・secrets で同じ2件を FAIL にする。message には変数名だけを出し、値を含めない。</p>
 *
 * <p>FE-B01・FE-B03 は、この class の該当の規則（全てを外せば class と validator の呼出し1行）を削除し、目録の DB 管理の印・設定の
 * 旧い変数の拒否に替える（tasks の FE-B01・FE-B03）。RB §1.0「段階の間の暫定の拒否」と §1.7 の注記、雛形の注記、
 * {@code validate-task-definition.ps1} の同じ2件の FAIL も同時に外す。</p>
 */
final class FeatureEnablementTransitionalRejections {

    static final String NF01_DUAL_VARIABLE = "FEATURE_NF01_DUAL_CONFIRMATION_ENABLED";
    static final String NF01_DUAL_PROPERTY = "app.features.nf01-dual-confirmation.enabled";
    static final String NF02_VISIBLE_SINCE_VARIABLE = "FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE";
    static final String NF02_VISIBLE_SINCE_PROPERTY = "app.features.nf02-customer-portal.visible-since";

    /** Spring の真偽の変換が false と読む値（これ以外は true か変換できない値。fail-closed）。 */
    private static final Set<String> FALSE_VALUES = Set.of("", "false", "off", "no", "0");
    private static final ObjectMapper JSON = new ObjectMapper();

    private FeatureEnablementTransitionalRejections() {
    }

    /** prod の暫定の違反（変数名と理由だけ。値を含めない）。 */
    static List<String> violations(Map<String, String> systemEnvironment, Properties systemProperties) {
        boolean nf01On = false;
        boolean nf02Set = false;
        Set<String> nf01Keys = Set.of(FeatureEnablementProductionValidator.nameKey(NF01_DUAL_VARIABLE),
                FeatureEnablementProductionValidator.nameKey(NF01_DUAL_PROPERTY));
        Set<String> nf02Keys = Set.of(FeatureEnablementProductionValidator.nameKey(NF02_VISIBLE_SINCE_VARIABLE),
                FeatureEnablementProductionValidator.nameKey(NF02_VISIBLE_SINCE_PROPERTY));
        for (Map.Entry<String, String> entry : deploymentValues(systemEnvironment, systemProperties)) {
            String key = FeatureEnablementProductionValidator.nameKey(entry.getKey());
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (nf01Keys.contains(key) && !FALSE_VALUES.contains(value.toLowerCase(Locale.ROOT))) {
                nf01On = true;
            }
            if (nf02Keys.contains(key) && !value.isEmpty()) {
                nf02Set = true;
            }
        }
        List<String> violations = new ArrayList<>();
        if (nf01On) {
            violations.add(NF01_DUAL_VARIABLE + "（" + NF01_DUAL_PROPERTY + "）を true にしないでください（NF01 本体は system の中で"
                    + "開くため、二者確認は FE-B01 で system の中へ移すまで prod では開けません）");
        }
        if (nf02Set) {
            violations.add(NF02_VISIBLE_SINCE_VARIABLE + "（" + NF02_VISIBLE_SINCE_PROPERTY + "）を設定しないでください（公開範囲の"
                    + "変更は FE-B03 で二人の承認へ移すまで、portal 起票の問い合わせだけを顧客へ出します）");
        }
        return violations;
    }

    /** 配備の設定の名前と値（環境変数・system property・{@code SPRING_APPLICATION_JSON} の葉）。 */
    private static List<Map.Entry<String, String>> deploymentValues(Map<String, String> systemEnvironment,
                                                                   Properties systemProperties) {
        List<Map.Entry<String, String>> values = new ArrayList<>();
        List<String> jsonSources = new ArrayList<>();
        String jsonKey = FeatureEnablementProductionValidator.nameKey(FeatureEnablementProductionValidator.JSON_VARIABLE);
        if (systemEnvironment != null) {
            systemEnvironment.forEach((name, value) -> {
                values.add(Map.entry(String.valueOf(name), value == null ? "" : value));
                if (FeatureEnablementProductionValidator.nameKey(name).equals(jsonKey) && value != null) {
                    jsonSources.add(value);
                }
            });
        }
        if (systemProperties != null) {
            for (String name : systemProperties.stringPropertyNames()) {
                String value = systemProperties.getProperty(name);
                values.add(Map.entry(name, value == null ? "" : value));
            }
            String json = systemProperties.getProperty(FeatureEnablementProductionValidator.JSON_PROPERTY);
            if (json != null) {
                jsonSources.add(json);
            }
        }
        for (String json : jsonSources) {
            try {
                collect(JSON.readTree(json), "", values);
            } catch (Exception unreadable) {
                // 読めない JSON は Spring 自身が起動の時に拒否する（旧い変数の拒否と同じ扱い）
            }
        }
        return values;
    }

    private static void collect(JsonNode node, String prefix, List<Map.Entry<String, String>> values) {
        if (node != null && node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                collect(field.getValue(), prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey(), values);
            }
        } else if (!prefix.isEmpty() && node != null) {
            values.add(Map.entry(prefix, node.isValueNode() ? (node.isNull() ? "" : node.asText()) : node.toString()));
        }
    }
}
