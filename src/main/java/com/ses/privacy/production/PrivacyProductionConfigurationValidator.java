package com.ses.privacy.production;

import com.ses.config.AdministratorConditionCheck;
import com.ses.config.AdministratorStartupConditions;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.feature.Feature;
import com.ses.entity.IdentityProvider;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureConditionEvaluator;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.state.FeatureEnablementStateSource;
import com.ses.mapper.IdentityProviderMapper;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.privacy.production.PrivacyAccessGuard.Capability;
import com.ses.privacy.production.dsar.PrivacyDsarDecisions;
import com.ses.privacy.production.retention.hold.PrivacyJournalStartupVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 本番の NF07（保持・保全・DSAR）の開放の条件の検査と、その評価器（W3-14b、production-bridge-phased-plan.md §3.1.1・§5.1、
 * feature-enablement-console design §4.4・§8.5 の 6。FE-C05）。
 *
 * <p>検査の内容（固定の違反コードだけを列挙し、設定値・username は出さない）:</p>
 * <ol>
 *   <li>決定ID束縛（{@link PrivacyDecisionBinding}。値は承認の値の源 {@link PrivacyApprovalSource}）: 承認tenantが設定tenantと一致、
 *       開く capability が承認済み、必要な決定（retention は D-01・D-09、dsar は D-01・D-06・D-09）が形式・placeholder・重複・
 *       有効期間の上限の規則を満たし、approval digest が一致し、開放単位が要る決定が有効期間内（D-06 の失効は DSAR だけ。review P1-2）。</li>
 *   <li>{@code privacy-admin} group が有効で {@code privacy.*} を許可し、privacy の拒否行を持たない。</li>
 *   <li>{@code privacy-admin} の有効な管理者（{@link UserPermissionGroupMapper#selectEffectivePrivacyAdminUsernames}。
 *       OIDC でログインできる管理者だけを数え、break-glass username は大文字小文字を区別せず除く）が
 *       {@value #MIN_PRIVACY_ADMINISTRATORS} 名以上。アカウントロックは数え方に影響しない（review P2-1）。</li>
 *   <li>{@code privacy-admin} 以外の有効な group が {@code privacy.} 接頭辞の許可行を持たない（review P3-2）。</li>
 *   <li>{@code dsar}: D-06 の期限規則 {@code dueDays} が 1〜366（{@code dsar.dueDaysUnset}）、package の最長保存期間が1時間〜366日
 *       （{@code dsar.packageMaxAgeUnset}）、F2 provider が1件以上（{@code dsar.providersUnregistered}）。</li>
 *   <li>{@code retention}: Journal の開放の条件の結果（appender の権限。{@link PrivacyJournalStartupVerifier#enablementResult}）。</li>
 * </ol>
 *
 * <p>評価器（{@link FeatureConditionEvaluator}。NF07 の2件）は {@link #validate} と同じ method を呼ぶ（条件の写しを作らない。PR #130 の形）。
 * 照会の失敗（{@code *.unverifiable}・{@code journal.unreachable}）は評価できない類で、一度の失敗では閉じない（review P2-1）。
 * 時間要因（{@code *.signedOn.future}・{@code *.expiresOn.expired}）は {@code temporal=true}。</p>
 *
 * <p>起動時の {@link #validate} は prod でも機能の条件では起動を止めない（log だけ。開いている NF07 の開放単位を閉じるのは
 * {@code FeatureEnablementStartupEvaluator} の起動時の評価）。開いている capability は開放状態の源から読む。</p>
 *
 * <p>{@link SmartInitializingSingleton} で全 singleton 生成後・Tomcat の connector 開始前に実行する。読むだけで DB へは書かない。</p>
 */@Component
@Profile("prod")
public class PrivacyProductionConfigurationValidator implements SmartInitializingSingleton,
        AdministratorStartupConditions, FeatureConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(PrivacyProductionConfigurationValidator.class);

    /** 登録者と有効化者の分離（NF07-REG）等の SoD に要る privacy-admin の人数（§5.1）。 */
    public static final int MIN_PRIVACY_ADMINISTRATORS = 2;

    /** 開いている NF07 の開放単位の条件を満たさないまま起動したことを示す固定コード（監視のlog検索用）。 */
    public static final String LOG_CODE_STOPPED = "NF07_PRIVACY_STOPPED_AT_STARTUP";

    /** privacy-admin の所属者を照会できなかったことを示す違反コード（評価できない類。guard へは照会失敗として伝える）。 */
    private static final String UNVERIFIABLE_PRIVACY_ADMINS = "privacyAdmins.unverifiable";

    private static final String ADMIN_ROLE = "管理者";
    private static final String OIDC_PROVIDER_TYPE = "OIDC";

    private final FeatureEnablementStateSource stateSource;
    private final PrivacyApprovalSource approvalSource;
    private final OidcSecurityProperties oidcProperties;
    private final PermissionGroupMapper permissionGroupMapper;
    private final UserPermissionGroupMapper userPermissionGroupMapper;
    private final IdentityProviderMapper identityProviderMapper;
    private final ProductionPrivacyProviderRegistry providerRegistry;
    private final Clock clock;
    /** Journal の起動時検査（保持・保全の開放の条件。無ければ評価できない）。 */
    private volatile PrivacyJournalStartupVerifier journalVerifier;
    /** 継続性 guard が NF07 の開放状態・未決の申請を DB から読む部品（無ければ従来どおり常に判定する）。 */
    private volatile PrivacyEnablementStateReader enablementStateReader;

    /** F2 provider の registry を持たない生成（DSAR を ON にすると {@code dsar.providersUnregistered} で止まる）。 */
    public PrivacyProductionConfigurationValidator(PrivacyFeatureProperties featureProperties,
                                                   PrivacyApprovalProperties approvalProperties,
                                                   OidcSecurityProperties oidcProperties,
                                                   PermissionGroupMapper permissionGroupMapper,
                                                   UserPermissionGroupMapper userPermissionGroupMapper,
                                                   IdentityProviderMapper identityProviderMapper,
                                                   Clock clock) {
        this(featureProperties, approvalProperties, oidcProperties, permissionGroupMapper, userPermissionGroupMapper,
                identityProviderMapper, null, clock);
    }

    /** property の源で包む（既存の試験のため）。 */
    public PrivacyProductionConfigurationValidator(PrivacyFeatureProperties featureProperties,
                                                   PrivacyApprovalProperties approvalProperties,
                                                   OidcSecurityProperties oidcProperties,
                                                   PermissionGroupMapper permissionGroupMapper,
                                                   UserPermissionGroupMapper userPermissionGroupMapper,
                                                   IdentityProviderMapper identityProviderMapper,
                                                   ProductionPrivacyProviderRegistry providerRegistry,
                                                   Clock clock) {
        this(PrivacyDecisionWindowMonitor.propertyStateSource(featureProperties),
                PrivacyApprovalSource.properties(approvalProperties, featureProperties), oidcProperties,
                permissionGroupMapper, userPermissionGroupMapper, identityProviderMapper, providerRegistry, clock);
    }

    /**
     * @param stateSource    開放状態の源（NF07 の開いている capability を読む。{@code FeatureSwitches} を経由しない）
     * @param approvalSource 承認の値の源
     */
    @Autowired
    public PrivacyProductionConfigurationValidator(FeatureEnablementStateSource stateSource,
                                                   PrivacyApprovalSource approvalSource,
                                                   OidcSecurityProperties oidcProperties,
                                                   PermissionGroupMapper permissionGroupMapper,
                                                   UserPermissionGroupMapper userPermissionGroupMapper,
                                                   IdentityProviderMapper identityProviderMapper,
                                                   ProductionPrivacyProviderRegistry providerRegistry,
                                                   Clock clock) {
        this.stateSource = stateSource;
        this.approvalSource = approvalSource;
        this.oidcProperties = oidcProperties;
        this.permissionGroupMapper = permissionGroupMapper;
        this.userPermissionGroupMapper = userPermissionGroupMapper;
        this.identityProviderMapper = identityProviderMapper;
        this.providerRegistry = providerRegistry;
        this.clock = clock == null ? Clock.system(PrivacyDecisionBinding.BUSINESS_ZONE) : clock;
    }

    @Autowired(required = false)
    public void setJournalVerifier(PrivacyJournalStartupVerifier journalVerifier) {
        this.journalVerifier = journalVerifier;
    }

    @Autowired(required = false)
    public void setEnablementStateReader(PrivacyEnablementStateReader enablementStateReader) {
        this.enablementStateReader = enablementStateReader;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate();
    }

    /**
     * 開いている capability の条件を検査し、結果を log に出す（prod でも起動を止めない。feature-enablement-console design §4.4）。
     * 満たさなければ WARN（{@value #LOG_CODE_STOPPED}。違反の固定コードだけ）を出す。開いている開放単位を閉じるのは起動時の評価。
     */
    public void validate() {
        Report report = report();
        if (report.enabled().isEmpty()) {
            log.info("[privacy] NF07の保持・保全・DSARが開いていないため、決定ID束縛とprivacy-adminの起動検査を行いません");
            return;
        }
        if (!report.satisfied()) {
            log.warn("{}: {}（起動は続けます。開いている開放単位は起動時の評価で閉じます）: privacyAdmins={}", LOG_CODE_STOPPED,
                    report.message(), report.administrators());
            return;
        }
        log.info("[privacy] NF07の開放の条件を確認しました: capabilities={} decisionIds={} privacyAdmins={}",
                flagNames(report.enabled()), report.decisionIds(), report.administrators());
        if (report.enabled().contains(Capability.DSAR)) {
            // 期限規則と package の最長保存期間は approval digest に入らない（review P3-6）。D-06 の決定書の値とこの log（画面は
            // D-06 の署名の構造化された値）を人が照合する（feature-switches.md 4.7）。値は法的な期間で秘密値ではない
            PrivacyApprovalSource values = approvalSource == null ? null : approvalSource.resolve();
            log.info("[privacy] DSARの設定値（D-06 の決定書と照合する）: dueDays={} packageMaxAge={}",
                    values == null ? null : values.dsarDueDays(), values == null ? null : values.dsarPackageMaxAge());
        }
    }

    /**
     * 開いている capability の条件の検査の結果（起動時の log と試験）。開いていなければ違反は空。違反は構造・時間要因・
     * 照会の失敗の全て（以前は構造の違反で起動を止めた）。
     */
    public Report report() {
        Set<Capability> enabled = enabledCapabilities();
        if (enabled.isEmpty()) {
            return new Report(enabled, List.of(), -1, List.of());
        }
        String tenantId = authorizationTenantId();
        PrivacyApprovalSource values = approvalSource == null ? null : approvalSource.resolve();
        List<String> violations = new ArrayList<>();
        PrivacyDecisionBinding.Evaluation binding = PrivacyDecisionBinding.evaluate(values, enabled, tenantId,
                PrivacyDecisionBinding.businessDate(clock), enabled);
        PrivacyDecisionBinding.structuralViolations(binding).forEach(code -> violations.add("decisionBinding." + code));
        if (enabled.contains(Capability.DSAR)) {
            validateDsarPrerequisites(values, violations);
        }
        validatePrivacyAdminGroup(tenantId, violations);
        int administrators = validatePrivacyAdministrators(tenantId, violations);
        validateNoPrivacyAllowOutsidePrivacyAdmin(tenantId, violations);
        PrivacyDecisionBinding.temporalViolations(binding).forEach(code -> violations.add("decisionBinding." + code));
        List<String> decisionIds = binding.complete()
                ? List.copyOf(binding.snapshot().decisionIds().values()) : List.of();
        return new Report(enabled, violations, administrators, decisionIds);
    }

    /**
     * 起動時の検査の結果。
     *
     * @param enabled        開いている capability
     * @param violations     違反の固定コード（構造・照会の失敗の後に時間要因）
     * @param administrators privacy-admin の有効な管理者の人数（数えていなければ -1）
     * @param decisionIds    束縛が完全な場合の決定ID（log 用。決定書の公開識別子）
     */
    public record Report(Set<Capability> enabled, List<String> violations, int administrators,
                         List<String> decisionIds) {

        public Report {
            enabled = Set.copyOf(enabled);
            violations = List.copyOf(violations);
            decisionIds = List.copyOf(decisionIds);
        }

        /** 開いている capability の条件を全て満たすか。 */
        public boolean satisfied() {
            return violations.isEmpty();
        }

        /** 違反の説明（固定コードだけ）。 */
        public String message() {
            return "本番でNF07の開放単位（" + flagNames(enabled) + "）を開く条件を満たしません: " + String.join(",", violations);
        }
    }

    // ---------------------------------------------------------------- 評価器（FE-C05）

    @Override
    public Set<Feature> features() {
        return EnumSet.of(Feature.NF07_RETENTION, Feature.NF07_DSAR);
    }

    /**
     * NF07 の開放単位の条件（requirements §6.11・§6.12）。開放単位ごとに、自分の capability だけを開く束縛として評価し、期間も自分が
     * 要る決定だけで判定する（D-06 の失効で保持・保全を閉じない）。承認の値は評価に渡された snapshot から読む（書込みの transaction の
     * 中では読み直した snapshot。design §3.2）。Journal は {@code remoteChecksAllowed} なら再検査し、そうでなければ最後の結果を使う。
     */
    @Override
    public List<FeatureConditionCheck> evaluate(Feature feature, FeatureEvaluationContext context) {
        Capability capability = feature == Feature.NF07_DSAR ? Capability.DSAR : Capability.RETENTION;
        Set<Capability> own = EnumSet.of(capability);
        String tenantId = authorizationTenantId();
        PrivacyApprovalSource values = approvalSource == null ? null
                : approvalSource.forSnapshot(context.snapshot()).resolve();
        List<FeatureConditionCheck> checks = new ArrayList<>();

        PrivacyDecisionBinding.Evaluation binding = PrivacyDecisionBinding.evaluate(values, own, tenantId,
                context.businessDate(), own);
        if (binding.complete()) {
            checks.add(FeatureConditionCheck.satisfied("decisionBinding.valid", FeatureConditionCheck.Category.AUTO, null));
        } else {
            for (String code : binding.violations()) {
                checks.add(FeatureConditionCheck.unsatisfied("decisionBinding." + code,
                        FeatureConditionCheck.Category.AUTO, PrivacyDecisionBinding.isTemporalViolation(code)));
            }
        }
        if (capability == Capability.DSAR) {
            List<String> dsar = new ArrayList<>();
            validateDsarPrerequisites(values, dsar);
            addAll(checks, dsar, "dsar.prerequisites");
        }
        List<String> administrators = new ArrayList<>();
        validatePrivacyAdminGroup(tenantId, administrators);
        validatePrivacyAdministrators(tenantId, administrators);
        validateNoPrivacyAllowOutsidePrivacyAdmin(tenantId, administrators);
        addAll(checks, administrators, "privacyAdmins.valid");
        if (capability == Capability.RETENTION) {
            checks.addAll(journalChecks(context.remoteChecksAllowed()));
        }
        return checks;
    }

    /** Journal の開放の条件の結果（appender の権限。requirements §6.11 の (e)）。 */
    private List<FeatureConditionCheck> journalChecks(boolean remoteChecksAllowed) {
        PrivacyJournalStartupVerifier verifier = journalVerifier;
        if (verifier == null) {
            return List.of(FeatureConditionCheck.unverifiable("journal.verifier.unverifiable",
                    FeatureConditionCheck.Category.AUTO));
        }
        PrivacyJournalStartupVerifier.Result result = remoteChecksAllowed ? verifier.reverify()
                : verifier.enablementResult();
        if (result == null) {
            return List.of(FeatureConditionCheck.unverifiable("journal.verifier.unverifiable",
                    FeatureConditionCheck.Category.AUTO));
        }
        return switch (result.availability()) {
            case AVAILABLE -> List.of(FeatureConditionCheck.satisfied("journal.appender",
                    FeatureConditionCheck.Category.AUTO, null));
            case NOT_CONFIGURED -> List.of(FeatureConditionCheck.unsatisfied("journal.notConfigured",
                    FeatureConditionCheck.Category.AUTO, false));
            case UNAVAILABLE -> {
                List<FeatureConditionCheck> checks = new ArrayList<>();
                for (String code : result.violations()) {
                    checks.add(FeatureConditionCheck.unsatisfied(code.startsWith("journal.") ? code : "journal." + code,
                            FeatureConditionCheck.Category.AUTO, false));
                }
                if (checks.isEmpty()) {
                    checks.add(FeatureConditionCheck.unverifiable("journal.unverifiable",
                            FeatureConditionCheck.Category.AUTO));
                }
                yield checks;
            }
        };
    }

    private static void addAll(List<FeatureConditionCheck> checks, List<String> violations, String satisfiedCode) {
        if (violations.isEmpty()) {
            checks.add(FeatureConditionCheck.satisfied(satisfiedCode, FeatureConditionCheck.Category.AUTO, null));
            return;
        }
        for (String code : violations) {
            checks.add(FeatureConditionCheck.unsatisfied(code, FeatureConditionCheck.Category.AUTO, false));
        }
    }
    /**
     * 管理者の継続性guard（{@code AdministratorContinuityGuard}。RB-ADMIN-GUARD）向け: {@link #validate} と同じ照会
     * （{@code validatePrivacyAdministrators}）で privacy-admin の有効な管理者を数える。
     *
     * <p>NF07 を DB の開放状態で管理する間（feature-enablement-console design §14。J-17）は、NF07 保持・保全か DSAR が開いている
     * （{@code OPEN}）か未決の申請がある時だけ判定し、それ以外は {@link AdministratorConditionCheck#notEvaluated}（privacy-admin の人数は
     * 起動を止める条件ではなく NF07 の開放の条件になったため、閉じている間に人数を割っても NF07 が閉じたままになるだけ）。開放状態を
     * DB から読めなければ {@link AdministratorConditionCheck#unverifiable}（guard は利用者の管理の変更を全て拒否する）。NF07 を DB で
     * 管理していない間は、従来どおり開閉に関わらず判定する。</p>
     *
     * <p>provider が無い場合は {@link #validate} と同じく満たさない扱いにする。照会に失敗した場合（{@code validatePrivacyAdministrators}
     * が例外を握って {@code privacyAdmins.unverifiable} にする場合）は、人数を比べられないことを guard へ伝えるため
     * {@link AdministratorConditionCheck#unverifiable} で返す（guard は拒否する）。DB へは書かない。</p>
     */
    @Override
    public List<AdministratorConditionCheck> checkAdministratorConditions() {
        AdministratorConditionCheck.Condition privacyCondition =
                AdministratorConditionCheck.Condition.PRIVACY_ADMINISTRATORS;
        PrivacyEnablementStateReader reader = enablementStateReader;
        if (reader != null) {
            switch (reader.read()) {
                case INACTIVE -> {
                    return List.of(AdministratorConditionCheck.notEvaluated(privacyCondition, MIN_PRIVACY_ADMINISTRATORS));
                }
                case UNREADABLE -> {
                    return List.of(AdministratorConditionCheck.unverifiable(privacyCondition, MIN_PRIVACY_ADMINISTRATORS));
                }
                default -> {
                    // ACTIVE（開いている・未決の申請あり）と NOT_DATABASE_MANAGED は判定する
                }
            }
        }
        List<String> missing = new ArrayList<>();
        int administrators = validatePrivacyAdministrators(authorizationTenantId(), missing);
        AdministratorConditionCheck.Condition condition = AdministratorConditionCheck.Condition.PRIVACY_ADMINISTRATORS;
        if (missing.contains(UNVERIFIABLE_PRIVACY_ADMINS)) {
            return List.of(AdministratorConditionCheck.unverifiable(condition, MIN_PRIVACY_ADMINISTRATORS));
        }
        return List.of(AdministratorConditionCheck.evaluated(condition, administrators, MIN_PRIVACY_ADMINISTRATORS,
                missing.isEmpty()));
    }

    /**
     * DSAR を ON にする追加の前提（§5.1 の表。NF07-A2b）。D-06 の決定ID（本人確認基準・期限規則・package の最長保存期間の根拠）は
     * {@link PrivacyDecisionBinding#requiredDecisions} が DSAR に要求し、欠落・placeholder・digest 不一致は {@code decisionBinding.*}
     * として既に数える。ここでは決定書に従って設定する値と F2 provider の登録を確かめる。
     */
    private void validateDsarPrerequisites(PrivacyApprovalSource values, List<String> missing) {
        if (values == null || !PrivacyDsarDecisions.isValidDueDays(values.dsarDueDays())) {
            missing.add("dsar.dueDaysUnset");
        }
        if (values == null || !PrivacyDsarDecisions.isValidPackageMaxAge(values.dsarPackageMaxAge())) {
            missing.add("dsar.packageMaxAgeUnset");
        }
        int providers;
        try {
            providers = providerRegistry == null ? 0 : providerRegistry.providers().size();
        } catch (RuntimeException e) {
            log.error("[privacy] F2 providerの登録を確認できません", e);
            providers = 0;
        }
        if (providers < 1) {
            missing.add("dsar.providersUnregistered");
        }
    }

    private void validatePrivacyAdminGroup(String tenantId, List<String> missing) {
        Long count;
        try {
            count = permissionGroupMapper.countPrivacyAdminGroupsGrantingPrivacy(tenantId);
        } catch (RuntimeException e) {
            log.error("[privacy] privacy-admin groupの状態を照会できません", e);
            missing.add("privacyAdminGroup.unverifiable");
            return;
        }
        if (count == null || count != 1L) {
            missing.add("privacyAdminGroup.notGrantingPrivacy");
        }
    }

    /**
     * 有効な privacy-admin の人数（OIDC でログインできる管理者。break-glass username を除く）を返す。数えられなければ -1。
     * 「OIDC でログインできる」は {@code ProductionSecurityConfigurationValidator#validateOidcAdministrators} と同じ判定
     * （設定tenant・issuer の有効な OIDC provider に APPROVED の外部identity link を持つ）。
     */
    private int validatePrivacyAdministrators(String tenantId, List<String> missing) {
        String identityTenantId = trimmed(oidcProperties == null ? null : oidcProperties.getTenantId());
        String issuer = trimmed(oidcProperties == null ? null : oidcProperties.getIssuerUri());
        if (identityTenantId == null || issuer == null) {
            missing.add("privacyAdmins.oidcProviderUnavailable");
            return -1;
        }
        List<String> usernames;
        try {
            IdentityProvider provider = identityProviderMapper == null ? null
                    : identityProviderMapper.selectEnabledByTenantAndIssuer(identityTenantId, issuer);
            if (provider == null || provider.getId() == null
                    || !OIDC_PROVIDER_TYPE.equalsIgnoreCase(provider.getProviderType())) {
                missing.add("privacyAdmins.oidcProviderUnavailable");
                return -1;
            }
            usernames = userPermissionGroupMapper.selectEffectivePrivacyAdminUsernames(
                    tenantId, ADMIN_ROLE, identityTenantId, provider.getId());
        } catch (RuntimeException e) {
            log.error("[privacy] privacy-adminの所属者を照会できません", e);
            missing.add(UNVERIFIABLE_PRIVACY_ADMINS);
            return -1;
        }
        if (usernames == null) {
            missing.add(UNVERIFIABLE_PRIVACY_ADMINS);
            return -1;
        }
        // break-glass username は incident 外の OIDC session が失効させられ、break-glass session では privacy が
        // DENY_SCOPE になるため数えない。DB の照合順序が大文字小文字を区別しないため、比較も区別しない（SecurityConfig と同じ）。
        Set<String> breakGlass = breakGlassUsernames();
        Set<String> administrators = new TreeSet<>();
        usernames.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(username -> !breakGlass.contains(username.toLowerCase(Locale.ROOT)))
                .map(username -> username.toLowerCase(Locale.ROOT))
                .forEach(administrators::add);
        if (administrators.size() < MIN_PRIVACY_ADMINISTRATORS) {
            missing.add("privacyAdmins.insufficient[current=" + administrators.size()
                    + ";required=" + MIN_PRIVACY_ADMINISTRATORS + "]");
        }
        return administrators.size();
    }

    private void validateNoPrivacyAllowOutsidePrivacyAdmin(String tenantId, List<String> missing) {
        List<String> groupKeys;
        try {
            groupKeys = permissionGroupMapper.selectGroupKeysAllowingPrivacyOutsidePrivacyAdmin(tenantId);
        } catch (RuntimeException e) {
            log.error("[privacy] privacy-admin以外のgroupのprivacy許可行を照会できません", e);
            missing.add("privacyAllow.unverifiable");
            return;
        }
        if (groupKeys == null) {
            missing.add("privacyAllow.unverifiable");
            return;
        }
        if (!groupKeys.isEmpty()) {
            // group key は設定識別子であり利用者情報を含まない（既存の '*' 検査と同じく出す）。
            missing.add("privacyAllow.outsidePrivacyAdmin[" + String.join(";", new TreeSet<>(groupKeys)) + "]");
        }
    }

    /**
     * 開いている capability（開放状態の源を直接読む。feature-enablement-console design §8.5 の 2。{@code FeatureSwitches#isEnabled} は
     * {@code PrivacyDecisionWindowMonitor} の実行時条件を掛けるため使わない）。
     */
    private Set<Capability> enabledCapabilities() {
        Set<Capability> enabled = EnumSet.noneOf(Capability.class);
        if (stateSource == null) {
            return enabled;
        }
        if (stateSource.isOpen(Feature.NF07_RETENTION)) {
            enabled.add(Capability.RETENTION);
        }
        if (stateSource.isOpen(Feature.NF07_DSAR)) {
            enabled.add(Capability.DSAR);
        }
        return enabled;
    }

    /** {@code AuthorizationServiceImpl}・{@code PermissionGroupManagementServiceImpl} が group を評価する tenant。 */
    private String authorizationTenantId() {
        String configured = oidcProperties == null ? null : oidcProperties.getTenantId();
        return StringUtils.hasText(configured) ? configured : "default";
    }

    /** break-glass username（前後空白を除き小文字化）。 */
    private Set<String> breakGlassUsernames() {
        Set<String> result = new LinkedHashSet<>();
        if (oidcProperties != null && oidcProperties.getBreakGlassUsernames() != null) {
            oidcProperties.getBreakGlassUsernames().stream()
                    .filter(StringUtils::hasText)
                    .map(name -> name.trim().toLowerCase(Locale.ROOT))
                    .forEach(result::add);
        }
        return result;
    }

    private static String trimmed(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String flagNames(Set<Capability> enabled) {
        List<String> names = new ArrayList<>();
        for (Capability capability : enabled) {
            names.add("privacy." + PrivacyDecisionBinding.capabilityKey(capability) + ".enabled");
        }
        return String.join(",", names);
    }
}
