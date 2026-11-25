package com.ses.importcenter.metadata;

import com.ses.config.feature.Feature;
import com.ses.entity.PermissionGroup;
import com.ses.entity.PermissionGroupAction;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureConditionRegistry;
import com.ses.featureenablement.condition.FeatureEvaluation;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.state.FeatureEnablementOverlay;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.mapper.PermissionGroupActionMapper;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.service.security.LegalEntityContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FE-B02（feature-enablement-console design §8.3、requirements §6.10）: NF06 の開放の条件。
 *
 * <ul>
 *   <li>(a) 確認済みの署名 {@code NF06_ADMISSION} の構造化された値（{@code ImportAdmissionPortTest} の golden vector
 *       {@code c5dc5f03…} の値）で成立し、改竄（digest の不一致）・期間の外（署名日前・失効は時間要因）・承認 tenant の違いで不成立。</li>
 *   <li>(b) 行参照の鍵の状態が {@code AVAILABLE} の時だけ成立。</li>
 *   <li>(c) {@code import.metadata.manage} を管理者専用の group だけが明示に許可する時に成立し、{@code import.*}・{@code '*'} の許可を
 *       持つ別の group（拒否行なし）で不成立。拒否行の無い新しい group でも許可が無ければ成立する（review P3-19）。</li>
 *   <li>(d) 法人がちょうど1つの時だけ成立（0件と2件で不成立）。照会の失敗は評価できない類。</li>
 *   <li>DB の源は署名の差替えが次の読取りから効き、目録の印が無ければ property の源へ委ねる。</li>
 * </ul>
 */
class ImportCenterEnablementConditionsTest {

    private static final Instant NOW = Instant.parse("2026-12-10T01:00:00Z");
    private static final LocalDate SIGNED = LocalDate.of(2026, 12, 1);
    private static final LocalDate EXPIRES = LocalDate.of(2027, 11, 30);
    private static final String GOLDEN_DIGEST = "c5dc5f03efdb4fd22c23cdf86c7d248f22af96d187880a87f07664a97b173550";
    private static final String GOLDEN_JSON = "{\"approvalDigest\":\"" + GOLDEN_DIGEST + "\",\"decisionId\":\"DO-NF06-2026-001\","
            + "\"sourceCodes\":\"CRM_2,crm-2,Crm,crm2,crm\",\"tenantId\":\"default\"}";
    /** prod で使える（公開済みでない）鍵の代わり。 */
    private static final String PRODUCTION_LIKE_KEY = java.util.Base64.getEncoder().encodeToString(
            "prod-like-nf06-row-reference-key-for-unit-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    private static final AtomicLong IDS = new AtomicLong(100);

    private final FeatureEnablementCatalog catalog = FeatureEnablementCatalog.standard();
    private PermissionGroupMapper groupMapper;
    private PermissionGroupActionMapper actionMapper;
    private LegalEntityContextService legalEntities;
    private final List<PermissionGroup> groups = new ArrayList<>();
    private final List<PermissionGroupAction> actions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        groupMapper = mock(PermissionGroupMapper.class);
        actionMapper = mock(PermissionGroupActionMapper.class);
        legalEntities = mock(LegalEntityContextService.class);
        when(groupMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(groups));
        when(actionMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(actions));
        when(legalEntities.administratorLegalEntityCount()).thenReturn(1);
        long admin = group("role-admin");
        allow(admin, "*");
        allow(admin, ImportMetadataAuthorizationService.ACTION);
        allow(group("privacy-admin"), ImportMetadataAuthorizationService.ACTION);
        deny(group("role-sales"), ImportMetadataAuthorizationService.ACTION);
    }

    @Test
    void golden_vectorの値の署名と使える鍵と管理者だけの許可と単一法人で全て成立する() {
        FeatureEvaluation evaluation = registry(PRODUCTION_LIKE_KEY).evaluate(Feature.NF06,
                context(snapshot(GOLDEN_JSON, SIGNED, EXPIRES), "default", LocalDate.of(2026, 12, 10)));
        List<FeatureConditionCheck> specific = evaluation.checks().stream()
                .filter(check -> check.code().startsWith("importCenter.")).toList();
        assertThat(specific).extracting(FeatureConditionCheck::code).containsExactly("importCenter.admission.complete",
                "importCenter.rowReferenceKey.available", "importCenter.permission.adminOnly",
                "importCenter.legalEntity.single");
        assertThat(specific).allMatch(FeatureConditionCheck::passing);
        assertThat(specific.get(0).validUntil()).isEqualTo(EXPIRES);
        assertThat(evaluation.summary()).doesNotContain(GOLDEN_DIGEST).doesNotContain("DO-NF06-2026-001")
                .doesNotContain("crm-2");
    }

