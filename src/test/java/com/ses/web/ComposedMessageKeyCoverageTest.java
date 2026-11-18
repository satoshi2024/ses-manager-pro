package com.ses.web;

import com.ses.privacy.production.retention.hold.PrivacyHoldDecisions;
import com.ses.privacy.production.retention.hold.PrivacyHoldGate;
import com.ses.privacy.production.retention.hold.PrivacyHoldJournal;
import com.ses.privacy.production.retention.hold.PrivacyHoldTargetType;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-PRERUN 追加 P3-4: 画面部品（{@code static/js/modules/*.js}）が値を連結して文言keyを作る箇所
 * （{@code this.t('prefix.' + 値)}・{@code SES.i18n.t('prefix.' + 値)}・{@code t('prefix.' + 値)}・template literal）を
 * すべて列挙し、台帳（{@link #registry()}）に載っていることを確かめる。
 *
 * <ul>
 *   <li>値域を持つ箇所: 値はAPIが返しうる値の正本（DBのCHECK制約・Javaのenum／定数・JSの定数）から読み、
 *       接頭辞 × 値のkeyが4言語の全bundleにあること（欠けると画面にkey名がそのまま出る。RB-PRERUN 6 の
 *       {@code lifecycle.dual.decision.REJECTED} と同じ型の不具合）。</li>
 *   <li>値域を読めない箇所: 第2引数で値そのものへfallbackしていること（keyが欠けてもkey名は出ず値が出る）を確かめ、
 *       理由を台帳に書く。fallbackの無い連結を値域なしで台帳に載せることはできない。</li>
 *   <li>台帳に無い連結を足す、または台帳の箇所が無くなると失敗する（列挙の漏れと古い台帳の両方を防ぐ）。</li>
 * </ul>
 *
 * <p>限界: 変数に入れた接頭辞の連結（{@code t(prefix + 値)}）と、{@code t} / {@code SES.i18n.t} 以外の名前の関数経由の
 * 連結は検出しない。{@code common.js} の {@code SES.i18n.e}（{@code 'enum.' + group + '.' + code}）は
 * {@code EnumMessageTranslationTest} の範囲。</p>
 */
class ComposedMessageKeyCoverageTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Path MODULES = RESOURCES.resolve("static/js/modules");
    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final List<String> BUNDLES = List.of("messages.properties", "messages_en.properties",
            "messages_ko.properties", "messages_zh_CN.properties");
    /** 連結の呼出し: t / this.t / SES.i18n.t の第1引数が「文字列literal + 値」。 */
    private static final Pattern CONCAT_CALL = Pattern.compile(
            "(?:SES\\.i18n\\.t|this\\.t|(?<![\\w.$])t)\\(\\s*(['\"])([A-Za-z][A-Za-z0-9_.]*\\.)\\1\\s*\\+\\s*");
    /** 連結の呼出し: 第1引数が template literal で値を埋め込む。 */
    private static final Pattern TEMPLATE_CALL = Pattern.compile(
            "(?:SES\\.i18n\\.t|this\\.t|(?<![\\w.$])t)\\(\\s*`([^`$]*)\\$\\{");
    private static final Pattern MIGRATION_FILE = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__.+\\.sql$");

    /** 連結箇所（file・接頭辞・fallbackの有無・行）。 */
    private record Site(String file, String prefix, boolean fallback, boolean template, int line) {
        String key() {
            return file + "|" + prefix;
        }
    }

    /** 台帳の1件: 値域（values が null ならfallback扱いで reason が必須）。 */
    private record Entry(Set<String> values, String reason) {
        static Entry domain(Set<String> values, String source) {
            return new Entry(values, source);
        }

        static Entry fallback(String reason) {
            return new Entry(null, reason);
        }
    }

    @Test
    void 画面部品の連結keyはすべて台帳にあり値域のkeyは4言語にあり値域の無いものは値へfallbackする() throws Exception {
        List<Site> sites = scan();
        Map<String, Entry> registry = registry();

        Set<String> scanned = new TreeSet<>();
        sites.forEach(site -> scanned.add(site.key()));
        assertThat(sites).as("連結箇所を読めていること").hasSizeGreaterThanOrEqualTo(30);
        assertThat(new TreeSet<>(registry.keySet()))
                .as("連結箇所の台帳（新しい連結は値域かfallbackの理由を付けて台帳へ足す。消した連結は台帳から外す）")
                .isEqualTo(scanned);

        List<String> problems = new ArrayList<>();
        Map<String, Properties> bundles = new LinkedHashMap<>();
        for (String bundle : BUNDLES) {
            bundles.put(bundle, load(bundle));
        }
        for (Site site : sites) {
            Entry entry = registry.get(site.key());
            assertThat(entry.reason()).as(site.key() + " の根拠").isNotBlank();
            if (entry.values() == null) {
                if (!site.fallback()) {
                    problems.add(site.file() + ":" + site.line() + " " + site.prefix()
                            + " は値域が無いのに第2引数のfallbackも無い（keyが欠けるとkey名が画面に出る）");
                }
                continue;
            }
            assertThat(entry.values()).as(site.key() + " の値域").isNotEmpty();
            for (String value : entry.values()) {
                String key = site.prefix() + value;
                bundles.forEach((bundle, properties) -> {
                    String message = properties.getProperty(key);
                    if (message == null || message.isBlank()) {
                        problems.add(bundle + ": " + key + "（" + site.file() + ":" + site.line() + "）");
                    }
                });
            }
        }
        assertThat(problems).as("連結keyの欠落・fallback無し").isEmpty();
    }

    /**
     * 連結箇所の台帳。値域は正本から読む。fallbackの理由は「値が無いとき画面に出るもの」を書く。
     */
    private static Map<String, Entry> registry() throws Exception {
        Map<String, Entry> registry = new TreeMap<>();
        // ---- 値域を正本から読む箇所（fallback無し。欠けるとkey名が画面に出る）
        Set<String> dualStates = new TreeSet<>(checkValues("ck_dual_projection_state"));
        dualStates.add("none"); // 提出が無いとき画面部品自身が選ぶkey
        registry.put("lifecycle-dual.js|lifecycle.dual.state.", Entry.domain(dualStates,
                "t_lifecycle_dual_projection.state の CHECK（V195）と画面の none"));
        registry.put("lifecycle-dual.js|lifecycle.dual.decision.", Entry.domain(
                checkValues("ck_dual_confirmation_decision"), "t_lifecycle_dual_confirmation.decision の CHECK（V195）"));
        Set<String> emptyReasons = javaStringAssignments("com/ses/controller/api/EngineerAccountLinkApiController.java",
                "emptyReason");
        emptyReasons.add(jsStringDefault("engineer-account-link.js", "emptyReason"));
        registry.put("engineer-account-link.js|engineer.accountLink.empty.", Entry.domain(emptyReasons,
                "EngineerAccountLinkApiController の emptyReason の代入値と画面の既定値"));
        registry.put("import-center.js|importCenter.source.status.", Entry.domain(
                checkValues("chk_import_source_status"), "t_import_source_system.status の CHECK（V184）"));
        registry.put("import-center.js|importCenter.field.", Entry.domain(jsArray("import-center.js", "CUSTOMER_FIELDS"),
                "import-center.js の CUSTOMER_FIELDS（SesImportV1Schema と同順）"));
        registry.put("privacy-holds.js|privacyHold.gate.", Entry.domain(enumNames(PrivacyHoldGate.State.values()),
                "PrivacyHoldGate.State（overview.gateState）"));
        registry.put("privacy-holds.js|privacyHold.journal.", Entry.domain(
                enumNames(PrivacyHoldJournal.Availability.values()), "PrivacyHoldJournal.Availability（overview.journalAvailability）"));
        registry.put("privacy-holds.js|privacyHold.prereq.", Entry.domain(holdPrerequisiteCodes(),
                "PrivacyHoldDecisions.evaluate() の REASON_D05 / REASON_TARGET_TYPES / REASON_MAX_SKEW と"
                        + " PrivacyHoldService の journal（画面は最初の '.' を '_' へ置換する）"));
        registry.put("privacy-holds.js|privacyHold.target.", Entry.domain(enumNames(PrivacyHoldTargetType.values()),
                "PrivacyHoldTargetType（hold.targetType・overview.allowedTargetTypes）"));
        registry.put("privacy-holds.js|privacyHold.status.", Entry.domain(checkValues("chk_legal_hold_status"),
                "t_legal_hold.status の CHECK（V190）"));
        registry.put("privacy-holds.js|privacyHold.choice.", Entry.domain(
                jsConstants("privacy-holds.js", "CHOICE_ADD", "CHOICE_ACCEPT"), "privacy-holds.js の CHOICE_ADD / CHOICE_ACCEPT"));
        registry.put("lifecycle.js|lifecycle.evidenceType.", Entry.domain(lifecycleEvidenceTypes(),
                "LifecycleTemplateServiceImpl.EVIDENCE_TYPES（テンプレートの保存で許す証跡種別。タスクはテンプレートから写す。"
                        + "RB-POSTLAUNCH B-1）"));

        // ---- 値域を台帳で持たない箇所（第2引数で値そのものへfallbackする。keyが欠けると訳されない値が出るが、key名は出ない）
        String statusFallback = "DBの状態名（日本語の値）へfallbackする。状態名の訳の網羅は本testの範囲外";
        registry.put("ai-evaluation.js|ai.evaluation.useCase.", Entry.fallback("use case のcodeへfallbackする"));
        registry.put("ai-evaluation.js|ai.evaluation.field.", Entry.fallback("metrics のkeyの末尾要素へfallbackする"));
        registry.put("contract-compliance.js|doc.title.", Entry.fallback("文書種別のcodeへfallbackする"));
        registry.put("engineer-change-requests.js|changeRequest.type.", Entry.fallback("申請種別のcodeへfallbackする"));
        registry.put("expense-management.js|expense.status.", Entry.fallback(statusFallback));
        registry.put("invoice.js|invoice.status.", Entry.fallback(statusFallback));
        registry.put("my-expenses.js|my.expense.status.", Entry.fallback(statusFallback));
        registry.put("my-payroll.js|my.payroll.calcStatus.", Entry.fallback("freeeの計算状態のcodeへfallbackする"));
        registry.put("my-payroll.js|my.payroll.category.", Entry.fallback("給与明細の区分のcodeへfallbackする"));
        registry.put("my-profile.js|my.changeRequest.type.", Entry.fallback("申請種別のcodeへfallbackする"));
        registry.put("my-profile.js|my.changeRequest.career.", Entry.fallback("入力欄のplaceholder文言へfallbackする"));
        registry.put("my-timesheet.js|workRecord.status.", Entry.fallback(statusFallback));
        registry.put("privacy-dsar.js|privacyDsar.readiness.", Entry.fallback("readiness のcodeへfallbackする"));
        registry.put("quotation.js|quotation.status.", Entry.fallback(statusFallback));
        registry.put("work-record.js|workRecord.status.", Entry.fallback(statusFallback));
        return registry;
    }

    // ---------------------------------------------------------------- scan

    private static List<Site> scan() throws IOException {
        List<Site> sites = new ArrayList<>();
        try (Stream<Path> files = Files.list(MODULES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".js")).sorted().toList()) {
                String name = file.getFileName().toString();
                String code = Files.readString(file, StandardCharsets.UTF_8);
                Matcher concat = CONCAT_CALL.matcher(code);
                while (concat.find()) {
                    int end = skipOperand(code, concat.end());
                    int next = skipSpaces(code, end);
                    char after = next < code.length() ? code.charAt(next) : ')';
                    sites.add(new Site(name, concat.group(2), after == ',', false, line(code, concat.start())));
                }
                Matcher template = TEMPLATE_CALL.matcher(code);
                while (template.find()) {
                    sites.add(new Site(name, template.group(1), false, true, line(code, template.start())));
                }
            }
        }
        return sites;
    }

    /** 連結の右辺（識別子・member・呼出し・括弧式）を読み飛ばした位置。 */
    private static int skipOperand(String code, int start) {
        int index = start;
        if (index < code.length() && code.charAt(index) == '(') {
            return matchingParen(code, index) + 1;
        }
        while (index < code.length()) {
            char c = code.charAt(index);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.') {
                index++;
            } else if (c == '(' || c == '[') {
                index = matchingParen(code, index) + 1;
            } else {
                break;
            }
        }
        return index;
    }

    private static int matchingParen(String code, int open) {
        char opening = code.charAt(open);
        char closing = opening == '(' ? ')' : ']';
        int depth = 0;
        char quote = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == opening) {
                depth++;
            } else if (c == closing && --depth == 0) {
                return i;
            }
        }
        throw new IllegalStateException("括弧が閉じていません: offset=" + open);
    }

    private static int skipSpaces(String code, int index) {
        while (index < code.length() && Character.isWhitespace(code.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int line(String code, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    // ---------------------------------------------------------------- 値域の正本

    /** migration の CHECK 制約 {@code CONSTRAINT name CHECK (col IN (...))} の値（最も新しい定義）。 */
    private static Set<String> checkValues(String constraint) throws IOException {
        Pattern definition = Pattern.compile("CONSTRAINT\\s+" + constraint + "\\s+CHECK\\s*\\(\\s*`?\\w+`?\\s+IN\\s*\\(([^)]*)\\)",
                Pattern.CASE_INSENSITIVE);
        String latest = null;
        MigrationVersion latestVersion = null;
        try (Stream<Path> files = Files.list(RESOURCES.resolve("db/migration"))) {
            for (Path file : files.toList()) {
                Matcher name = MIGRATION_FILE.matcher(file.getFileName().toString());
                if (!name.matches()) {
                    continue;
                }
                Matcher check = definition.matcher(Files.readString(file, StandardCharsets.UTF_8));
                String found = null;
                while (check.find()) {
                    found = check.group(1);
                }
                MigrationVersion version = MigrationVersion.fromVersion(name.group(1).replace('_', '.'));
                if (found != null && (latestVersion == null || version.compareTo(latestVersion) > 0)) {
                    latest = found;
                    latestVersion = version;
                }
            }
        }
        assertThat(latest).as(constraint + " のCHECK制約をmigrationから読めること").isNotNull();
        return quoted(latest, '\'');
    }

    /** Java source の {@code variable = "VALUE"} の値。 */
    private static Set<String> javaStringAssignments(String source, String variable) throws IOException {
        String code = Files.readString(MAIN_JAVA.resolve(source), StandardCharsets.UTF_8);
        Set<String> values = new TreeSet<>();
        Matcher matcher = Pattern.compile("\\b" + variable + "\\s*=\\s*\"([^\"]+)\"").matcher(code);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        assertThat(values).as(source + " の " + variable + " の代入値").isNotEmpty();
        return values;
    }

    /** JS の {@code (data.data && data.data.variable) || 'DEFAULT'} の既定値。 */
    private static String jsStringDefault(String file, String variable) throws IOException {
        String code = Files.readString(MODULES.resolve(file), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("\\." + variable + "\\)\\s*\\|\\|\\s*'([^']+)'").matcher(code);
        assertThat(matcher.find()).as(file + " の " + variable + " の既定値").isTrue();
        return matcher.group(1);
    }

    /** JS の {@code const NAME = [ '...', ... ];} の要素。 */
    private static Set<String> jsArray(String file, String name) throws IOException {
        String code = Files.readString(MODULES.resolve(file), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("(?:const|let|var)\\s+" + name + "\\s*=\\s*\\[([^\\]]*)\\]").matcher(code);
        assertThat(matcher.find()).as(file + " の " + name).isTrue();
        return quoted(matcher.group(1), '\'');
    }

    /** JS の {@code var NAME = 'VALUE';} の値。 */
    private static Set<String> jsConstants(String file, String... names) throws IOException {
        String code = Files.readString(MODULES.resolve(file), StandardCharsets.UTF_8);
        Set<String> values = new TreeSet<>();
        for (String name : names) {
            Matcher matcher = Pattern.compile("(?:const|let|var)\\s+" + name + "\\s*=\\s*'([^']+)'").matcher(code);
            assertThat(matcher.find()).as(file + " の " + name).isTrue();
            values.add(matcher.group(1));
        }
        return values;
    }

    /** 保全hold画面の前提code（画面は最初の '.' を '_' へ置換して key にする）。 */
    private static Set<String> holdPrerequisiteCodes() throws ReflectiveOperationException {
        Set<String> codes = new TreeSet<>();
        for (String field : List.of("REASON_D05", "REASON_TARGET_TYPES", "REASON_MAX_SKEW")) {
            Field declared = PrivacyHoldDecisions.class.getDeclaredField(field);
            declared.setAccessible(true);
            codes.add(((String) declared.get(null)).replaceFirst("\\.", "_"));
        }
        codes.add("journal");
        return codes;
    }

    /** ライフサイクルの証跡種別の許可値（{@code LifecycleTemplateServiceImpl.EVIDENCE_TYPES}、package private）。 */
    @SuppressWarnings("unchecked")
    private static Set<String> lifecycleEvidenceTypes() throws ReflectiveOperationException {
        Field declared = Class.forName("com.ses.service.lifecycle.impl.LifecycleTemplateServiceImpl")
                .getDeclaredField("EVIDENCE_TYPES");
        declared.setAccessible(true);
        return new TreeSet<>((Set<String>) declared.get(null));
    }

    private static Set<String> enumNames(Enum<?>[] values) {
        Set<String> names = new TreeSet<>();
        Arrays.stream(values).forEach(value -> names.add(value.name()));
        return names;
    }

    private static Set<String> quoted(String text, char quote) {
        Set<String> values = new TreeSet<>();
        Matcher matcher = Pattern.compile(quote + "([^" + quote + "]+)" + quote).matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static Properties load(String bundle) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(RESOURCES.resolve(bundle), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
