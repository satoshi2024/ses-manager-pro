package com.ses.web;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.SignOffKindClass;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.privacy.production.retention.hold.PrivacyHoldDecisions;
import com.ses.privacy.production.retention.hold.PrivacyHoldGate;
import com.ses.privacy.production.retention.hold.PrivacyHoldJournal;
import com.ses.privacy.production.retention.hold.PrivacyHoldTargetType;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationEnablementConditions;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * <p>RB-POSTLAUNCH E-3・P3-B で次を足した。</p>
 * <ul>
 *   <li>fallback は「第2引数がある」だけでなく、連結した値そのもの（{@code String(値)}・括弧は同じとみなす）であること。
 *       値以外へ fallback する箇所は、その式を台帳（{@link Entry#fallbackTo}）に書いたものだけを認める。
 *       第2引数が配列（文言の引数）なら fallback ではない。</li>
 *   <li>変数に入れた接頭辞（{@code const P = 'x.'; t(P + 値)}）と、別名関数を経由した連結（{@code t} を呼ぶ小さな関数:
 *       {@code h('x.' + 値)} のように key を渡すものと、{@code label('x.', 値)} のように接頭辞と値を渡して中で連結するもの）
 *       も検出する。別名の fallback は関数の中の第2引数を呼出しの実引数で置き換えて比べる。</li>
 *   <li>接頭辞を解決できない連結（関数の外から来た変数や式 + 値）は台帳で値域を持てないため、検出して失敗させる。</li>
 * </ul>
 *
 * <p>限界: 別名は同じfile内の小さな関数（本体が約400文字以内）だけを見る。他のfileで定義した関数、{@code call}・
 * {@code apply}・object の property 経由の呼出し、2段以上の関数を通して渡した接頭辞は検出しない。{@code common.js} の
 * {@code SES.i18n.e}（{@code 'enum.' + group + '.' + code}）は {@code EnumMessageTranslationTest} の範囲。</p>
 */
class ComposedMessageKeyCoverageTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Path MODULES = RESOURCES.resolve("static/js/modules");
    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final List<String> BUNDLES = List.of("messages.properties", "messages_en.properties",
            "messages_ko.properties", "messages_zh_CN.properties");
    /** 文言の呼出し: t / this.t / SES.i18n.t。 */
    private static final Pattern T_CALL = Pattern.compile("(?:SES\\.i18n\\.t|this\\.t|(?<![\\w.$])t)\\(");
    /** 接頭辞の定数: {@code const P = 'x.y.';}。 */
    private static final Pattern PREFIX_CONSTANT = Pattern.compile(
            "(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(['\"])([A-Za-z][A-Za-z0-9_.]*\\.)\\2\\s*[;,\\n]");
    private static final Pattern LITERAL_PREFIX = Pattern.compile(
            "(['\"])([A-Za-z][A-Za-z0-9_.]*\\.)\\1\\s*\\+\\s*(.+)", Pattern.DOTALL);
    private static final Pattern IDENTIFIER_PREFIX = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*\\+\\s*(.+)", Pattern.DOTALL);
    private static final Pattern PREFIX_LITERAL = Pattern.compile("(['\"])([A-Za-z][A-Za-z0-9_.]*\\.)\\1");
    private static final Pattern TEMPLATE_KEY = Pattern.compile("`([^`$]*)\\$\\{.*", Pattern.DOTALL);
    private static final Pattern IDENTIFIER = Pattern.compile("(?<![\\w$.])[A-Za-z_$][\\w$]*");
    /** 別名関数の定義（function 宣言・function 式・arrow）。group(1)=名前、group(2)=引数。 */
    private static final List<Pattern> ALIAS_DEFINITIONS = List.of(
            Pattern.compile("function\\s+([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)\\s*\\{"),
            Pattern.compile("([A-Za-z_$][\\w$]*)\\s*[:=]\\s*function\\s*\\(([^)]*)\\)\\s*\\{"),
            Pattern.compile("([A-Za-z_$][\\w$]*)\\s*=\\s*\\(([^)]*)\\)\\s*=>"),
            Pattern.compile("([A-Za-z_$][\\w$]*)\\s*=\\s*([A-Za-z_$][\\w$]*)\\s*=>"));
    /** 別名とみなす関数の本体の上限（文字数）。 */
    private static final int ALIAS_BODY_LIMIT = 400;
    private static final Pattern MIGRATION_FILE = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__.+\\.sql$");

    /**
     * 連結箇所（file・接頭辞・連結した値の式・第2引数の式・template literal か・経由した別名・行）。
     * {@code fallback} は第2引数の式（無ければ null）。
     */
    private record Site(String file, String prefix, String operand, String fallback, boolean template, String via,
                        int line) {
        String key() {
            return file + "|" + prefix;
        }

        String where() {
            return file + ":" + line + (via == null ? "" : " (" + via + "() 経由)");
        }
    }

    /** 台帳の1件: 値域（values が null ならfallback扱いで reason が必須）。値以外の fallback は式を書く。 */
    private record Entry(Set<String> values, String reason, String expectedFallback) {
        static Entry domain(Set<String> values, String source) {
            return new Entry(values, source, null);
        }

        /** 値そのものへ fallback する箇所。 */
        static Entry fallback(String reason) {
            return new Entry(null, reason, null);
        }

        /** 値以外（値から作る表示・入力欄の文言等）へ fallback する箇所。その式を書く。 */
        static Entry fallbackTo(String reason, String expression) {
            return new Entry(null, reason, expression);
        }

        /**
         * 値域を持ち、第2引数が値そのものでない箇所（値以外への fallback か、文言の位置引数の件数等）。値域の key は4言語に要り、
         * 第2引数はその式に限る。
         */
        static Entry domainWith(Set<String> values, String source, String secondArgument) {
            return new Entry(values, source, secondArgument);
        }
    }

    /** 1 file の走査結果。 */
    private record Scan(List<Site> sites, List<String> unresolved) {
    }

    @Test
    void 画面部品の連結keyはすべて台帳にあり値域のkeyは4言語にあり値域の無いものは値へfallbackする() throws Exception {
        Scan scan = scan();
        List<Site> sites = scan.sites();
        Map<String, Entry> registry = registry();

        assertThat(scan.unresolved())
                .as("接頭辞を解決できない連結（関数の外から来た変数・式 + 値）。台帳で値域を持てないため、接頭辞を文字列literalか"
                        + "同じfileの定数にする")
                .isEmpty();
        Set<String> scanned = new TreeSet<>();
        sites.forEach(site -> scanned.add(site.key()));
        assertThat(sites).as("連結箇所を読めていること").hasSizeGreaterThanOrEqualTo(50);
        assertThat(sites.stream().filter(site -> site.via() != null).count())
                .as("別名関数を経由した連結を読めていること").isGreaterThanOrEqualTo(15);
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
            if (site.operand() != null && hasTopLevelPlus(site.operand())) {
                problems.add(site.where() + " " + site.prefix() + " は値の後にも連結している（接頭辞 × 値の台帳で表せない）");
            }
            if (site.fallback() != null && !acceptableFallback(site, entry)) {
                problems.add(site.where() + " " + site.prefix() + " のfallback「" + site.fallback()
                        + "」が値そのもの（" + site.operand() + "）でも台帳の式でもない");
            }
            if (entry.values() == null) {
                if (site.fallback() == null) {
                    problems.add(site.where() + " " + site.prefix()
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
                        problems.add(bundle + ": " + key + "（" + site.where() + "）");
                    }
                });
            }
        }
        // 台帳に書いた値以外のfallbackの式が今も使われていること（古い台帳を残さない）
        registry.forEach((key, entry) -> {
            if (entry.expectedFallback() != null && sites.stream().noneMatch(site -> site.key().equals(key)
                    && site.fallback() != null
                    && normalize(site.fallback()).equals(normalize(entry.expectedFallback())))) {
                problems.add(key + " の台帳のfallbackの式「" + entry.expectedFallback() + "」が使われていない");
            }
        });
        assertThat(problems).as("連結keyの欠落・fallback無し・値以外へのfallback").isEmpty();
    }

    /** 走査そのものの確認（別名関数・変数の接頭辞・fallbackの比較・解決できない連結）。 */
    @Test
    void 走査は別名関数と変数の接頭辞の連結も検出し値以外のfallbackと解決できない連結を見分ける() {
        String code = String.join("\n",
                "const PREFIX = 'a.b.';",
                "function lbl(prefix, value) { return t(prefix + value, String(value)); }",
                "const h = (k, fb) => SES.i18n.t(k, fb);",
                "x = t(PREFIX + v, v);",
                "y = lbl('c.d.', row.kind);",
                "z = h('e.f.' + u, u);",
                "w = this.t('g.h.' + s, [s]);",
                "q = t(dynamic + '.x');",
                "p = SES.i18n.t(`i.j.${k}`);",
                "");
        Scan scan = scanFile("sample.js", code);
        Map<String, Site> byPrefix = new TreeMap<>();
        scan.sites().forEach(site -> byPrefix.put(site.prefix(), site));
        assertThat(byPrefix.keySet()).containsExactly("a.b.", "c.d.", "e.f.", "g.h.", "i.j.");
        assertThat(byPrefix.get("c.d.").via()).isEqualTo("lbl");
        assertThat(byPrefix.get("c.d.").operand()).isEqualTo("row.kind");
        assertThat(byPrefix.get("e.f.").via()).isEqualTo("h");
        assertThat(byPrefix.get("i.j.").template()).isTrue();
        Entry valueFallback = Entry.fallback("値");
        assertThat(acceptableFallback(byPrefix.get("a.b."), valueFallback)).isTrue();
        assertThat(acceptableFallback(byPrefix.get("c.d."), valueFallback)).as("String(値) は値と同じ").isTrue();
        assertThat(acceptableFallback(byPrefix.get("e.f."), valueFallback)).isTrue();
        assertThat(acceptableFallback(byPrefix.get("g.h."), valueFallback)).as("配列は文言の引数でfallbackではない").isFalse();
        assertThat(scan.unresolved()).hasSize(1).allMatch(entry -> entry.contains("dynamic"));
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
        // 機能の開放の画面（feature-enablement-console FE-A10。段階 A〜D の整合で台帳に足した）
        String fe = "feature-enablement.js|featureEnablement.";
        Set<String> decisionActions = new TreeSet<>();
        jsObjectKeys("feature-enablement.js", "DECISION_DIALOG_KEYS")
                .forEach(kind -> decisionActions.add(kind.replaceFirst("^(signOff|bundle)", "").toLowerCase(Locale.ROOT)));
        registry.put(fe + "btn.", Entry.domain(decisionActions,
                "feature-enablement.js の DECISION_DIALOG_KEYS の操作（kind から signOff・bundle を除いて小文字にした値）"));
        registry.put(fe + "toast.", Entry.domain(decisionActions, "同上（署名・組の確認・却下・取下げ・取消しの toast）"));
        registry.put(fe + "closeWarning.", Entry.domainWith(
                Set.of(LifecycleDualConfirmationEnablementConditions.WARNING_ACTIVE_DUAL_TEMPLATES),
                "FeatureEnablementCloseWarningSource の実装が返す code（今は NF01 二者確認の有効な template の件数だけ）。"
                        + "第2引数は fallback ではなく文言の {0} の件数（数値のため SES.i18n.t は fallback にしない）",
                "warning.count"));
        registry.put(fe + "unitName.", Entry.domainWith(enumNames(Feature.values()),
                "Feature（開放単位の key）。key が欠けた時は画面が渡す表示名か key へ fallback する", "fallback || key"));
        registry.put(fe + "setting.label.", Entry.domain(checkValues("ck_fe_setting_key"),
                "t_feature_enablement_setting.setting_key の CHECK（V203）"));
        registry.put(fe + "state.", Entry.domain(checkValues("ck_fe_state_value"),
                "t_feature_enablement_state.state の CHECK（V203）"));
        registry.put(fe + "requestStatus.", Entry.domain(checkValues("ck_fe_request_status"),
                "t_feature_enablement_request.status の CHECK（V203）"));
        registry.put(fe + "requestType.", Entry.domain(checkValues("ck_fe_request_type"),
                "t_feature_enablement_request.request_type の CHECK（V203）"));
        registry.put(fe + "signOffStatus.", Entry.domain(checkValues("ck_fe_sign_off_status"),
                "t_feature_sign_off.status の CHECK（V203。組の status はこの部分集合）"));
        registry.put(fe + "kindClass.", Entry.domain(enumNames(SignOffKindClass.values()), "SignOffKindClass"));
        registry.put(fe + "category.", Entry.domain(enumNames(FeatureConditionCheck.Category.values()),
                "FeatureConditionCheck.Category"));
        registry.put(fe + "checkStatus.", Entry.domain(enumNames(FeatureConditionCheck.Status.values()),
                "FeatureConditionCheck.Status"));
        registry.put(fe + "scope.", Entry.domain(enumNames(FeatureConditionCheck.Scope.values()),
                "FeatureConditionCheck.Scope"));
        registry.put(fe + "purpose.", Entry.domain(enumNames(FeatureEvaluationContext.Purpose.values()),
                "FeatureEvaluationContext.Purpose"));
        registry.put(fe + "closeReason.", Entry.domain(new TreeSet<>(FeatureEnablementCatalog.MANUAL_CLOSE_REASONS),
                "FeatureEnablementCatalog.MANUAL_CLOSE_REASONS（catalog の manualCloseReasons）"));
        Set<String> signerRoles = new TreeSet<>();
        FeatureEnablementCatalog.standard().kinds().forEach(kind -> signerRoles.addAll(kind.signerRoles()));
        registry.put(fe + "role.", Entry.domain(signerRoles, "本番の目録の署名の種別の signerRoles の和"));
        Set<String> signOffReasons = new TreeSet<>(jsArray("feature-enablement.js", "SIGN_OFF_REASONS"));
        signOffReasons.addAll(jsArray("feature-enablement.js", "REVOKE_REASONS"));
        registry.put(fe + "signOffReason.", Entry.domain(signOffReasons,
                "feature-enablement.js の SIGN_OFF_REASONS・REVOKE_REASONS（却下・取消しの理由の選択肢）"));

        // ---- 値域を台帳で持たない箇所（第2引数で値そのものへfallbackする。keyが欠けると訳されない値が出るが、key名は出ない）
        String statusFallback = "DBの状態名（日本語の値）へfallbackする。状態名の訳の網羅は本testの範囲外";
        registry.put("ai-evaluation.js|ai.evaluation.useCase.", Entry.fallback("use case のcodeへfallbackする"));
        registry.put("ai-evaluation.js|ai.evaluation.field.", Entry.fallbackTo("metrics のkeyの末尾要素へfallbackする",
                "key.split('.').pop()"));
        registry.put("contract-compliance.js|doc.title.", Entry.fallback("文書種別のcodeへfallbackする"));
        registry.put("engineer-change-requests.js|changeRequest.type.", Entry.fallback("申請種別のcodeへfallbackする"));
        registry.put("expense-management.js|expense.status.", Entry.fallback(statusFallback));
        registry.put("invoice.js|invoice.status.", Entry.fallback(statusFallback));
        registry.put("my-expenses.js|my.expense.status.", Entry.fallback(statusFallback));
        registry.put("my-payroll.js|my.payroll.calcStatus.", Entry.fallback("freeeの計算状態のcodeへfallbackする"));
        registry.put("my-payroll.js|my.payroll.category.", Entry.fallback("給与明細の区分のcodeへfallbackする"));
        registry.put("my-profile.js|my.changeRequest.type.", Entry.fallback("申請種別のcodeへfallbackする"));
        registry.put("my-profile.js|my.changeRequest.career.", Entry.fallbackTo("入力欄のplaceholder文言へfallbackする",
                "ph"));
        registry.put("my-timesheet.js|workRecord.status.", Entry.fallback(statusFallback));
        registry.put("privacy-dsar.js|privacyDsar.readiness.", Entry.fallback("readiness のcodeへfallbackする"));
        registry.put("quotation.js|quotation.status.", Entry.fallback(statusFallback));
        registry.put("work-record.js|workRecord.status.", Entry.fallback(statusFallback));
        // privacy-dsar.js の label(prefix, value) は t(prefix + value, String(value)) で値そのものへfallbackする
        // （RB-POSTLAUNCH E-3 で別名関数の走査を足して見えるようになった箇所）
        for (String prefix : List.of("type.", "status.", "channel.", "hold.", "resolution.", "packageStatus.",
                "redaction.", "manualReason.", "deliveryMethod.", "reason.", "identity.", "delivery.", "subjectType.",
                "link.")) {
            registry.put("privacy-dsar.js|privacyDsar." + prefix,
                    Entry.fallback("DSARの列挙値のcodeへfallbackする（label() 経由）"));
        }
        // 機能の開放の画面: 閉鎖の理由は system の理由・手動の理由・条件の code が混ざり、値域を1つの正本から読めない
        registry.put("feature-enablement.js|featureEnablement.reason.", Entry.fallback(
                "closed_reason の code（system の理由・MANUAL_*・条件の code）へfallbackする"));
        registry.put("feature-enablement.js|featureEnablement.requiredStatus.", Entry.fallback(
                "要る署名の状態（FeatureEnablementQueryService が文字列で作る）のcodeへfallbackする（label() 経由）"));
        registry.put("feature-enablement.js|featureEnablement.subject.", Entry.fallback(
                "履歴の subject_type（V203 は CHECK を持たない）のcodeへfallbackする（label() 経由）"));
        return registry;
    }

    // ---------------------------------------------------------------- scan

    private static Scan scan() throws IOException {
        List<Site> sites = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        try (Stream<Path> files = Files.list(MODULES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".js")).sorted().toList()) {
                Scan scan = scanFile(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
                sites.addAll(scan.sites());
                unresolved.addAll(scan.unresolved());
            }
        }
        return new Scan(sites, unresolved);
    }

    /** 別名関数（t を呼ぶ小さな関数）。key を渡す引数の位置、接頭辞の連結なら値の引数、関数の中の第2引数。 */
    private record Alias(String name, List<String> params, int keyIndex, String valueParam, String fallback) {
    }

    private static Scan scanFile(String name, String code) {
        List<Site> sites = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        Map<String, String> constants = new TreeMap<>();
        Matcher constant = PREFIX_CONSTANT.matcher(code);
        while (constant.find()) {
            constants.put(constant.group(1), constant.group(3));
        }

        List<Alias> aliases = new ArrayList<>();
        Set<Integer> aliasInternalCalls = new HashSet<>();
        for (Pattern definition : ALIAS_DEFINITIONS) {
            Matcher matcher = definition.matcher(code);
            while (matcher.find()) {
                String alias = matcher.group(1);
                if ("t".equals(alias)) {
                    continue; // t( の呼出しは直接走査する
                }
                List<String> params = new ArrayList<>();
                for (String param : matcher.group(2).split(",")) {
                    String cleaned = param.replaceAll("=.*$", "").replace("...", "").trim();
                    if (!cleaned.isEmpty()) {
                        params.add(cleaned);
                    }
                }
                int bodyStart = matcher.end();
                int bodyEnd;
                if (code.charAt(bodyStart - 1) == '{') {
                    bodyEnd = matchingWithin(code, bodyStart - 1);
                } else {
                    bodyStart = skipSpaces(code, bodyStart);
                    if (bodyStart < code.length() && code.charAt(bodyStart) == '{') {
                        bodyEnd = matchingWithin(code, bodyStart);
                        bodyStart++;
                    } else {
                        bodyEnd = code.indexOf('\n', bodyStart);
                        bodyEnd = bodyEnd < 0 ? code.length() : bodyEnd;
                    }
                }
                if (bodyEnd < 0) {
                    continue; // 本体が長い（別名の小さな関数ではない）
                }
                String body = code.substring(bodyStart, bodyEnd);
                Matcher call = T_CALL.matcher(body);
                while (call.find()) {
                    List<String> args = arguments(body, call.end() - 1);
                    if (args == null || args.isEmpty()) {
                        continue;
                    }
                    String key = args.get(0);
                    String fallback = args.size() > 1 ? args.get(1) : null;
                    Matcher concat = IDENTIFIER_PREFIX.matcher(key);
                    if (params.contains(key)) {
                        aliases.add(new Alias(alias, params, params.indexOf(key), null, fallback));
                        aliasInternalCalls.add(bodyStart + call.start());
                    } else if (concat.matches() && params.contains(concat.group(1))
                            && params.contains(concat.group(2).trim())) {
                        aliases.add(new Alias(alias, params, params.indexOf(concat.group(1)), concat.group(2).trim(),
                                fallback));
                        aliasInternalCalls.add(bodyStart + call.start());
                    }
                }
            }
        }

        Matcher call = T_CALL.matcher(code);
        while (call.find()) {
            if (aliasInternalCalls.contains(call.start())) {
                continue;
            }
            List<String> args = arguments(code, call.end() - 1);
            if (args == null) {
                unresolved.add(name + ":" + line(code, call.start()) + " 括弧が閉じていない呼出し");
                continue;
            }
            analyzeKey(name, code, call.start(), args.isEmpty() ? "" : args.get(0),
                    args.size() > 1 ? args.get(1) : null, null, constants, sites, unresolved);
        }

        for (Alias alias : aliases) {
            Matcher use = Pattern.compile("(?<![\\w$])" + Pattern.quote(alias.name()) + "\\s*\\(").matcher(code);
            while (use.find()) {
                int open = use.end() - 1;
                int close = matchingParen(code, open);
                if (close < 0) {
                    continue;
                }
                int next = skipSpaces(code, close + 1);
                if (code.startsWith("=>", next) || (next < code.length() && code.charAt(next) == '{')) {
                    continue; // 定義（function name(...) { / name = (...) => / name(...) {）は呼出しではない
                }
                List<String> args = arguments(code, open);
                if (args == null || args.size() <= alias.keyIndex()) {
                    continue;
                }
                String argument = args.get(alias.keyIndex());
                String fallback = substitute(alias.fallback(), alias.params(), args);
                if (alias.valueParam() == null) {
                    analyzeKey(name, code, use.start(), argument, fallback, alias.name(), constants, sites, unresolved);
                    continue;
                }
                int valueIndex = alias.params().indexOf(alias.valueParam());
                String operand = valueIndex < args.size() ? args.get(valueIndex) : "undefined";
                Matcher literal = PREFIX_LITERAL.matcher(argument);
                String prefix = literal.matches() ? literal.group(2) : constants.get(argument);
                if (prefix == null) {
                    unresolved.add(name + ":" + line(code, use.start()) + " " + alias.name() + "(" + argument + ", ...)");
                } else {
                    sites.add(new Site(name, prefix, operand, fallback, false, alias.name(), line(code, use.start())));
                }
            }
        }
        return new Scan(sites, unresolved);
    }

    /** key の引数を読み、連結なら台帳の箇所に、接頭辞を解決できない連結なら unresolved に足す。 */
    private static void analyzeKey(String name, String code, int offset, String key, String fallback, String via,
                                   Map<String, String> constants, List<Site> sites, List<String> unresolved) {
        int line = line(code, offset);
        Matcher template = TEMPLATE_KEY.matcher(key);
        if (template.matches()) {
            sites.add(new Site(name, template.group(1), null, null, true, via, line));
            return;
        }
        Matcher literal = LITERAL_PREFIX.matcher(key);
        if (literal.matches()) {
            sites.add(new Site(name, literal.group(2), literal.group(3).trim(), fallback, false, via, line));
            return;
        }
        Matcher identifier = IDENTIFIER_PREFIX.matcher(key);
        if (identifier.matches() && constants.containsKey(identifier.group(1))) {
            sites.add(new Site(name, constants.get(identifier.group(1)), identifier.group(2).trim(), fallback, false,
                    via, line));
            return;
        }
        if (hasTopLevelPlus(key)) {
            unresolved.add(name + ":" + line + " " + key.replaceAll("\\s+", " "));
        }
    }

    /** fallback が値そのもの（String(値)・括弧は同じ）か、台帳に書いた式か。配列（文言の引数）は fallback ではない。 */
    private static boolean acceptableFallback(Site site, Entry entry) {
        String fallback = normalize(site.fallback());
        if (fallback.startsWith("[")) {
            return false;
        }
        return fallback.equals(normalize(site.operand()))
                || (entry.expectedFallback() != null && fallback.equals(normalize(entry.expectedFallback())));
    }

    /** 空白を除き、全体を囲む括弧と String(...) を外す。 */
    private static String normalize(String expression) {
        if (expression == null) {
            return "";
        }
        String value = expression.replaceAll("\\s+", "");
        while (true) {
            if (value.startsWith("String(") && matchingParen(value, 6) == value.length() - 1) {
                value = value.substring(7, value.length() - 1);
            } else if (value.startsWith("(") && matchingParen(value, 0) == value.length() - 1) {
                value = value.substring(1, value.length() - 1);
            } else {
                return value;
            }
        }
    }

    /** 別名の中の式の引数名を、呼出しの実引数（括弧付き）へ置き換える。 */
    private static String substitute(String template, List<String> params, List<String> args) {
        if (template == null) {
            return null;
        }
        Matcher matcher = IDENTIFIER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            int index = params.indexOf(matcher.group());
            String replacement = index >= 0 && index < args.size() ? "(" + args.get(index) + ")" : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 呼出しの実引数（最上位の , で区切る。文字列・括弧の中の , は区切らない）。括弧が閉じなければ null。 */
    private static List<String> arguments(String code, int open) {
        int close = matchingParen(code, open);
        if (close < 0) {
            return null;
        }
        List<String> args = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = open + 1;
        for (int i = open + 1; i < close; i++) {
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
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(code.substring(start, i).trim());
                start = i + 1;
            }
        }
        String last = code.substring(start, close).trim();
        if (!last.isEmpty() || !args.isEmpty()) {
            args.add(last);
        }
        return args;
    }

    private static boolean hasTopLevelPlus(String expression) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
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
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == '+' && depth == 0) {
                return true;
            }
        }
        return false;
    }

    /** 対応する閉じ括弧（( [ {）の位置。閉じなければ -1。文字列の中の括弧は数えない。 */
    private static int matchingParen(String code, int open) {
        char opening = code.charAt(open);
        char closing = opening == '(' ? ')' : opening == '[' ? ']' : '}';
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
        return -1;
    }

    /** 別名の本体（{@link #ALIAS_BODY_LIMIT} 文字以内で閉じるもの）の閉じ括弧の位置。閉じなければ -1。 */
    private static int matchingWithin(String code, int open) {
        return matchingParen(code.substring(0, Math.min(code.length(), open + ALIAS_BODY_LIMIT)), open);
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

    /** JS の {@code const NAME = { key: {…}, … };} の第1階層の key（値が object の行）。 */
    private static Set<String> jsObjectKeys(String file, String name) throws IOException {
        String code = Files.readString(MODULES.resolve(file), StandardCharsets.UTF_8);
        Matcher block = Pattern.compile("(?:const|let|var)\\s+" + name + "\\s*=\\s*\\{(.*?)\\n\\s*\\};", Pattern.DOTALL)
                .matcher(code);
        assertThat(block.find()).as(file + " の " + name).isTrue();
        Set<String> keys = new TreeSet<>();
        Matcher key = Pattern.compile("(?m)^\\s*([A-Za-z_$][\\w$]*)\\s*:\\s*\\{").matcher(block.group(1));
        while (key.find()) {
            keys.add(key.group(1));
        }
        assertThat(keys).as(file + " の " + name + " の key").isNotEmpty();
        return keys;
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