    @Test
    void 改竄と期間の外と承認tenantの違いでadmissionは不成立になり期間の外は時間要因() {
        ImportCenterEnablementConditions conditions = conditions(PRODUCTION_LIKE_KEY);
        FeatureConditionCheck tampered = conditions.admission(context(snapshot(GOLDEN_JSON.replace("crm2", "crm3"), SIGNED,
                EXPIRES), "default", LocalDate.of(2026, 12, 10)));
        assertThat(tampered.code()).isEqualTo("importCenter.admission." + ImportAdmissionApproval.VIOLATION_DIGEST_MISMATCH);
        assertThat(tampered.temporal()).isFalse();
        assertThat(tampered.passing()).isFalse();

        FeatureConditionCheck before = conditions.admission(context(snapshot(GOLDEN_JSON, SIGNED, EXPIRES), "default",
                LocalDate.of(2026, 11, 30)));
        assertThat(before.code()).isEqualTo("importCenter.admission.signedOn.future");
        assertThat(before.temporal()).isTrue();
        FeatureConditionCheck expired = conditions.admission(context(snapshot(GOLDEN_JSON, SIGNED, EXPIRES), "default",
                EXPIRES));
        assertThat(expired.code()).isEqualTo("importCenter.admission.expiresOn.expired");
        assertThat(expired.temporal()).isTrue();

        assertThat(conditions.admission(context(snapshot(GOLDEN_JSON, SIGNED, EXPIRES), "other-tenant",
                LocalDate.of(2026, 12, 10))).code()).isEqualTo("importCenter.admission.tenant.notApproved");
        assertThat(conditions.admission(context(snapshot(null, SIGNED, EXPIRES), "default", LocalDate.of(2026, 12, 10)))
                .code()).as("構造化された値の無い署名").isEqualTo("importCenter.admission.decisionId.missing");
        FeatureEnablementSnapshot none = new FeatureEnablementSnapshot(1L, NOW, NOW, true, Map.of(), Map.of(), Map.of(),
                Map.of());
        assertThat(conditions.admission(context(none, "default", LocalDate.of(2026, 12, 10))).passing())
                .as("署名が無ければ不成立（fail-closed）").isFalse();
        assertThat(conditions.admission(context(snapshot("{broken", SIGNED, EXPIRES), "default",
                LocalDate.of(2026, 12, 10))).passing()).as("読めない値も不成立").isFalse();
    }

    @Test
    void 行参照の鍵はAVAILABLEの時だけ成立する() {
        assertThat(conditions(PRODUCTION_LIKE_KEY).rowReferenceKey().passing()).isTrue();
        for (String key : new String[]{null, "broken-key", ImportSourceRowReference.DEVELOPMENT_ONLY_KEY}) {
            FeatureConditionCheck check = conditions(key).rowReferenceKey();
            assertThat(check.passing()).as(String.valueOf(key)).isFalse();
            assertThat(check.code()).isEqualTo("importCenter.rowReferenceKey.notUsable");
            assertThat(check.detailCodes().get(0)).startsWith("state=");
        }
    }

    @Test
    void 権限は管理者専用のgroupだけが許可し別のgroupに実効の許可があれば不成立() {
        ImportCenterEnablementConditions conditions = conditions(PRODUCTION_LIKE_KEY);
        // 拒否行の無い新しい group でも、許可が無ければ成立する（拒否行の有無で判定しない。review P3-19）
        group("role-new-without-deny-row");
        assertThat(conditions.permission("default").code()).isEqualTo("importCenter.permission.adminOnly");

        // import.* を許可する別の group と、管理者の '*' を複製した group（拒否行なし）は実効の許可を持つ
        allow(group("custom-importer"), "import.*");
        allow(group("copied-admin"), "*");
        long denied = group("copied-admin-with-deny");
        allow(denied, "*");
        deny(denied, "import.*");
        FeatureConditionCheck others = conditions.permission("default");
        assertThat(others.passing()).isFalse();
        assertThat(others.code()).isEqualTo("importCenter.permission.otherGroupAllowed[count=2]");
    }

    @Test
    void 管理者専用のgroupが無いか明示の許可が無ければ不成立で照会の失敗は評価できない類() {
        groups.removeIf(group -> "role-admin".equals(group.getGroupKey()));
        assertThat(conditions(PRODUCTION_LIKE_KEY).permission("default").code())
                .isEqualTo("importCenter.permission.adminGroup.missing[group=role-admin]");

        groups.clear();
        actions.clear();
        allow(group("role-admin"), "*");
        assertThat(conditions(PRODUCTION_LIKE_KEY).permission("default").code())
                .as("service 層は '*' を許可根拠に数えないため明示の許可行が要る")
                .isEqualTo("importCenter.permission.adminGroup.notAllowed[group=role-admin]");

        when(groupMapper.selectList(any())).thenThrow(new IllegalStateException("db"));
        FeatureConditionCheck failed = conditions(PRODUCTION_LIKE_KEY).permission("default");
        assertThat(failed.status()).isEqualTo(FeatureConditionCheck.Status.UNVERIFIABLE);
        assertThat(FeatureEnablementCatalog.isUnverifiableCode(failed.code())).isTrue();
    }

