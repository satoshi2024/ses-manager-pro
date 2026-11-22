package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.config.AdministratorConditionCheck;
import com.ses.config.AdministratorConditionCheck.Condition;
import com.ses.config.AdministratorStartupConditions;
import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 管理者の継続性guard（RB-ADMIN-GUARD、deploy-runbook §5.6）。
 *
 * <p>本番で、管理者の人数・状態に関する起動時検査（OIDCでログインできる管理者（break-glass以外）3名以上、
 * break-glass管理者2名（有効な管理者・MFA登録済み）、privacy-adminに所属しOIDCでログインできる有効な管理者2名以上）を
 * <b>変更の前は満たし、変更の後は満たさなくなる</b>書込みを、transactionごとrollbackして拒否する。人数の不足は従来、
 * 次の再起動で初めて起動時検査が止めて分かり、復旧にDBAかbootstrapの再開（§5.7）が要った。</p>
 *
 * <ul>
 *   <li><b>適用条件</b>: {@code Environment#acceptsProfiles("prod")} のときだけ判定する（起動時検査と同じ
 *       {@code @Profile("prod")} の判定。{@code spring.profiles.active} の値だけは見ない）。prod以外は何もしない。</li>
 *   <li><b>判定</b>: 起動時検査そのもの（{@link AdministratorStartupConditions}。{@code ProductionSecurityConfigurationValidator}・
 *       {@code PrivacyProductionConfigurationValidator}）を変更の前後に同じtransactionで呼び、条件の写しを持たない。
 *       起動時検査が判定しない条件（bootstrap中のOIDC管理者・break-glass、OIDCのtenant・issuerが未設定のOIDC管理者）は
 *       判定しない。privacy-adminはNF07のflagのON/OFFに関わらず判定する。</li>
 *   <li><b>前から満たさない条件では拒否しない</b>: bootstrapで人数を揃えている途中などの変更を止めない（壊れていない状態を
 *       壊す変更だけを止める）。</li>
 *   <li><b>直列化</b>: {@link #lockAndCapture} は、全tenantの未削除の管理者と変更対象の利用者の {@code sys_user} 行を
 *       {@code (tenant_id, id)} の昇順でX lockしてから変更前の判定を取る（NF01 lock rank 3 の最初のlockとして、呼出側が
 *       ほかのrank行をlockする前に呼ぶ。対象の行のlockを兼ねる）。lock後に発見をやり直し、lockしていない管理者が
 *       増えていれば409で止める。呼出側は READ_COMMITTED にする（lockを待つ間にcommitされた変更を、lock後の照会が見る）。
 *       2名の管理者が同時に別々の管理者を無効化しても、後の操作は先のcommitを待ってから数え直す。</li>
 *   <li>{@link #captureLocked} は、変更する利用者の行を呼出側が既にX lockしている経路（要員の削除・退社案件の完了による
 *       連携ユーザーの無効化。NF01のlock計画を変えない）で、追加のlockを取らずに変更前の判定を取る。この経路どうしの
 *       同時の変更は直列化しない（{@link #lockAndCapture} の経路とは対象の行のlockで直列化される）。</li>
 *   <li>同じtransactionで入れ子に呼ばれた場合（利用者の更新から権限groupの再割当）は外側の判定に任せる。</li>
 *   <li><b>拒否</b>: {@link BusinessException}（400。既存の自己lockout防止と同じ）で、どの条件か・変更後の人数・必要な人数を
 *       返す。transactionをrollback-onlyにしてから投げ、rollback後に独立transactionで監査
 *       {@value #AUDIT_APPLICATION_CODE} を残す（best-effort。lock保持中に別transactionを開かない。NF01 §3.5 規則6）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdministratorContinuityGuard {

    /** 拒否の監査の application_code。 */
    public static final String AUDIT_APPLICATION_CODE = "ADMIN_CONTINUITY_DENIED";
    /** 拒否の監査の method（{@code t_audit_log.method} は VARCHAR(10)）。 */
    static final String AUDIT_METHOD = "SECURITY";
    static final String ADMIN_ROLE = "管理者";
    private static final String PRODUCTION_PROFILE = "prod";
    private static final int AUDIT_URI_MAX = 500;

    /** 条件ごとの拒否の文言（引数: 変更後の人数、必要な人数）。 */
    static final Map<Condition, String> MESSAGE_KEYS = Map.of(
            Condition.OIDC_ADMINISTRATORS, "error.user.adminContinuity.oidcAdministrators",
            Condition.BREAK_GLASS_ADMINISTRATORS, "error.user.adminContinuity.breakGlassAdministrators",
            Condition.PRIVACY_ADMINISTRATORS, "error.user.adminContinuity.privacyAdministrators");

    /** 同じtransactionで判定を開いていることの印（transactionの完了か {@link #verify} で外す）。 */
    private static final Object TRANSACTION_RESOURCE_KEY = new Object();

    private final Environment environment;
    private final SysUserMapper sysUserMapper;
    private final ObjectProvider<AdministratorStartupConditions> conditionSources;
    private final ObjectProvider<AuditLogService> auditLogService;
    private final ObjectProvider<PlatformTransactionManager> transactionManager;

    /** 本番（{@code prod} profile）か。prod以外では判定しない。 */
    public boolean isActive() {
        return environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE));
    }

    /**
     * 全tenantの未削除の管理者と {@code targetUserIds} の利用者行を {@code (tenant_id, id)} 昇順でX lockし、変更前の判定を取る。
     * NF01 lock rank 3 の最初のlockとして、READ_COMMITTED のtransactionの中で呼ぶ。
     *
     * @param operation     監査・logに残す操作名（固定値）
     * @param targetUserIds 変更する利用者（作成ではまだ無いので空）
     * @throws BusinessException 409 lockの間に管理者が増えた（lockしていない管理者が居る）場合
     */
    public Snapshot lockAndCapture(String operation, Collection<Long> targetUserIds) {
        if (!isActive()) {
            return Snapshot.inactive();
        }
        requireTransaction();
        if (TransactionSynchronizationManager.hasResource(TRANSACTION_RESOURCE_KEY)) {
            return Snapshot.nested();
        }
        List<Long> targets = normalizedTargets(targetUserIds);
        Set<Long> locked = lockAdministratorsAndTargets(targets);
        // lock後の発見（READ_COMMITTED の新しい読取）。lockしていない管理者が増えていれば、その管理者の変更とは
        // 直列化できていないため、lockを途中で足さず（rank順を崩さず）409で止める。
        for (SysUser current : sysUserMapper.selectAdministratorContinuityLockTargets(ADMIN_ROLE, targets)) {
            if (current != null && current.getId() != null && !locked.contains(current.getId())) {
                log.info("[admin-continuity] lockの間に管理者が増えたため操作を中断します: operation={} userId={}",
                        operation, current.getId());
                throw BusinessException.of(409, "error.common.optimisticLock");
            }
        }
        return open(operation, targets);
    }

    /**
     * 変更する利用者の行を呼出側が既にX lockしている経路で、追加のlockを取らずに変更前の判定を取る
     * （要員の削除・退社案件の完了による連携ユーザーの無効化。NF01のlock計画を変えない）。
     */
    public Snapshot captureLocked(String operation, Long lockedUserId) {
        if (!isActive()) {
            return Snapshot.inactive();
        }
        requireTransaction();
        if (TransactionSynchronizationManager.hasResource(TRANSACTION_RESOURCE_KEY)) {
            return Snapshot.nested();
        }
        return open(operation, normalizedTargets(lockedUserId == null ? List.of() : List.of(lockedUserId)));
    }

    /**
     * 変更後の判定を取り、変更前は満たし変更後は満たさない条件があれば、transactionをrollback-onlyにして拒否する。
     * 入れ子・prod以外の判定（{@link Snapshot#owner} でない）では何もしない。
     *
     * @throws BusinessException 400 起動時検査を満たさなくなる変更の場合
     */
    public void verify(Snapshot snapshot) {
        if (snapshot == null || !snapshot.owner()) {
            return;
        }
        try {
            List<AdministratorConditionCheck> after = evaluate();
            List<Violation> violations = violations(snapshot.before(), after);
            if (violations.isEmpty()) {
                return;
            }
            Violation first = violations.get(0);
            String detail = describe(violations);
            log.warn("[admin-continuity] 本番の起動時検査を満たさなくなる変更を拒否しました: operation={} targets={} {}",
                    snapshot.operation(), snapshot.targetUserIds(), detail);
            markRollbackOnly();
            registerDenialAudit(SecurityUtils.currentUsername(), auditUri(snapshot, detail));
            throw BusinessException.of(400, MESSAGE_KEYS.get(first.condition()),
                    first.after().count(), first.after().required());
        } finally {
            // 同じtransactionで続く別の操作は、この操作の後の状態から判定し直す。
            TransactionSynchronizationManager.unbindResourceIfPossible(TRANSACTION_RESOURCE_KEY);
        }
    }

    private Snapshot open(String operation, List<Long> targets) {
        List<AdministratorConditionCheck> before = evaluate();
        TransactionSynchronizationManager.bindResource(TRANSACTION_RESOURCE_KEY, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(TRANSACTION_RESOURCE_KEY);
            }
        });
        return new Snapshot(operation, targets, before, true);
    }

    /** 発見した管理者と対象を {@code (tenant_id, id)} 昇順で1行ずつX lockする（lock rank 3 の同rank昇順。NF01 §3.5 規則3）。 */
    private Set<Long> lockAdministratorsAndTargets(List<Long> targets) {
        List<SysUser> ordered = new ArrayList<>();
        for (SysUser candidate : sysUserMapper.selectAdministratorContinuityLockTargets(ADMIN_ROLE, targets)) {
            if (candidate != null && candidate.getId() != null) {
                ordered.add(candidate);
            }
        }
        ordered.sort(Comparator.comparing((SysUser user) -> user.getTenantId() == null ? "" : user.getTenantId())
                .thenComparing(SysUser::getId));
        Set<Long> locked = new LinkedHashSet<>();
        for (SysUser candidate : ordered) {
            if (locked.add(candidate.getId())) {
                sysUserMapper.selectByIdForUpdateIncludingDeleted(candidate.getId());
            }
        }
        return locked;
    }

    /** 起動時検査の判定を集める。prodで条件が揃わなければ確認できないとして止める（fail-closed）。 */
    private List<AdministratorConditionCheck> evaluate() {
        Map<Condition, AdministratorConditionCheck> byCondition = new EnumMap<>(Condition.class);
        conditionSources.orderedStream().forEach(source -> {
            List<AdministratorConditionCheck> checks = source.checkAdministratorConditions();
            if (checks != null) {
                for (AdministratorConditionCheck check : checks) {
                    if (check != null && check.condition() != null) {
                        byCondition.put(check.condition(), check);
                    }
                }
            }
        });
        if (byCondition.size() != Condition.values().length) {
            throw new IllegalStateException("管理者の起動時条件を確認できません（起動時検査の判定が揃いません: "
                    + byCondition.keySet() + "）");
        }
        return List.copyOf(byCondition.values());
    }

    private static List<Violation> violations(List<AdministratorConditionCheck> before,
                                              List<AdministratorConditionCheck> after) {
        Map<Condition, AdministratorConditionCheck> afterByCondition = new EnumMap<>(Condition.class);
        after.forEach(check -> afterByCondition.put(check.condition(), check));
        List<Violation> violations = new ArrayList<>();
        for (AdministratorConditionCheck previous : before) {
            AdministratorConditionCheck current = afterByCondition.get(previous.condition());
            if (previous.evaluated() && previous.satisfied() && current != null && current.blocksStartup()) {
                violations.add(new Violation(previous.condition(), previous, current));
            }
        }
        return violations;
    }

    private static String describe(List<Violation> violations) {
        return "violations=" + violations.stream()
                .map(v -> v.condition().name() + "(before=" + v.before().count() + ",after=" + v.after().count()
                        + ",required=" + v.after().required() + ")")
                .collect(Collectors.joining(","));
    }

    private static String auditUri(Snapshot snapshot, String detail) {
        String uri = "/internal/admin-continuity/deny?operation=" + snapshot.operation()
                + "&targets=" + snapshot.targetUserIds().stream().map(String::valueOf).collect(Collectors.joining(","))
                + "&" + detail;
        return uri.length() <= AUDIT_URI_MAX ? uri : uri.substring(0, AUDIT_URI_MAX);
    }

    /**
     * 拒否したtransactionのrollback後に、独立transaction（REQUIRES_NEW）で監査を残す。lockを保持したまま別transactionを
     * 開かない（NF01 §3.5 規則6）ため、完了後に書く。書けなくても拒否は変わらない（best-effort。WARNを残す）。
     */
    private void registerDenialAudit(String username, String uri) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                writeDenialAudit(username, uri);
            }
        });
    }

    private void writeDenialAudit(String username, String uri) {
        AuditLogService audit = auditLogService.getIfAvailable();
        PlatformTransactionManager manager = transactionManager.getIfAvailable();
        if (audit == null || manager == null) {
            log.warn("[admin-continuity] 拒否の監査を記録できません（監査またはtransaction managerが利用できません）: uri={}", uri);
            return;
        }
        try {
            TransactionTemplate template = new TransactionTemplate(manager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.executeWithoutResult(status -> audit.record(username, AUDIT_METHOD, uri, 400,
                    AUDIT_APPLICATION_CODE, false));
        } catch (RuntimeException e) {
            log.warn("[admin-continuity] 拒否の監査を記録できませんでした: uri={} exceptionClass={}", uri,
                    e.getClass().getSimpleName());
        }
    }

    /** 呼出側が例外を握りつぶしてもcommitさせない（rollback-only）。 */
    private static void markRollbackOnly() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (NoTransactionException ignored) {
            // 宣言的transactionの外（手動のtransaction等）では、投げる例外で呼出側がrollbackする。
        }
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("管理者の継続性guardはtransactionの中で呼んでください");
        }
    }

    private static List<Long> normalizedTargets(Collection<Long> targetUserIds) {
        if (targetUserIds == null || targetUserIds.isEmpty()) {
            return List.of();
        }
        return List.copyOf(targetUserIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new)));
    }

    private record Violation(Condition condition, AdministratorConditionCheck before, AdministratorConditionCheck after) {
    }

    /**
     * 変更前の判定。{@link #owner} が true の判定だけが {@link #verify} で比べられる（prod以外・入れ子は何もしない）。
     */
    public record Snapshot(String operation, List<Long> targetUserIds, List<AdministratorConditionCheck> before,
                           boolean owner) {

        private static final Snapshot INACTIVE = new Snapshot("INACTIVE", List.of(), List.of(), false);
        private static final Snapshot NESTED = new Snapshot("NESTED", List.of(), List.of(), false);

        /** 判定しない（prod以外、または guard を配線していないテストスライス）。 */
        public static Snapshot inactive() {
            return INACTIVE;
        }

        /** 同じtransactionの外側の判定に任せる。 */
        static Snapshot nested() {
            return NESTED;
        }
    }
}
