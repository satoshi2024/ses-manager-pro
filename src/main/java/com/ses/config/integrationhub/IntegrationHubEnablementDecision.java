package com.ses.config.integrationhub;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.condition.MasterSwitchConditionEvaluator;
import com.ses.featureenablement.condition.SecretPresenceConditionEvaluator;
import com.ses.featureenablement.condition.SignOffConditionEvaluator;
import com.ses.featureenablement.service.FeatureSignOffRegistrar;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.service.integrationhub.IntegrationHubDigest;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * NF05 の開閉の判定（feature-enablement-console design §9.1 の 0・§9.4。FE-D01）。普通の値（開放状態・確認済みの署名の値と期間・
 * barrier の行・OFF の監査の有無・bind した properties・総スイッチ・今日）を入力にする純粋な関数で、起動の前の読取り
 * （{@code IntegrationHubEnablementBootResolver}。JDBC で入力を作る）と評価器（{@code IntegrationHubEnablementConditions}。mapper と
 * snapshot で入力を作る）が共用する（判定の写しを持たない。review P3-14）。
 *
 * <ul>
 *   <li>総スイッチ・署名・鍵の判定は共通の評価器（{@link MasterSwitchConditionEvaluator}・{@link SignOffConditionEvaluator}・
 *       {@link SecretPresenceConditionEvaluator}）をそのまま呼ぶ。評価器の経路では registry が同じ共通の評価器を呼ぶため、評価器は
 *       {@link #specificChecks} だけを返す。</li>
 *   <li>固有の判定: {@link IntegrationHubExternalApiProperties#boundaryViolations} と
 *       {@link IntegrationHubProductionEnablementPolicy#violationList}（承認の値は {@link IntegrationHubApprovals}）、outbound の
 *       barrier の行の tenant の指紋（継続の条件。phase は問わない）と、開放の遷移の前提（{@code barrier.offRecorded}: phase が
 *       {@code OFF} で {@code NF05_OUTBOUND_OFF} の監査がある。申請・承認・{@code BOOT_PENDING} だけ。review P1-1）。</li>
 *   <li>起動の判定（{@link #decide}）: 開放状態が {@code OPEN}・{@code OPEN_PENDING_RESTART} で、全ての check が成り立つ時だけ開。
 *       {@code OPEN} は {@code STARTUP}（指紋だけ）、{@code OPEN_PENDING_RESTART} は {@code BOOT_PENDING}（OFF の記録も要る）で判定する
 *       （既に開いている outbound の再起動で OFF を要求しない）。</li>
 *   <li>purge 用の property（{@link #purgeEnabled}）: どちらかの行が一度でも開いた（{@code opened_at} が非 NULL）か、保存期間の対象の表に
 *       行があれば true（review P2-5）。読めない時の true は呼出元（boot resolver）が決める（review P3-N3）。</li>
 * </ul>
 * code は固定の文字列だけ（値・digest・tenant を含めない）。
 */
public final class IntegrationHubEnablementDecision {

    /** barrier の行の key（{@code IntegrationHubOutboundStartupBarrier.CONFIG_KEY} と同じ）。 */
    public static final String BARRIER_CONFIG_KEY = "integration.hub.outbound.runtime.v1";
    /** barrier の OFF の監査（{@code t_audit_log.application_code}。{@code reference_type='NF05'}・{@code reference_id=0}）。 */
    public static final String OFF_AUDIT_CODE = "NF05_OUTBOUND_OFF";
    public static final String AUDIT_REFERENCE_TYPE = "NF05";
    /** 起動の判定の結果の property の値。 */
    public static final String RESULT_OPENED = "OPENED";
    public static final String RESULT_READ_FAILED = "READ_FAILED";
    public static final String RESULT_CLOSED_PREFIX = "CLOSED:";
    /** 総スイッチが閉の時の理由（起動時の評価と同じ code）。 */
    public static final String MASTER_SWITCH_CLOSED = "MASTER_SWITCH_CLOSED";
    /** barrier の行の継続の条件の code。 */
    public static final String CODE_BARRIER_FINGERPRINT = "barrier.tenantFingerprint";
    /** barrier の行・監査を読めない（評価できない類）。 */
    public static final String CODE_STATE_IO_ERROR = "state=STATE_IO_ERROR";
    /** truststore を読まなかった（lock を持つ transaction の中で最後の結果も無い。評価できない類）。 */
    public static final String CODE_TRUSTSTORE_UNVERIFIABLE = "nf05.outbound.truststore.unverifiable";

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final Pattern BARRIER_VALUE =
            Pattern.compile("v1[|](OFF|ON|QUARANTINING)[|][0-9]{1,19}[|]([0-9a-f]{64})");
    private static final int REASON_MAX = 64;

    private IntegrationHubEnablementDecision() {
    }

    /**
     * 1つの方向の開放状態。
     *
     * @param state       開放状態（行が無ければ null）
     * @param version     行の版（行が無ければ -1）
     * @param everOpened  一度でも開いたか（{@code opened_at} が非 NULL）
     */
    public record DirectionState(String state, int version, boolean everOpened) {
        public static final DirectionState NONE = new DirectionState(null, -1, false);

        boolean openLike() {
            return "OPEN".equals(state) || "OPEN_PENDING_RESTART".equals(state);
        }
    }

    /**
     * barrier の行の読取り。
     *
     * @param readable 読めたか（読めなければ評価できない）
     * @param value    {@code config_value}（行が無ければ null）
     */
    public record BarrierRead(boolean readable, String value) {
        public static BarrierRead unreadable() {
            return new BarrierRead(false, null);
        }
    }

    /**
     * truststore の読取り。
     *
     * @param attempted 読んだか（lock を持つ transaction の中では読まない。最後の結果も無ければ false で評価できない）
     * @param content   読めた byte 列（読めなければ null）
     */
    public record TruststoreRead(boolean attempted, byte[] content) {
        public static TruststoreRead notAttempted() {
            return new TruststoreRead(false, null);
        }
    }

    /**
     * 判定の入力（普通の値だけ。DB・Spring の bean を含めない）。
     *
     * @param tenantId             設定の tenant
     * @param today                JST の業務日
     * @param masterAllowed        総スイッチが許可か
     * @param inbound              inbound の開放状態
     * @param outbound             outbound の開放状態
     * @param signOffs             確認済みの署名（種別 → 内容）
     * @param barrier              barrier の行
     * @param offAuditRecorded     {@code NF05_OUTBOUND_OFF} の監査があるか（読まなかった・読めなければ null）
     * @param retentionRowsPresent NF05 の保存期間の対象の表に行があるか
     * @param properties           bind した {@code integration.hub.*}
     * @param environment          鍵の有無と credential の検査に使う環境（null なら鍵は無い扱い）
     * @param approvals            承認の値（null なら確認済みの署名から作る）
     * @param truststore           outbound の truststore の読取り
     */
    public record BootInputs(String tenantId, LocalDate today, boolean masterAllowed, DirectionState inbound,
                             DirectionState outbound, Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> signOffs,
                             BarrierRead barrier, Boolean offAuditRecorded, boolean retentionRowsPresent,
                             IntegrationHubExternalApiProperties properties, Environment environment,
                             IntegrationHubApprovals approvals, TruststoreRead truststore) {

        public BootInputs {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(today, "today");
            Objects.requireNonNull(properties, "properties");
            inbound = inbound == null ? DirectionState.NONE : inbound;
            outbound = outbound == null ? DirectionState.NONE : outbound;
            signOffs = signOffs == null ? Map.of() : Map.copyOf(signOffs);
            barrier = barrier == null ? BarrierRead.unreadable() : barrier;
            truststore = truststore == null ? TruststoreRead.notAttempted() : truststore;
        }

        /** 承認の値（明示が無ければ確認済みの署名から）。 */
        public IntegrationHubApprovals effectiveApprovals() {
            return approvals != null ? approvals : approvalsFromSignOffs(signOffs);
        }

        DirectionState state(Feature feature) {
            return feature == Feature.NF05_INBOUND ? inbound : outbound;
        }
    }

    /**
     * 1つの方向の起動の判定。
     *
     * @param open 開くか
     * @param code 閉じる理由の code（開くなら null。64 文字以内）
     */
    public record Result(boolean open, String code) {
        static Result opened() {
            return new Result(true, null);
        }

        static Result closed(String code) {
            return new Result(false, truncate(code));
        }

        /** property の値（{@code OPENED} / {@code CLOSED:<code>}）。 */
        public String propertyValue() {
            return open ? RESULT_OPENED : RESULT_CLOSED_PREFIX + code;
        }
    }

    /** 起動の判定の結果。 */
    public record Decision(Result inbound, Result outbound, boolean purgeEnabled) {
    }

    /** 起動の判定（boot resolver）。 */
    public static Decision decide(BootInputs inputs, FeatureEnablementCatalog catalog) {
        return new Decision(decideDirection(Feature.NF05_INBOUND, inputs, catalog),
                decideDirection(Feature.NF05_OUTBOUND, inputs, catalog), purgeEnabled(inputs));
    }

    private static Result decideDirection(Feature feature, BootInputs inputs, FeatureEnablementCatalog catalog) {
        DirectionState state = inputs.state(feature);
        if (!state.openLike()) {
            return Result.closed("state." + (state.state() == null ? "NONE" : state.state()));
        }
        if (!inputs.masterAllowed()) {
            return Result.closed(MASTER_SWITCH_CLOSED);
        }
        FeatureEvaluationContext.Purpose purpose = "OPEN_PENDING_RESTART".equals(state.state())
                ? FeatureEvaluationContext.Purpose.BOOT_PENDING : FeatureEvaluationContext.Purpose.STARTUP;
        for (FeatureConditionCheck check : checks(feature, inputs, purpose, catalog)) {
            if (!check.passing()) {
                return Result.closed(check.code());
            }
        }
        return Result.opened();
    }

    /** purge 用の property（どちらかが一度でも開いたか、保存期間の対象の表に行がある。review P2-5）。 */
    public static boolean purgeEnabled(BootInputs inputs) {
        return inputs.inbound().everOpened() || inputs.outbound().everOpened() || inputs.retentionRowsPresent();
    }

    /**
     * 全ての check（総スイッチ・署名・鍵の共通の評価器と固有の check）。開放の遷移の前提は {@code purpose} が判定する目的の時だけ含める。
     */
    public static List<FeatureConditionCheck> checks(Feature feature, BootInputs inputs,
                                                     FeatureEvaluationContext.Purpose purpose,
                                                     FeatureEnablementCatalog catalog) {
        requireNf05(feature);
        FeatureEvaluationContext context = context(inputs, purpose, feature);
        List<FeatureConditionCheck> checks = new ArrayList<>(new MasterSwitchConditionEvaluator().evaluate(feature, context));
        checks.addAll(new SignOffConditionEvaluator(catalog).evaluate(feature, context));
        if (inputs.environment() != null) {
            checks.addAll(new SecretPresenceConditionEvaluator(catalog, inputs.environment()).evaluate(feature, context));
        }
        checks.addAll(specificChecks(feature, inputs, purpose));
        return checks;
    }

    /** 固有の check（境界・承認の検査と barrier。評価器が返す部分）。 */
    public static List<FeatureConditionCheck> specificChecks(Feature feature, BootInputs inputs,
                                                             FeatureEvaluationContext.Purpose purpose) {
        requireNf05(feature);
        boolean inbound = feature == Feature.NF05_INBOUND;
        List<FeatureConditionCheck> checks = new ArrayList<>();
        IntegrationHubExternalApiProperties properties = inputs.properties();
        List<IntegrationHubProductionEnablementPolicy.Violation> violations = new ArrayList<>(
                IntegrationHubExternalApiProperties.boundaryViolations(properties, inbound, !inbound));
        TruststoreRead truststore = inputs.truststore();
        violations.addAll(IntegrationHubProductionEnablementPolicy.violationList(properties, inputs.environment(), inbound,
                !inbound, inputs.effectiveApprovals(), inbound ? null : truststore.content()));
        String prefix = inbound ? "nf05.inbound" : "nf05.outbound";
        for (IntegrationHubProductionEnablementPolicy.Violation violation : violations) {
            if (!inbound && !truststore.attempted() && "nf05.outbound.truststore.unreadable".equals(violation.code())) {
                checks.add(FeatureConditionCheck.unverifiable(CODE_TRUSTSTORE_UNVERIFIABLE,
                        FeatureConditionCheck.Category.AUTO));
                continue;
            }
            checks.add(FeatureConditionCheck.unsatisfied(violation.code(), FeatureConditionCheck.Category.AUTO, false));
        }
        if (violations.isEmpty()) {
            checks.add(FeatureConditionCheck.satisfied(prefix + ".policy", FeatureConditionCheck.Category.AUTO, null));
        }
        if (!inbound) {
            checks.addAll(barrierChecks(inputs, purpose));
        }
        return checks;
    }

    /** outbound の barrier の行（継続の条件）と OFF の記録（開放の遷移の前提）。 */
    static List<FeatureConditionCheck> barrierChecks(BootInputs inputs, FeatureEvaluationContext.Purpose purpose) {
        List<FeatureConditionCheck> checks = new ArrayList<>();
        BarrierRead barrier = inputs.barrier();
        String phase = null;
        if (!barrier.readable()) {
            checks.add(FeatureConditionCheck.unverifiable(CODE_STATE_IO_ERROR, FeatureConditionCheck.Category.AUTO));
        } else if (barrier.value() == null) {
            checks.add(FeatureConditionCheck.unsatisfied(CODE_BARRIER_FINGERPRINT + ".missing",
                    FeatureConditionCheck.Category.AUTO, false));
        } else {
            java.util.regex.Matcher matcher = BARRIER_VALUE.matcher(barrier.value());
            if (!matcher.matches()) {
                checks.add(FeatureConditionCheck.unsatisfied(CODE_BARRIER_FINGERPRINT + ".invalid",
                        FeatureConditionCheck.Category.AUTO, false));
            } else if (!matcher.group(2).equals(barrierFingerprint(inputs.properties()))) {
                // 全ての起動で同じ bound-tenant-id を設定する規則（RB §1.7）。値を変えた起動は閉じる
                checks.add(FeatureConditionCheck.unsatisfied(CODE_BARRIER_FINGERPRINT + ".mismatch",
                        FeatureConditionCheck.Category.AUTO, false));
            } else {
                phase = matcher.group(1);
                checks.add(FeatureConditionCheck.satisfied(CODE_BARRIER_FINGERPRINT, FeatureConditionCheck.Category.AUTO,
                        null));
            }
        }
        if (purpose.judgesOpenTransition()) {
            String code = FeatureEnablementCatalog.CODE_NF05_BARRIER_OFF;
            if (!barrier.readable() || inputs.offAuditRecorded() == null) {
                checks.add(FeatureConditionCheck.unverifiable(code, FeatureConditionCheck.Category.AUTO));
            } else if ("OFF".equals(phase) && inputs.offAuditRecorded()) {
                checks.add(FeatureConditionCheck.satisfied(code, FeatureConditionCheck.Category.AUTO, null));
            } else {
                String detail = !"OFF".equals(phase) ? "barrier.phase." + (phase == null ? "UNKNOWN" : phase)
                        : "barrier.offAudit.missing";
                checks.add(new FeatureConditionCheck(code, FeatureConditionCheck.Category.AUTO,
                        FeatureConditionCheck.Scope.OPEN_TRANSITION, FeatureConditionCheck.Status.UNSATISFIED, false, null,
                        List.of(detail)));
            }
        }
        return checks;
    }

    /** barrier の行の tenant の指紋（{@code sha256(bound-tenant-id)}。{@code IntegrationHubOutboundStartupBarrier} と同じ）。 */
    public static String barrierFingerprint(IntegrationHubExternalApiProperties properties) {
        String tenant = properties.getTopology() == null ? null : properties.getTopology().getBoundTenantId();
        return IntegrationHubDigest.sha256Hex(tenant == null ? "" : tenant);
    }

    /** 確認済みの署名から承認の値を作る（{@code NF05_DG05_IN}・{@code NF05_DG05_OUT} の参照と {@code approvalDigest}）。 */
    public static IntegrationHubApprovals approvalsFromSignOffs(
            Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> signOffs) {
        return new IntegrationHubApprovals(decisionOf(signOffs.get("NF05_DG05_IN")),
                decisionOf(signOffs.get("NF05_DG05_OUT")));
    }

    private static IntegrationHubApprovals.Decision decisionOf(FeatureEnablementSnapshot.ConfirmedSignOff signOff) {
        if (signOff == null) {
            return IntegrationHubApprovals.Decision.NONE;
        }
        String digest;
        try {
            digest = FeatureSignOffRegistrar.structuredMap(signOff.structuredValues()).get("approvalDigest");
        } catch (RuntimeException unreadable) {
            digest = null;
        }
        return new IntegrationHubApprovals.Decision(signOff.reference(), digest);
    }

    /** truststore の file を1回だけ読む（読めない・空・1 MiB 超過なら null。path を例外に出さない）。 */
    public static byte[] readTruststore(String path) {
        return IntegrationHubApprovalDigest.readTruststore(path);
    }

    /**
     * 現在の配備の設定から計算した承認内容の digest（画面の DG-05 の一致・不一致の表示。FE-A06 の残り。値は返さず一致だけを返す呼出しで
     * 使う）。tenant・truststore が digest の形式に合わなければ null。
     */
    public static String currentDigest(Feature feature, IntegrationHubExternalApiProperties properties, String decisionId,
                                       byte[] truststoreContent) {
        requireNf05(feature);
        String canonical = feature == Feature.NF05_INBOUND
                ? IntegrationHubApprovalDigest.canonicalInbound(properties, decisionId)
                : IntegrationHubApprovalDigest.canonicalOutbound(properties, decisionId,
                        IntegrationHubApprovalDigest.sha256Hex(truststoreContent));
        return IntegrationHubApprovalDigest.digest(canonical);
    }

    private static FeatureEvaluationContext context(BootInputs inputs, FeatureEvaluationContext.Purpose purpose,
                                                    Feature feature) {
        Instant start = inputs.today().atStartOfDay(JST).toInstant();
        FeatureEnablementSnapshot snapshot = new FeatureEnablementSnapshot(0L, start, start, inputs.masterAllowed(),
                Map.of(), Map.of(), inputs.signOffs(), Map.of());
        return new FeatureEvaluationContext(inputs.tenantId(), inputs.today(), Clock.fixed(start, JST), snapshot, purpose,
                Set.of(feature), false);
    }

    private static void requireNf05(Feature feature) {
        if (feature != Feature.NF05_INBOUND && feature != Feature.NF05_OUTBOUND) {
            throw new IllegalArgumentException("NF05 の開放単位ではありません");
        }
    }

    private static String truncate(String code) {
        return code == null || code.length() <= REASON_MAX ? code : code.substring(0, REASON_MAX);
    }
}