    @Test
    void 法人はちょうど1つの時だけ成立し照会の失敗は評価できない類() {
        ImportCenterEnablementConditions conditions = conditions(PRODUCTION_LIKE_KEY);
        when(legalEntities.administratorLegalEntityCount()).thenReturn(0, 2, 1);
        assertThat(conditions.legalEntity().detailCodes()).containsExactly("count=0");
        assertThat(conditions.legalEntity().code()).isEqualTo("importCenter.legalEntity.notSingle");
        assertThat(conditions.legalEntity().passing()).isTrue();
        when(legalEntities.administratorLegalEntityCount()).thenThrow(new IllegalStateException("db"));
        FeatureConditionCheck failed = conditions.legalEntity();
        assertThat(failed.status()).isEqualTo(FeatureConditionCheck.Status.UNVERIFIABLE);
        assertThat(FeatureEnablementCatalog.isUnverifiableCode(failed.code())).isTrue();
    }

    @Test
    void DBの源は署名の差替えが次の読取りから効きpropertyの源は設定値をそのまま返す() {
        ImportAdmissionApprovalProperties configured = new ImportAdmissionApprovalProperties();
        configured.setDecisionId("DO-PROPERTY-0001");
        ImportAdmissionApprovalSource.Property property = new ImportAdmissionApprovalSource.Property(configured);
        FeatureEnablementSnapshotHolder holder = holder(snapshot(GOLDEN_JSON, SIGNED, EXPIRES));
        ImportAdmissionApprovalSource database = new ImportAdmissionApprovalSource.Database(holder);

        ImportAdmissionApprovalProperties first = database.current();
        assertThat(first.getDecisionId()).isEqualTo("DO-NF06-2026-001");
        assertThat(first.getSignedOn()).isEqualTo("2026-12-01");
        assertThat(first.getExpiresOn()).isEqualTo("2027-11-30");
        assertThat(ImportAdmissionApproval.computeDigest(first)).isEqualTo(GOLDEN_DIGEST);

        holder.replace(snapshot(GOLDEN_JSON.replace("DO-NF06-2026-001", "DO-NF06-2026-002"), SIGNED, EXPIRES));
        assertThat(database.current().getDecisionId()).as("再起動なしに次の読取りから効く").isEqualTo("DO-NF06-2026-002");

        assertThat(property.current().getDecisionId()).isEqualTo("DO-PROPERTY-0001");
        assertThat(property.fromSnapshot(snapshot(GOLDEN_JSON, SIGNED, EXPIRES)).getDecisionId())
                .isEqualTo("DO-PROPERTY-0001");
    }

    // ---------------------------------------------------------------- helper

    private ImportCenterEnablementConditions conditions(String key) {
        return new ImportCenterEnablementConditions(new ImportAdmissionApprovalSource.Database(
                holder(snapshot(GOLDEN_JSON, SIGNED, EXPIRES))),
                new ImportSourceRowReference(key, true), groupMapper, actionMapper, legalEntities);
    }

    private FeatureConditionRegistry registry(String key) {
        return new FeatureConditionRegistry(catalog, List.of(conditions(key)));
    }

    private static FeatureEvaluationContext context(FeatureEnablementSnapshot snapshot, String tenantId, LocalDate today) {
        return new FeatureEvaluationContext(tenantId, today, Clock.fixed(NOW, ZoneOffset.UTC), snapshot,
                FeatureEvaluationContext.Purpose.APPROVAL, Set.of(Feature.NF06), true);
    }

    private static FeatureEnablementSnapshot snapshot(String json, LocalDate signedOn, LocalDate expiresOn) {
        FeatureEnablementSnapshot.ConfirmedSignOff admission = new FeatureEnablementSnapshot.ConfirmedSignOff(7L, 0,
                ImportAdmissionApprovalSource.KIND, "DO-NF06-2026-001", "DATA_OWNER", signedOn, expiresOn, json, "DECISION");
        return new FeatureEnablementSnapshot(1L, NOW, NOW, true, Map.of(), Map.of(),
                Map.of(ImportAdmissionApprovalSource.KIND, admission), Map.of());
    }

    private static FeatureEnablementSnapshotHolder holder(FeatureEnablementSnapshot snapshot) {
        FeatureEnablementProperties properties = new FeatureEnablementProperties();
        properties.setSource(FeatureEnablementProperties.Source.DATABASE);
        FeatureEnablementSnapshotHolder holder = new FeatureEnablementSnapshotHolder(null,
                FeatureEnablementTenant.fixed("default"), properties, new FeatureEnablementOverlay(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        holder.replace(snapshot);
        return holder;
    }

    private long group(String key) {
        PermissionGroup group = new PermissionGroup();
        group.setId(IDS.incrementAndGet());
        group.setTenantId("default");
        group.setGroupKey(key);
        group.setEnabled(1);
        groups.add(group);
        return group.getId();
    }

    private void allow(long groupId, String actionKey) {
        actions.add(row(groupId, actionKey, 0));
    }

    private void deny(long groupId, String actionKey) {
        actions.add(row(groupId, actionKey, 1));
    }

    private static PermissionGroupAction row(long groupId, String actionKey, int denyFlag) {
        PermissionGroupAction action = new PermissionGroupAction();
        action.setTenantId("default");
        action.setGroupId(groupId);
        action.setActionKey(actionKey);
        action.setDenyFlag(denyFlag);
        return action;
    }
}
