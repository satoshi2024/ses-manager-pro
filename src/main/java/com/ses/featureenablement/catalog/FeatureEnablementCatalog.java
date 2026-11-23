package com.ses.featureenablement.catalog;

import com.ses.config.feature.Feature;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 機能の開放の目録（feature-enablement-console design §4.1。code の正本で、文書は requirements §6）。
 *
 * <p>開放単位ごとに、親・再起動の要否・NF07 の印・要る署名の種別と類・条件になる鍵・固有の評価器の要否・DB 管理の印・開放の時だけの
 * 条件と開放の遷移の前提の code・副入口・出処を持つ。署名の種別ごとに、類（決定・証拠）・署名者の役割・参照の規則・構造化された値の
 * 項目・属する組を持つ。値（決定 ID・digest・鍵）は持たない。</p>
 *
 * <p>DB 管理の印（{@link UnitDefinition#databaseManaged()}）は段階の間の印で、印の無い開放単位は {@code database} の源でも
 * property の値を返す（design §3.1）。段階 A は容易な7件（{@link #STAGE_A_DATABASE_MANAGED}。FE-A11）に印を付け、段階 B〜D が
 * 残りに付け、段階 E で印を削除する。試験は {@link #withDatabaseManaged} で印を付け直した写しを使う。</p>
 */
public final class FeatureEnablementCatalog {

    /** NF02 の公開範囲の設定の key（{@code t_feature_enablement_setting.setting_key}）。 */
    public static final String SETTING_NF02_VISIBLE_SINCE = "nf02-customer-portal.visible-since";
    /** NF07 の決定の束縛の組の種類（D-01・D-09 と、D-09 が dsar を承認する時の D-06。design §2.6.1）。 */
    public static final String BUNDLE_NF07_DECISION_BINDING = "NF07_DECISION_BINDING";
    /** NF02 の全社共通カレンダー（開放の時だけの条件。D-9）。評価器は {@code WorkCalendarConditionEvaluator}（FE-A11）。 */
    public static final String CODE_NF02_CALENDAR = "calendar.companyWide";
    /**
     * NF02 の暦を照会できない時の code（評価できない類。開放の時だけの条件のまま扱い、照会の失敗で NF02 を自動閉鎖しない。D-9。
     * 申請・承認では評価できない条件として 409）。
     */
    public static final String CODE_NF02_CALENDAR_UNVERIFIABLE = "calendar.companyWide.unverifiable";
    /** 段階 A で DB 管理にする容易な7件（requirements §6.1・6.3〜6.7・6.13。FE-A11）。 */
    public static final Set<Feature> STAGE_A_DATABASE_MANAGED = Collections.unmodifiableSet(java.util.EnumSet.of(
            Feature.NF01, Feature.NF02, Feature.NF02_CUSTOMER_PORTAL, Feature.NF03, Feature.NF03_CERTIFICATION_PII,
            Feature.NF04, Feature.NF10));
    /** 段階 C で DB 管理にする NF07 の2件（requirements §6.11・6.12。FE-C05）。 */
    public static final Set<Feature> STAGE_C_DATABASE_MANAGED = Collections.unmodifiableSet(java.util.EnumSet.of(
            Feature.NF07_RETENTION, Feature.NF07_DSAR));
    /** NF05 outbound の barrier の OFF の記録（開放の遷移の前提）。評価器は FE-D05 で足す。 */
    public static final String CODE_NF05_BARRIER_OFF = "barrier.offRecorded";

    /**
     * 手動の閉鎖の理由の区分（{@code closed_reason} に入る。system の理由（{@code SIGN_OFF_REVOKED}・{@code MASTER_SWITCH_CLOSED} 等）と
     * 混ざらないよう {@code MANUAL_} で始まる値だけ。画面は選択肢で出す。review rb-fe-a1 P3-4）。
     */
    public static final List<String> MANUAL_CLOSE_REASONS = List.of("MANUAL_OWNER_DECISION", "MANUAL_SECURITY_INCIDENT",
            "MANUAL_CONDITION_DOUBT", "MANUAL_OPERATION_ERROR", "MANUAL_VERIFICATION", "MANUAL_OTHER");

    /**
     * 「評価できない類」の code（design §4.2。review P2-1）。既存の検査が照会の失敗を違反 code に変えて返す場合も、registry が
     * {@code UNVERIFIABLE} に直す。{@code *.unverifiable}、{@code *.unverifiable[...]}、{@code journal.unreachable}、
     * {@code journal.timeout}、{@code evaluator.*}、{@code state=STATE_IO_ERROR}。
     */
    private static final List<Pattern> UNVERIFIABLE_CODES = List.of(
            Pattern.compile("^[A-Za-z0-9_.\\-]+\\.unverifiable(\\[[^\\]]*])?$"),
            Pattern.compile("^journal\\.(unreachable|timeout)(\\[[^\\]]*])?$"),
            Pattern.compile("^evaluator\\.[A-Za-z0-9_.\\-]+(\\[[^\\]]*])?$"),
            Pattern.compile("^state=STATE_IO_ERROR$"));

    private final Map<Feature, UnitDefinition> units;
    private final Map<String, SignOffKind> kinds;
    private final Map<String, BundleKind> bundles;

    private FeatureEnablementCatalog(Map<Feature, UnitDefinition> units, Map<String, SignOffKind> kinds,
                                     Map<String, BundleKind> bundles) {
        this.units = Collections.unmodifiableMap(new EnumMap<>(units));
        this.kinds = Collections.unmodifiableMap(new LinkedHashMap<>(kinds));
        this.bundles = Collections.unmodifiableMap(new LinkedHashMap<>(bundles));
    }

    /** 全ての開放単位（{@link Feature} の順）。 */
    public Collection<UnitDefinition> units() {
        return units.values();
    }

    /** 開放単位の行（目録に無い開放単位は無い。{@code FeatureEnablementCatalogCoverageTest}）。 */
    public UnitDefinition unit(Feature feature) {
        UnitDefinition unit = units.get(Objects.requireNonNull(feature, "feature"));
        if (unit == null) {
            throw new IllegalArgumentException("目録に無い開放単位: " + feature.name());
        }
        return unit;
    }

    /** 全ての署名の種別（登録の順）。 */
    public Collection<SignOffKind> kinds() {
        return kinds.values();
    }

    /** 署名の種別（目録に無ければ空）。 */
    public Optional<SignOffKind> kind(String code) {
        return Optional.ofNullable(code == null ? null : kinds.get(code));
    }

    /** 全ての組の種類。 */
    public Collection<BundleKind> bundleKinds() {
        return bundles.values();
    }

    /** 組の種類（目録に無ければ空）。 */
    public Optional<BundleKind> bundleKind(String code) {
        return Optional.ofNullable(code == null ? null : bundles.get(code));
    }

    /** DB の開放状態で開閉する開放単位か（段階の間の印）。 */
    public boolean databaseManaged(Feature feature) {
        UnitDefinition unit = units.get(feature);
        return unit != null && unit.databaseManaged();
    }

    /** 開放単位の子（親の閉鎖で自動閉鎖する。design §5.5）。 */
    public List<Feature> children(Feature parent) {
        List<Feature> children = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            if (feature.parent().filter(parent::equals).isPresent()) {
                children.add(feature);
            }
        }
        return List.copyOf(children);
    }

    /** 署名の種別を要する開放単位（共用の種別は複数）。 */
    public List<Feature> unitsRequiring(String kindCode) {
        List<Feature> result = new ArrayList<>();
        for (UnitDefinition unit : units.values()) {
            if (unit.signOffs().stream().anyMatch(requirement -> requirement.kindCode().equals(kindCode))) {
                result.add(unit.feature());
            }
        }
        return List.copyOf(result);
    }

    /** 組の種類の member を要する開放単位。 */
    public List<Feature> unitsRequiringBundle(String bundleKind) {
        Set<Feature> result = new LinkedHashSet<>();
        for (SignOffKind kind : kinds.values()) {
            if (bundleKind.equals(kind.bundleKind())) {
                result.addAll(unitsRequiring(kind.code()));
            }
        }
        return List.copyOf(result);
    }

    /** 「評価できない類」の code か（design §4.2）。 */
    public static boolean isUnverifiableCode(String code) {
        if (code == null) {
            return false;
        }
        for (Pattern pattern : UNVERIFIABLE_CODES) {
            if (pattern.matcher(code).matches()) {
                return true;
            }
        }
        return false;
    }

    /** DB 管理の印を指定の開放単位だけに付けた写し（段階の印を変える試験と、後の段階の印の付与に使う）。 */
    public FeatureEnablementCatalog withDatabaseManaged(Set<Feature> managed) {
        Map<Feature, UnitDefinition> copy = new EnumMap<>(Feature.class);
        units.forEach((feature, unit) -> copy.put(feature, unit.withDatabaseManaged(managed.contains(feature))));
        return new FeatureEnablementCatalog(copy, kinds, bundles);
    }

    /** 組の種類と、その member の種別を足した写し（組の流れの試験の組の種類。FE-A12）。 */
    public FeatureEnablementCatalog withBundle(BundleKind bundle, List<SignOffKind> memberKinds, Feature requiringUnit) {
        Map<String, SignOffKind> kindCopy = new LinkedHashMap<>(kinds);
        for (SignOffKind kind : memberKinds) {
            kindCopy.put(kind.code(), kind);
        }
        Map<String, BundleKind> bundleCopy = new LinkedHashMap<>(bundles);
        bundleCopy.put(bundle.code(), bundle);
        Map<Feature, UnitDefinition> unitCopy = new EnumMap<>(units);
        if (requiringUnit != null) {
            UnitDefinition unit = unitCopy.get(requiringUnit);
            List<SignOffRequirement> requirements = new ArrayList<>(unit.signOffs());
            for (SignOffKind kind : memberKinds) {
                requirements.add(SignOffRequirement.always(kind.code()));
            }
            unitCopy.put(requiringUnit, new UnitDefinition(unit.feature(), unit.restartRequired(), unit.privacyUnit(),
                    requirements, unit.keys(), unit.specificEvaluatorRequired(), unit.databaseManaged(),
                    unit.openOnlyCodes(), unit.openTransitionCodes(), unit.subgates(), unit.sources()));
        }
        return new FeatureEnablementCatalog(unitCopy, kindCopy, bundleCopy);
    }

    // ---------------------------------------------------------------- 正本

    /** 段階 A の目録（13件。NF08 は含めない。D-6）。 */
    public static FeatureEnablementCatalog standard() {
        Builder b = new Builder();

        // NF01 本体（requirements §6.1）
        b.unit(Feature.NF01, false, false, false, List.of(),
                List.of("RB §1.7「初回リリースのスイッチ」の NF01 本体の行", "署名表の NF01 本体の行 (1)〜(4)"),
                decision("NF01_PLAN_SECURITY", "IDENTITY_SECURITY"),
                decision("NF01_PLAN_SRE", "SRE"),
                decision("NF01_PLAN_DOCUMENT_OWNER", "DOCUMENT_OWNER"),
                decision("NF01_PLAN_HR", "HR"),
                decision("NF01_PLAN_DBA", "DBA"),
                evidence("NF01_STAGING_VERIFICATION", "PRODUCTION_OWNER"),
                evidence("NF01_RUNNER_EVIDENCE", "TECH_LEAD"));

        // NF01 二者確認（§6.2）。証跡種別の検査と Privacy 署名の参照の検査は FE-B01 の評価器
        b.unit(Feature.NF01_DUAL_CONFIRMATION, false, false, true, List.of(),
                List.of("LifecycleDualConfirmationReleaseGate.validate（起動時検査）", "FS 4.9.2・4.9.5",
                        "署名表の NF01 二者確認の行 (1)〜(7)"),
                kind("NF01_DUAL_PRIVACY", SignOffKindClass.DECISION, SignOffKind.ReferenceRule.NF01_DUAL_PRIVACY,
                        List.of(), null, false, "PRIVACY_OWNER"),
                evidence("NF01_DUAL_MANUAL_CHECK", "PRODUCTION_OWNER"),
                decision("NF01_DUAL_BREAK_GLASS_DECISION", "SECURITY"),
                evidence("NF01_DUAL_RUNNER_EVIDENCE", "TECH_LEAD"),
                evidence("NF01_DUAL_REVIEW_PASS", "TECH_LEAD"));

        // NF02 本体（§6.3）。全社共通カレンダーは開放の時だけの条件（D-9）。評価器は FE-A11
        b.unit(Feature.NF02, false, false, true, List.of(),
                List.of("ServiceSlaCalculator.findCalendarForScope の4段目", "FS 4.3", "署名表の NF02 の行"),
                decision("NF02_DG02", "CS_OWNER"));
        b.openOnly(Feature.NF02, CODE_NF02_CALENDAR);
        b.openOnly(Feature.NF02, CODE_NF02_CALENDAR_UNVERIFIABLE);

        // NF02 顧客ポータルの問い合わせ（§6.4）
        b.unit(Feature.NF02_CUSTOMER_PORTAL, false, false, false, List.of(),
                List.of("FS 3.4・4.3.1", "署名表の NF02 の行"),
                decision("NF02_PORTAL_PUBLICATION", "PUBLICATION_OWNER"),
                decision("NF02_PORTAL_DG02_AC", "CS_OWNER"),
                kind("NF02_PORTAL_VISIBLE_SINCE_DECISION", SignOffKindClass.DECISION, SignOffKind.ReferenceRule.GENERIC,
                        List.of("visibleSince"), null, false, "CS_OWNER"),
                evidence("NF02_PORTAL_BROWSER_CHECK", "PRODUCTION_OWNER"));
        b.conditional(Feature.NF02_CUSTOMER_PORTAL, evidence("NF02_PORTAL_INVENTORY", "CS_OWNER"),
                SETTING_NF02_VISIBLE_SINCE);

        // NF03 本体（§6.5）。資格番号の暗号鍵は常時（起動時に必須）で条件にしない
        b.unit(Feature.NF03, false, false, false, List.of(),
                List.of("RB §1.7 の NF03 の行", "FS 3.2"),
                decision("NF03_BUSINESS_OWNER", "BUSINESS_OWNER"));

        // NF03 資格番号（§6.6）
        b.unit(Feature.NF03_CERTIFICATION_PII, false, false, false, List.of(),
                List.of("FS 3.4・4.6.1", "署名表の NF03 の行"),
                decision("NF03_DG07", "PRIVACY", "BUSINESS_OWNER"),
                evidence("NF03_EVIDENCE_PRIVACY", "PRIVACY"));

        // NF04（§6.7）。PWA_CONTEXT_SECRET は常時
        b.unit(Feature.NF04, false, false, false, List.of(),
                List.of("RB §1.7 の NF04 の行", "署名表の NF04 の行"),
                decision("NF04_BUSINESS_OWNER", "BUSINESS_OWNER"),
                evidence("NF04_SRE", "SRE"),
                evidence("NF04_PRIVACY", "PRIVACY"),
                decision("NF04_SECURITY", "SECURITY", "BUSINESS_OWNER"),
                evidence("NF04_DEVICE_ACCEPTANCE", "ACCEPTANCE_OWNER"));

        // NF05 inbound（§6.8）。開くのは次の起動。評価器は FE-D05
        List<KeyRequirement> nf05Keys = List.of(
                new KeyRequirement("INTEGRATION_HUB_PUBLIC_ID_KEY", "integration.hub.public-api.public-id-key"),
                new KeyRequirement("INTEGRATION_HUB_CRYPTO_CURRENT_KEY_VERSION", "integration.hub.crypto.current-key-version"),
                new KeyRequirement("INTEGRATION_HUB_CRYPTO_CREDENTIAL_HASH_PEPPER",
                        "integration.hub.crypto.credential-hash-pepper"));
        SignOffKind sreLoad = evidence("NF05_SRE_LOAD", "SRE");
        SignOffKind stagingWire = evidence("NF05_STAGING_WIRE", "SRE");
        SignOffKind digestRule = decision("NF05_DIGEST_RULE_DECISION", "SECURITY", "NF05_OWNER");
        b.unit(Feature.NF05_INBOUND, true, false, true, nf05Keys,
                List.of("IntegrationHubProductionEnablementPolicy（credential・inbound の検査）",
                        "IntegrationHubExternalApiProperties.validateBoundaries", "RB §1.0 の NF05", "署名表の NF05 の行 (a)〜(h)"),
                kind("NF05_DG05_IN", SignOffKindClass.DECISION, SignOffKind.ReferenceRule.NF05_DG05_IN,
                        List.of("approvalDigest"), null, false, "NF05_OWNER"),
                evidence("NF05_IN_SRE_WAF", "SRE"),
                sreLoad, stagingWire,
                evidence("NF05_QUIESCE_ACCEPTANCE", "NF05_OWNER"),
                evidence("NF05_CLIENT_NOTICE", "NF05_OWNER"),
                digestRule);

        // NF05 outbound（§6.9）。barrier の OFF の記録は開放の遷移の前提
        List<KeyRequirement> outboundKeys = new ArrayList<>(nf05Keys);
        outboundKeys.add(new KeyRequirement("INTEGRATION_HUB_TRUSTSTORE_PATH",
                "integration.hub.external-transport.truststore-path"));
        outboundKeys.add(new KeyRequirement("INTEGRATION_HUB_TRUSTSTORE_PASSWORD",
                "integration.hub.external-transport.truststore-password"));
        b.unit(Feature.NF05_OUTBOUND, true, false, true, outboundKeys,
                List.of("IntegrationHubProductionEnablementPolicy（credential・outbound の検査）",
                        "IntegrationHubOutboundStartupBarrier", "RB §1.7「ON の起動までの順序と準備」", "署名表の NF05 の行"),
                kind("NF05_DG05_OUT", SignOffKindClass.DECISION, SignOffKind.ReferenceRule.NF05_DG05_OUT,
                        List.of("approvalDigest"), null, false, "NF05_OWNER"),
                evidence("NF05_OUT_G3R", "NF05_OWNER"),
                evidence("NF05_OUT_CHANGE_ACCEPTANCE", "NF05_OWNER", "SRE"),
                sreLoad, stagingWire, digestRule);
        b.openTransition(Feature.NF05_OUTBOUND, CODE_NF05_BARRIER_OFF);

        // NF06（§6.10）。評価器は FE-B02
        b.unit(Feature.NF06, false, false, true,
                List.of(new KeyRequirement("IMPORT_ROW_REFERENCE_HMAC_KEY", "app.import-center.row-reference.hmac-key")),
                List.of("ImportAdmissionStartupValidator・ImportRowReferenceKeyStartupValidator（起動時検査）",
                        "FS 4.8.2 の #1〜#10", "署名表の NF06 の行"),
                kind("NF06_ADMISSION", SignOffKindClass.DECISION, SignOffKind.ReferenceRule.NF06_ADMISSION,
                        List.of("decisionId", "tenantId", "sourceCodes", "approvalDigest"), null, false, "DATA_OWNER"),
                evidence("NF06_PRIVACY_RAW_CSV", "PRIVACY"),
                evidence("NF06_SRE_LB", "SRE"),
                evidence("NF06_DBA_ACCOUNT", "DBA"),
                evidence("NF06_STAGING_CHECK", "PRODUCTION_OWNER"));

        // NF07 保持・保全（§6.11）。D-01・D-09 は組「NF07 の決定の束縛」。副入口の条件の署名は開放単位の条件ではない。評価器は
        // PrivacyProductionConfigurationValidator（prod だけ。FE-C05）
        List<KeyRequirement> journalKeys = List.of(
                new KeyRequirement("PRIVACY_JOURNAL_DATASOURCE_URL", "privacy.journal.datasource.url"),
                new KeyRequirement("PRIVACY_JOURNAL_DATASOURCE_USERNAME", "privacy.journal.datasource.username"),
                new KeyRequirement("PRIVACY_JOURNAL_DATASOURCE_PASSWORD", "privacy.journal.datasource.password"),
                new KeyRequirement("PRIVACY_JOURNAL_JOURNALID", "privacy.journal.journal-id"));
        SignOffKind d01 = kind("NF07_D01", SignOffKindClass.DECISION, SignOffKind.ReferenceRule.NF07_DECISION,
                List.of(), BUNDLE_NF07_DECISION_BINDING, true, "PRIVACY_OWNER");
        SignOffKind d09 = kind("NF07_D09", SignOffKindClass.DECISION, SignOffKind.ReferenceRule.NF07_DECISION,
                List.of("tenantId", "capabilities", "approvalDigest"), BUNDLE_NF07_DECISION_BINDING, true, "D09_SIGNER");
        b.unit(Feature.NF07_RETENTION, false, true, true, journalKeys,
                List.of("PrivacyProductionConfigurationValidator.validate", "PrivacyJournalStartupVerifier.enforce",
                        "FS 4.7.2 の表の 1〜4", "署名表の NF07 保持・保全の行 (a)〜(e)"),
                d01, d09, privacyEvidence("NF07_BRIDGE", "TECH_LEAD"));
        b.subgate(new Subgate("HOLD_START", Feature.NF07_RETENTION, List.of("NF07_D05", "NF07_D05_OWNER_ACCEPTANCE")),
                privacyDecision("NF07_D05", "D05_OWNER"), privacyEvidence("NF07_D05_OWNER_ACCEPTANCE", "D05_OWNER"));
        b.subgate(new Subgate("REL", Feature.NF07_RETENTION,
                        List.of("NF07_D03", "NF07_D05", "NF07_TRUST_KMS", "NF07_TRUST_STAGING")),
                privacyDecision("NF07_D03", "D03_OWNER"), privacyEvidence("NF07_TRUST_KMS", "SECURITY", "D03_OWNER"),
                privacyEvidence("NF07_TRUST_STAGING", "SRE"));
        b.subgate(new Subgate("REG", Feature.NF07_RETENTION, List.of("NF07_D01", "NF07_D02", "NF07_D03")),
                privacyDecision("NF07_D02", "D02_OWNER"));

        // NF07 本人請求（§6.12）。D-06 も組に含める
        SignOffKind d06 = kind("NF07_D06", SignOffKindClass.DECISION, SignOffKind.ReferenceRule.NF07_DECISION,
                List.of("dueDays", "packageMaxAge"), BUNDLE_NF07_DECISION_BINDING, true, "D06_OWNER");
        b.unit(Feature.NF07_DSAR, false, true, true, List.of(),
                List.of("PrivacyProductionConfigurationValidator.validateDsarPrerequisites", "FS 4.7.2 の表の 5",
                        "署名表の NF07 本人請求の行"),
                d01, d06, d09,
                privacyEvidence("NF07_DSAR_HOLD_ACCEPTANCE", "D05_OWNER", "D06_OWNER"),
                privacyEvidence("NF07_DSAR_SELF_DELIVERY", "D06_OWNER"),
                privacyEvidence("NF07_DSAR_I10", "PRIVACY"),
                privacyEvidence("NF07_DSAR_REDACTION", "D06_OWNER"));
        // D-09 の digest の整合の検査（FE-C01）を要する。実装が無い間は組の確認を拒む
        b.bundle(new BundleKind(BUNDLE_NF07_DECISION_BINDING, List.of("NF07_D01", "NF07_D09"), List.of("NF07_D06"), true,
                true));

        // NF10（§6.13）。scheduler 無効は警告だけ（ADVISORY。評価器は SchedulingConditionEvaluator。FE-A11）
        b.unit(Feature.NF10, false, false, false, List.of(),
                List.of("RB §1.7 の NF10 の行", "FS 4.5.1 の #1〜#8", "署名表の NF10 の行"),
                decision("NF10_REPORT_OWNER", "REPORT_OWNER"),
                decision("NF10_SRE", "SRE"),
                decision("NF10_TZ_POLICY", "REPORT_OWNER", "SRE"),
                decision("NF10_DRAIN_APPROVAL", "SRE", "DBA"));

        // 段階 A の DB 管理の印（容易な7件。FE-A11）
        b.databaseManaged(STAGE_A_DATABASE_MANAGED);
        // 段階 C の DB 管理の印（NF07 の2件。評価器は PrivacyProductionConfigurationValidator。FE-C05）
        b.databaseManaged(STAGE_C_DATABASE_MANAGED);

        return b.build();
    }

    private static SignOffKind decision(String code, String... roles) {
        return kind(code, SignOffKindClass.DECISION, SignOffKind.ReferenceRule.GENERIC, List.of(), null, false, roles);
    }

    private static SignOffKind evidence(String code, String... roles) {
        return kind(code, SignOffKindClass.EVIDENCE, SignOffKind.ReferenceRule.GENERIC, List.of(), null, false, roles);
    }

    private static SignOffKind privacyDecision(String code, String... roles) {
        return kind(code, SignOffKindClass.DECISION, SignOffKind.ReferenceRule.NF07_DECISION, List.of(), null, true, roles);
    }

    private static SignOffKind privacyEvidence(String code, String... roles) {
        return kind(code, SignOffKindClass.EVIDENCE, SignOffKind.ReferenceRule.GENERIC, List.of(), null, true, roles);
    }

    private static SignOffKind kind(String code, SignOffKindClass kindClass, SignOffKind.ReferenceRule rule,
                                    List<String> fields, String bundle, boolean privacy, String... roles) {
        return new SignOffKind(code, kindClass, List.of(roles), rule, fields, bundle, privacy);
    }

    /** 目録の組立て（同じ種別を共用する開放単位は同じ定義を使う）。 */
    private static final class Builder {

        private final Map<Feature, UnitDefinition> units = new EnumMap<>(Feature.class);
        private final Map<String, SignOffKind> kinds = new LinkedHashMap<>();
        private final Map<String, BundleKind> bundles = new LinkedHashMap<>();

        void unit(Feature feature, boolean restartRequired, boolean privacyUnit, boolean specificEvaluatorRequired,
                  List<KeyRequirement> keys, List<String> sources, SignOffKind... required) {
            List<SignOffRequirement> requirements = new ArrayList<>();
            for (SignOffKind kind : required) {
                register(kind);
                requirements.add(SignOffRequirement.always(kind.code()));
            }
            units.put(feature, new UnitDefinition(feature, restartRequired, privacyUnit, requirements, keys,
                    specificEvaluatorRequired, false, Set.of(), Set.of(), List.of(), sources));
        }

        void conditional(Feature feature, SignOffKind kind, String settingKey) {
            register(kind);
            UnitDefinition unit = units.get(feature);
            List<SignOffRequirement> requirements = new ArrayList<>(unit.signOffs());
            requirements.add(SignOffRequirement.whenSet(kind.code(), settingKey));
            replace(unit, requirements, unit.openOnlyCodes(), unit.openTransitionCodes(), unit.subgates());
        }

        void openOnly(Feature feature, String code) {
            UnitDefinition unit = units.get(feature);
            Set<String> codes = new LinkedHashSet<>(unit.openOnlyCodes());
            codes.add(code);
            replace(unit, unit.signOffs(), codes, unit.openTransitionCodes(), unit.subgates());
        }

        void openTransition(Feature feature, String code) {
            UnitDefinition unit = units.get(feature);
            Set<String> codes = new LinkedHashSet<>(unit.openTransitionCodes());
            codes.add(code);
            replace(unit, unit.signOffs(), unit.openOnlyCodes(), codes, unit.subgates());
        }

        void subgate(Subgate subgate, SignOffKind... newKinds) {
            for (SignOffKind kind : newKinds) {
                register(kind);
            }
            UnitDefinition unit = units.get(subgate.unit());
            List<Subgate> subgates = new ArrayList<>(unit.subgates());
            subgates.add(subgate);
            replace(unit, unit.signOffs(), unit.openOnlyCodes(), unit.openTransitionCodes(), subgates);
        }

        void bundle(BundleKind bundle) {
            bundles.put(bundle.code(), bundle);
        }

        void databaseManaged(Set<Feature> managed) {
            for (Feature feature : managed) {
                units.put(feature, units.get(feature).withDatabaseManaged(true));
            }
        }

        private void replace(UnitDefinition unit, List<SignOffRequirement> requirements, Set<String> openOnly,
                             Set<String> openTransition, List<Subgate> subgates) {
            units.put(unit.feature(), new UnitDefinition(unit.feature(), unit.restartRequired(), unit.privacyUnit(),
                    requirements, unit.keys(), unit.specificEvaluatorRequired(), unit.databaseManaged(), openOnly,
                    openTransition, subgates, unit.sources()));
        }

        private void register(SignOffKind kind) {
            SignOffKind existing = kinds.putIfAbsent(kind.code(), kind);
            if (existing != null && !existing.equals(kind)) {
                throw new IllegalStateException("署名の種別の定義が食い違う: " + kind.code());
            }
        }

        FeatureEnablementCatalog build() {
            return new FeatureEnablementCatalog(units, kinds, bundles);
        }
    }
}
