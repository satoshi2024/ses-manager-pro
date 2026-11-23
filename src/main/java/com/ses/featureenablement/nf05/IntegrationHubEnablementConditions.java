package com.ses.featureenablement.nf05;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.config.feature.Feature;
import com.ses.config.integrationhub.IntegrationHubApprovals;
import com.ses.config.integrationhub.IntegrationHubEnablementDecision;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.entity.AuditLog;
import com.ses.entity.SystemConfig;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureConditionEvaluator;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.mapper.AuditLogMapper;
import com.ses.mapper.SystemConfigMapper;
import com.ses.service.security.LegalEntityReadinessService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * NF05 inbound・outbound の固有の評価器（feature-enablement-console design §9.4・§9.5、requirements §6.8・6.9。FE-D05）。申請・承認と
 * 起動時・定期の評価で、起動の前の読取りと同じ純粋な関数（{@link IntegrationHubEnablementDecision#specificChecks}）を mapper と snapshot
 * で作った入力で呼ぶ（判定の写しを持たない）。総スイッチ・署名・鍵は共通の評価器が判定する。
 *
 * <ul>
 *   <li>境界と承認の検査（{@code nf05.*}）: 承認の値は、DB の源では確認済みの署名（{@code NF05_DG05_IN}・{@code NF05_DG05_OUT}）、
 *       property の源では配備の設定。digest が現在の配備の設定と合わなければ {@code nf05.<方向>.approvalDigest.mismatch}（画面の
 *       DG-05 の一致・不一致はこの code で出る）。</li>
 *   <li>outbound の barrier: 継続の条件は行の tenant の指紋（{@code barrier.tenantFingerprint}。phase は問わない。{@code QUARANTINING}・
 *       {@code ON} でも成り立つ）。開放の遷移の前提（{@code barrier.offRecorded}）は申請・承認だけ（{@code OPEN_TRANSITION}）。行・監査を
 *       読めなければ {@code state=STATE_IO_ERROR}（評価できない類）。OFF の監査の照会は {@code reference_type='NF05'} を付けて
 *       {@code idx_auditlog_reference} を使う（review P3-N8）。</li>
 *   <li>truststore: 外部への検査を許す評価だけ file を読み、lock を持つ transaction の中（{@code remoteChecksAllowed=false}）は最後に読んだ
 *       内容を使う（無ければ {@code nf05.outbound.truststore.unverifiable}。review P3-20）。</li>
 *   <li>法人の未解決（{@code legalEntity.unresolved}。判定できなければ {@code legalEntity.unverifiable}）。</li>
 * </ul>
 */
@Component
public class IntegrationHubEnablementConditions implements FeatureConditionEvaluator {

    public static final String CODE_LEGAL_ENTITY = "legalEntity.resolved";
    public static final String CODE_LEGAL_ENTITY_UNRESOLVED = "legalEntity.unresolved";
    public static final String CODE_LEGAL_ENTITY_UNVERIFIABLE = "legalEntity.unverifiable";

    private final IntegrationHubExternalApiProperties properties;
    private final Environment environment;
    private final SystemConfigMapper configs;
    private final AuditLogMapper audits;
    private final ObjectProvider<FeatureEnablementProperties> featureProperties;
    private final ObjectProvider<LegalEntityReadinessService> readiness;
    /** 最後に読んだ truststore（lock を持つ transaction の中の評価が使う）。 */
    private volatile IntegrationHubEnablementDecision.TruststoreRead lastTruststore =
            IntegrationHubEnablementDecision.TruststoreRead.notAttempted();

    public IntegrationHubEnablementConditions(IntegrationHubExternalApiProperties properties, Environment environment,
                                              SystemConfigMapper configs, AuditLogMapper audits,
                                              ObjectProvider<FeatureEnablementProperties> featureProperties,
                                              ObjectProvider<LegalEntityReadinessService> readiness) {
        this.properties = properties;
        this.environment = environment;
        this.configs = configs;
        this.audits = audits;
        this.featureProperties = featureProperties;
        this.readiness = readiness;
    }

    @Override
    public Set<Feature> features() {
        return Set.of(Feature.NF05_INBOUND, Feature.NF05_OUTBOUND);
    }

    @Override
    public List<FeatureConditionCheck> evaluate(Feature feature, FeatureEvaluationContext context) {
        boolean outbound = feature == Feature.NF05_OUTBOUND;
        IntegrationHubEnablementDecision.BarrierRead barrier = outbound ? readBarrier()
                : IntegrationHubEnablementDecision.BarrierRead.unreadable();
        Boolean offAudit = outbound && context.purpose().judgesOpenTransition() ? readOffAudit() : null;
        IntegrationHubEnablementDecision.TruststoreRead truststore = outbound ? truststore(context.remoteChecksAllowed())
                : IntegrationHubEnablementDecision.TruststoreRead.notAttempted();
        IntegrationHubEnablementDecision.BootInputs inputs = new IntegrationHubEnablementDecision.BootInputs(
                context.tenantId(), context.businessDate(), context.snapshot().masterAllowed(), null, null,
                context.snapshot().signOffs(), barrier, offAudit, false, properties, environment, approvals(context),
                truststore);
        List<FeatureConditionCheck> checks = new ArrayList<>(
                IntegrationHubEnablementDecision.specificChecks(feature, inputs, context.purpose()));
        checks.add(legalEntity());
        return checks;
    }

    /** 承認の値（DB の源は確認済みの署名、property の源は配備の設定）。 */
    private IntegrationHubApprovals approvals(FeatureEvaluationContext context) {
        FeatureEnablementProperties settings = featureProperties.getIfAvailable();
        if (settings != null && settings.databaseSource()) {
            return IntegrationHubEnablementDecision.approvalsFromSignOffs(context.snapshot().signOffs());
        }
        return IntegrationHubApprovals.fromProperties(properties);
    }

    private IntegrationHubEnablementDecision.BarrierRead readBarrier() {
        try {
            SystemConfig row = configs.selectById(IntegrationHubEnablementDecision.BARRIER_CONFIG_KEY);
            return new IntegrationHubEnablementDecision.BarrierRead(true, row == null ? null : row.getConfigValue());
        } catch (RuntimeException e) {
            return IntegrationHubEnablementDecision.BarrierRead.unreadable();
        }
    }

    private Boolean readOffAudit() {
        try {
            Long count = audits.selectCount(new QueryWrapper<AuditLog>()
                    .eq("reference_type", IntegrationHubEnablementDecision.AUDIT_REFERENCE_TYPE)
                    .eq("reference_id", 0L)
                    .eq("application_code", IntegrationHubEnablementDecision.OFF_AUDIT_CODE));
            return count != null && count > 0;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private IntegrationHubEnablementDecision.TruststoreRead truststore(boolean remoteChecksAllowed) {
        if (!remoteChecksAllowed) {
            return lastTruststore;
        }
        IntegrationHubEnablementDecision.TruststoreRead read = new IntegrationHubEnablementDecision.TruststoreRead(true,
                IntegrationHubEnablementDecision.readTruststore(properties.getExternalTransport().getTruststorePath()));
        lastTruststore = read;
        return read;
    }

    private FeatureConditionCheck legalEntity() {
        LegalEntityReadinessService service = readiness == null ? null : readiness.getIfAvailable();
        if (service == null) {
            return FeatureConditionCheck.unverifiable(CODE_LEGAL_ENTITY_UNVERIFIABLE, FeatureConditionCheck.Category.AUTO);
        }
        try {
            return service.isReady()
                    ? FeatureConditionCheck.satisfied(CODE_LEGAL_ENTITY, FeatureConditionCheck.Category.AUTO, null)
                    : FeatureConditionCheck.unsatisfied(CODE_LEGAL_ENTITY_UNRESOLVED, FeatureConditionCheck.Category.AUTO,
                    false);
        } catch (RuntimeException e) {
            return FeatureConditionCheck.unverifiable(CODE_LEGAL_ENTITY_UNVERIFIABLE, FeatureConditionCheck.Category.AUTO);
        }
    }
}
