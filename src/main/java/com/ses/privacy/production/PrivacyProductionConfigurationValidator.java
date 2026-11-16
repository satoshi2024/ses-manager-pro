package com.ses.privacy.production;

import com.ses.config.OidcSecurityProperties;
import com.ses.entity.IdentityProvider;
import com.ses.mapper.IdentityProviderMapper;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.privacy.production.PrivacyAccessGuard.Capability;
import com.ses.privacy.production.dsar.PrivacyDsarDecisions;
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
 * 本番起動時の NF07 flag ON の前提検査（W3-14b、production-bridge-phased-plan.md §3.1.1・§5.1）。
 *
 * <ul>
 *   <li>NF07 の flag（{@code privacy.retention.enabled}・{@code privacy.dsar.enabled}）が全て OFF: 何も検査せず、
 *       起動を止めない（初回リリースは OFF で配備する。OFF の間は入口が404で、DB も読まない）。</li>
 *   <li>1つでも ON: 次の全てを満たさなければ起動を失敗させる（固定の違反コードだけを列挙し、設定値・username は出さない）。
 *     <ol>
 *       <li>決定ID束縛（{@link PrivacyDecisionBinding}）に構造的な不足が無い: 承認tenantが設定tenantと一致、ON の capability が
 *           全て承認済み、必要な決定（retention は D-01・D-09、dsar は D-01・D-06・D-09）が形式・placeholder・重複・
 *           有効期間の上限の規則を満たし、approval digest が一致する。</li>
 *       <li>{@code privacy-admin} group が有効で {@code privacy.*} を許可し、privacy の拒否行を持たない。</li>
 *       <li>{@code privacy-admin} の有効な管理者（{@link UserPermissionGroupMapper#selectEffectivePrivacyAdminUsernames}。
 *           OIDC でログインできる管理者だけを数え、break-glass username は大文字小文字を区別せず除く）が
 *           {@value #MIN_PRIVACY_ADMINISTRATORS} 名以上。アカウントロックは数え方に影響しない（review P2-1）。</li>
 *       <li>{@code privacy-admin} 以外の有効な group が {@code privacy.} 接頭辞の許可行を持たない（review P3-2）。</li>
 *       <li>{@code dsar}（NF07-A2b で実検査に置換）: §5.1 の追加前提。D-06 の決定ID は上の束縛が DSAR に要求する（欠ければ
 *           {@code decisionBinding.*}）。加えて {@code privacy.dsar.due-days} が 1〜366（{@code dsar.dueDaysUnset}）、
 *           {@code privacy.dsar.package-max-age} が1時間〜366日（{@code dsar.packageMaxAgeUnset}）、F2 provider が1件以上登録済み
 *           （{@code dsar.providersUnregistered}）。署名が揃わない間は ON にできない。</li>
 *     </ol>
 *   </li>
 * </ul>
 *
 * <p>NF08・NF06 と同じく、決定の署名日前・失効のように時間の経過だけで生じる不足では起動を止めない。その場合は
 * WARN（{@value #LOG_CODE_STOPPED}）を出して起動し、{@link PrivacyDecisionWindowMonitor} が NF07 を OFF として扱う
 * （入口は OFF と同じ404。実行中に失効日を迎えた場合も同じ）。構造的な不足と同時なら起動を止める（失効日を書き換えた
 * 設定は digest 不一致になるため、時間要因だけとはみなされない）。照会できない場合は確認できないとして起動を止める（fail-closed）。
 *
 * <p>{@link SmartInitializingSingleton} で全 singleton 生成後・Tomcat の connector 開始前に実行する（失敗すると port を開かない）。
 * 同じ時点の {@code ProductionSchemaVersionGuard} より先に動くことがあるため、schema が artifact と一致しないと
 * {@code *.unverifiable} で止まる場合がある（その場合は schema guard の結果を先に確認する）。読むだけで DB へは書かない。
 */
@Component
@Profile("prod")
public class PrivacyProductionConfigurationValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PrivacyProductionConfigurationValidator.class);

    /** 登録者と有効化者の分離（NF07-REG）等の SoD に要る privacy-admin の人数（§5.1）。 */
    public static final int MIN_PRIVACY_ADMINISTRATORS = 2;

    /** 時間要因で NF07 を OFF として扱ったまま起動したことを示す固定コード（監視のlog検索用）。 */
    public static final String LOG_CODE_STOPPED = "NF07_PRIVACY_STOPPED_AT_STARTUP";

    private static final String ADMIN_ROLE = "管理者";
    private static final String OIDC_PROVIDER_TYPE = "OIDC";

    private final PrivacyFeatureProperties featureProperties;
    private final PrivacyApprovalProperties approvalProperties;
    private final OidcSecurityProperties oidcProperties;
    private final PermissionGroupMapper permissionGroupMapper;
    private final UserPermissionGroupMapper userPermissionGroupMapper;
    private final IdentityProviderMapper identityProviderMapper;
    private final ProductionPrivacyProviderRegistry providerRegistry;
    private final Clock clock;

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

    @Autowired
    public PrivacyProductionConfigurationValidator(PrivacyFeatureProperties featureProperties,
                                                   PrivacyApprovalProperties approvalProperties,
                                                   OidcSecurityProperties oidcProperties,
                                                   PermissionGroupMapper permissionGroupMapper,
                                                   UserPermissionGroupMapper userPermissionGroupMapper,
                                                   IdentityProviderMapper identityProviderMapper,
                                                   ProductionPrivacyProviderRegistry providerRegistry,
                                                   Clock clock) {
        this.featureProperties = featureProperties;
        this.approvalProperties = approvalProperties;
        this.oidcProperties = oidcProperties;
        this.permissionGroupMapper = permissionGroupMapper;
        this.userPermissionGroupMapper = userPermissionGroupMapper;
        this.identityProviderMapper = identityProviderMapper;
        this.providerRegistry = providerRegistry;
        this.clock = clock == null ? Clock.system(PrivacyDecisionBinding.BUSINESS_ZONE) : clock;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate();
    }

    /**
     * 前提を検査し、構造的な不足があれば {@link IllegalStateException} で起動を止める。
     * 時間要因（署名日前・失効）だけなら WARN を出して起動を続ける（NF07 は OFF として扱われる）。
     */
    public void validate() {
        Set<Capability> enabled = enabledCapabilities();
        if (enabled.isEmpty()) {
            log.info("[privacy] NF07のflagが全てOFFのため、決定ID束縛とprivacy-adminの起動検査を行いません");
            return;
        }
        String tenantId = authorizationTenantId();
        List<String> blocking = new ArrayList<>();

        PrivacyDecisionBinding.Evaluation binding = PrivacyDecisionBinding.evaluate(approvalProperties, enabled,
                tenantId, PrivacyDecisionBinding.businessDate(clock));
        PrivacyDecisionBinding.structuralViolations(binding).forEach(code -> blocking.add("decisionBinding." + code));
        if (enabled.contains(Capability.DSAR)) {
            validateDsarPrerequisites(blocking);
        }
        validatePrivacyAdminGroup(tenantId, blocking);
        int administrators = validatePrivacyAdministrators(tenantId, blocking);
        validateNoPrivacyAllowOutsidePrivacyAdmin(tenantId, blocking);

        List<String> temporal = new ArrayList<>();
        PrivacyDecisionBinding.temporalViolations(binding).forEach(code -> temporal.add("decisionBinding." + code));
        if (!blocking.isEmpty()) {
            List<String> all = new ArrayList<>(blocking);
            all.addAll(temporal);
            throw new IllegalStateException("本番でNF07のflag（" + flagNames(enabled)
                    + "）をONにする前提を満たさないため起動できません: " + String.join(",", all));
        }
        if (!temporal.isEmpty()) {
            // 署名日前・失効だけ: 起動は止めず、NF07 は PrivacyDecisionWindowMonitor が OFF として扱う（入口は404）。
            log.warn("{}: NF07の決定ID束縛が有効期間外のため、NF07をOFFとして扱って起動します。再承認後に設定を差し替えて"
                    + "再起動してください: flags={} violations={} privacyAdmins={}", LOG_CODE_STOPPED,
                    flagNames(enabled), String.join(",", temporal), administrators);
            return;
        }
        log.info("[privacy] NF07のflag ONの前提を確認しました: flags={} decisionIds={} privacyAdmins={}",
                flagNames(enabled), binding.snapshot().decisionIds().values(), administrators);
    }

    /**
     * DSAR を ON にする追加の前提（§5.1 の表。NF07-A2b）。D-06 の決定ID（本人確認基準・期限規則・package の最長保存期間の根拠）は
     * {@link PrivacyDecisionBinding#requiredDecisions} が DSAR に要求し、欠落・placeholder・digest 不一致は {@code decisionBinding.*}
     * として既に数える。ここでは決定書に従って設定する値と F2 provider の登録を確かめる。
     */
    private void validateDsarPrerequisites(List<String> missing) {
        PrivacyFeatureProperties.Dsar dsar = featureProperties == null ? null : featureProperties.getDsar();
        if (dsar == null || !PrivacyDsarDecisions.isValidDueDays(dsar.getDueDays())) {
            missing.add("dsar.dueDaysUnset");
        }
        if (dsar == null || !PrivacyDsarDecisions.isValidPackageMaxAge(dsar.getPackageMaxAge())) {
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
            missing.add("privacyAdmins.unverifiable");
            return -1;
        }
        if (usernames == null) {
            missing.add("privacyAdmins.unverifiable");
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

    private Set<Capability> enabledCapabilities() {
        Set<Capability> enabled = EnumSet.noneOf(Capability.class);
        if (featureProperties == null) {
            return enabled;
        }
        if (featureProperties.getRetention() != null && featureProperties.getRetention().isEnabled()) {
            enabled.add(Capability.RETENTION);
        }
        if (featureProperties.getDsar() != null && featureProperties.getDsar().isEnabled()) {
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
