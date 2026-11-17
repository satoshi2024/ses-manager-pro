package com.ses.common.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code enum.*}（DB値の表示名。{@code SES.i18n.e} と template が使う）の訳の検査（RB-I18N）。
 *
 * <ul>
 *   <li>en・ko・zh_CN の値が日本語の値をそのまま写していない。ko・zh_CN の契約状態・ロール・性別・提案状態等が日本語のまま
 *       残っており、{@code MessageBundleConsistencyTest} は値の言語を見ないため検出できなかった。</li>
 *   <li>同じ画面に並ぶenumと画面のkey（看板の列名と移動後のtoast、メールテンプレートの選択肢と一覧のbadge等）が、
 *       全言語で同じ訳語である（CLAUDE.md Internationalization の「同じ画面でserverとclientの文言が違う」の防止）。</li>
 * </ul>
 *
 * <p>限界: 「ja と同じでない」だけでは、日本語を簡体字へ転写しただけの誤訳（例: 「要員」を簡体字に置き換えただけの値。漢語では「要人」の意になる）は
 * 検出できない。訳語の妥当性は翻訳レビューで確かめる。</p>
 */
class EnumMessageTranslationTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    /** 全言語で同じ表記が正しい値（略語・ランク記号）。追加するときは理由が言えるものだけにする。 */
    private static final Set<String> SAME_IN_ALL_LANGUAGES = Set.of(
            "enum.userRole.hr",
            "enum.customerTrustLevel.S", "enum.customerTrustLevel.A", "enum.customerTrustLevel.B",
            "enum.customerTrustLevel.C",
            "enum.employmentType.bp",
            "enum.skillCategory.fw", "enum.skillCategory.db", "enum.skillCategory.os");

    /** zh_CN で日本語と同じ字形が正しい漢語である値（key → 理由）。en・ko には同じ扱いを認めない。 */
    private static final Map<String, String> ZH_SAME_AS_JA = Map.of(
            "enum.engineerStatus.proposing", "「提案中」は漢語でも提案している最中の意。画面の engineer.status.proposed と同じ訳語",
            "enum.emailTemplateType.proposal", "「提案」は漢語でも提案の意。メールテンプレートの選択肢 emailTemplate.type.proposal と同じ訳語",
            "enum.contractType.dispatch", "「派遣」は漢語の労務派遣の語。契約形態の選択肢 contract.contractType.dispatch と同じ訳語");

    /** 同じ画面に並ぶ enum key と画面の key。全言語で同じ値にする。 */
    private static final Map<String, String> SAME_SCREEN_PAIRS = Map.ofEntries(
            // 提案看板: 列名（kanban.status.*）と移動後のtoast（SES.i18n.e('proposalStatus')）
            Map.entry("enum.proposalStatus.doc_screening", "kanban.status.document"),
            Map.entry("enum.proposalStatus.first_interview", "kanban.status.interview1"),
            Map.entry("enum.proposalStatus.second_interview", "kanban.status.interview2"),
            Map.entry("enum.proposalStatus.waiting", "kanban.status.waiting"),
            Map.entry("enum.proposalStatus.won", "kanban.status.won"),
            Map.entry("enum.proposalStatus.lost", "kanban.status.lost"),
            // メールテンプレート: モーダルの選択肢と一覧のbadge
            Map.entry("enum.emailTemplateType.proposal", "emailTemplate.type.proposal"),
            Map.entry("enum.emailTemplateType.interview", "emailTemplate.type.interview"),
            Map.entry("enum.emailTemplateType.thanks", "emailTemplate.type.thanks"),
            Map.entry("enum.emailTemplateType.followup", "emailTemplate.type.followup"),
            // 契約一覧: 絞り込み・フォームの選択肢とbadge
            Map.entry("enum.contractStatus.draft", "contract.status.preparing"),
            Map.entry("enum.contractStatus.active", "contract.status.active"),
            Map.entry("enum.contractStatus.completed", "contract.status.ended"),
            Map.entry("enum.contractStatus.cancelled", "contract.status.cancelled"),
            Map.entry("enum.contractType.ses", "contract.contractType.quasiMandate"),
            Map.entry("enum.contractType.contract", "contract.contractType.contract"),
            Map.entry("enum.contractType.dispatch", "contract.contractType.dispatch"),
            // 顧客の活動履歴: 登録フォームの選択肢とbadge
            Map.entry("enum.customerActivityType.meeting", "customer.activity.type.meeting"),
            Map.entry("enum.customerActivityType.visit", "customer.activity.type.visit"),
            Map.entry("enum.customerActivityType.phone", "customer.activity.type.phone"),
            Map.entry("enum.customerActivityType.email", "customer.activity.type.email"),
            Map.entry("enum.customerActivityType.other", "customer.activity.type.other"),
            // 要員一覧・案件一覧・スキル・ユーザー管理: 絞り込み・フォームの選択肢とbadge
            Map.entry("enum.employmentType.regular", "engineer.employmentType.fulltime"),
            Map.entry("enum.employmentType.contract", "engineer.employmentType.contract"),
            Map.entry("enum.engineerStatus.active", "engineer.status.active"),
            Map.entry("enum.engineerStatus.bench", "engineer.status.bench"),
            Map.entry("enum.engineerStatus.proposing", "engineer.status.proposed"),
            Map.entry("enum.projectStatus.open", "project.status.open"),
            Map.entry("enum.projectStatus.selecting", "project.status.screening"),
            Map.entry("enum.projectStatus.filled", "project.status.filled"),
            Map.entry("enum.projectStatus.closed", "project.status.closed"),
            Map.entry("enum.skillCategory.lang", "skill.category.language"),
            Map.entry("enum.skillCategory.tool", "skill.category.tool"),
            Map.entry("enum.skillCategory.other", "skill.category.other"),
            Map.entry("enum.userRole.admin", "user.role.admin"),
            Map.entry("enum.userRole.sales", "user.role.sales"),
            Map.entry("enum.userRole.hr", "user.role.hr"),
            Map.entry("enum.userRole.manager", "user.role.manager"));

    private static final List<String> LANGUAGES = List.of("en", "ko", "zh_CN");

    @Test
    void enumの表示名はen_ko_zhCNで日本語の値を写さない() throws IOException {
        Properties ja = load("messages.properties");
        List<String> copied = new ArrayList<>();
        int checked = 0;
        for (String lang : LANGUAGES) {
            Properties bundle = load("messages_" + lang + ".properties");
            for (String key : ja.stringPropertyNames()) {
                if (!key.startsWith("enum.") || SAME_IN_ALL_LANGUAGES.contains(key)
                        || ("zh_CN".equals(lang) && ZH_SAME_AS_JA.containsKey(key))) {
                    continue;
                }
                checked++;
                String value = bundle.getProperty(key);
                if (value != null && value.equals(ja.getProperty(key))) {
                    copied.add(lang + ": " + key + "=" + value);
                }
            }
        }
        assertThat(checked).isGreaterThan(100);
        assertThat(copied).as("日本語の値を写したenumの表示名").isEmpty();
    }

    @Test
    void 除外は理由のある値だけで実際に日本語と同じ表記である() throws IOException {
        Properties ja = load("messages.properties");
        for (String lang : LANGUAGES) {
            Properties bundle = load("messages_" + lang + ".properties");
            for (String key : SAME_IN_ALL_LANGUAGES) {
                assertThat(ja.getProperty(key)).as(key).matches("[A-Z]{1,3}");
                assertThat(bundle.getProperty(key)).as(lang + ": " + key).isEqualTo(ja.getProperty(key));
            }
        }
        Properties zh = load("messages_zh_CN.properties");
        ZH_SAME_AS_JA.forEach((key, reason) -> {
            assertThat(reason).as(key + " の理由").isNotBlank();
            assertThat(zh.getProperty(key)).as("zh_CN: " + key).isEqualTo(ja.getProperty(key));
        });
    }

    @Test
    void 同じ画面に並ぶenumと画面のkeyは全言語で同じ訳語である() throws IOException {
        List<String> diverged = new ArrayList<>();
        for (String lang : List.of("ja", "en", "ko", "zh_CN")) {
            Properties bundle = load("ja".equals(lang) ? "messages.properties" : "messages_" + lang + ".properties");
            SAME_SCREEN_PAIRS.forEach((enumKey, screenKey) -> {
                String enumValue = bundle.getProperty(enumKey);
                String screenValue = bundle.getProperty(screenKey);
                assertThat(enumValue).as(lang + ": " + enumKey).isNotNull();
                assertThat(screenValue).as(lang + ": " + screenKey).isNotNull();
                if (!enumValue.equals(screenValue)) {
                    diverged.add(lang + ": " + enumKey + "=" + enumValue + " / " + screenKey + "=" + screenValue);
                }
            });
        }
        assertThat(diverged).as("同じ画面で訳語が分かれている").isEmpty();
    }

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(RESOURCES.resolve(name), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
