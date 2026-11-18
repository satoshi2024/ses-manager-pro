package com.ses.privacy.production.retention.policy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.privacy.PiiDataElement;
import com.ses.entity.privacy.RetentionPolicy;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.mapper.privacy.RetentionPolicyMapper;
import com.ses.privacy.foundation.CurrentPolicySnapshot;
import com.ses.privacy.foundation.PolicyLookupKey;
import com.ses.privacy.foundation.ProductionCatalogBinding;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1 表を使う catalog・current source（prod 用部品）を H2 上で確認する。
 *
 * <p>生成列 {@code current_key} は application から書かない（insert/update NEVER）。current の一意性・CHECK・
 * 複合FKの実MySQLでの挙動は {@code FlywayNf07PrivacyF1MigrationTest} が確認する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DbCurrentPolicySourceTest {

    private static final String TENANT = "nf07-db-source-test";
    private static final String MODULE = "NF08_AI_SUMMARY";
    private static final String ELEMENT = "AI_PROMPT_LOG";
    private static final String ACTION = "EXTERNAL_DISPATCH";
    private static final String PROVIDER = "gemini-adapter";

    @Autowired
    private RetentionPolicyMapper policyMapper;
    @Autowired
    private PiiDataElementMapper dataElementMapper;

    @Test
    void 行が無ければcatalogは解決せずsourceはNOT_FOUNDを返す() {
        DbProductionPolicyCatalog catalog = new DbProductionPolicyCatalog(dataElementMapper, policyMapper);
        DbCurrentPolicySource source = new DbCurrentPolicySource(policyMapper);

        assertTrue(catalog.resolve(key()).isEmpty());
        assertEquals(CurrentPolicySnapshot.CurrentStatus.NOT_FOUND, source.readCurrent(key(), binding("p-1")).status());
    }

    @Test
    void data_elementが登録されcurrent候補のpolicyが一意ならbindingを返しCANDIDATEはcurrentとして返さない() {
        registerElement();
        insertPolicy("p-1", "r1", "CANDIDATE", null);
        DbProductionPolicyCatalog catalog = new DbProductionPolicyCatalog(dataElementMapper, policyMapper);
        DbCurrentPolicySource source = new DbCurrentPolicySource(policyMapper);

        Optional<ProductionCatalogBinding> binding = catalog.resolve(key());
        assertTrue(binding.isPresent());
        assertEquals("p-1", binding.get().policyId());
        assertEquals(MODULE, binding.get().scope());
        assertEquals(ProductionCatalogBinding.TENANT_WIDE_ACTOR_BINDING, binding.get().actorBinding());
        assertEquals(CurrentPolicySnapshot.CurrentStatus.NOT_FOUND,
                source.readCurrent(key(), binding.get()).status(), "CANDIDATEはcurrentではない");

        // 法人単位のkeyは P-LE 未決のため解決しない
        assertTrue(catalog.resolve(new PolicyLookupKey(MODULE, TENANT, "17", 5L, ELEMENT, ACTION, PROVIDER)).isEmpty());
    }

    @Test
    void CURRENTの行は保存bytesのまま1件だけ返し生成列はDBが計算する() {
        registerElement();
        byte[] canonical = {1, 2, 3};
        byte[] signature = {4, 5};
        RetentionPolicy current = insertPolicy("p-1", "r2", "CURRENT", 2L);
        DbCurrentPolicySource source = new DbCurrentPolicySource(policyMapper);

        CurrentPolicySnapshot snapshot = source.readCurrent(key(), binding("p-1"));

        assertEquals(CurrentPolicySnapshot.CurrentStatus.CURRENT, snapshot.status());
        assertEquals("p-1", snapshot.policyId());
        assertEquals("r2", snapshot.revision());
        assertEquals("a".repeat(64), snapshot.digest());
        assertArrayEquals(canonical, snapshot.envelopeCanonical());
        assertArrayEquals(signature, snapshot.envelopeSignature());
        RetentionPolicy reloaded = policyMapper.selectById(current.getId());
        assertNotNull(reloaded.getCurrentKey(), "current_keyはDBの生成列が計算する");
        assertTrue(reloaded.getCurrentKey().startsWith(TENANT + "|" + MODULE + "|0|"), reloaded.getCurrentKey());
    }

    @Test
    void 同じkeyの2件目のCURRENTは生成列の一意制約で拒否されREVOKEDはcatalogの候補から外れる() {
        registerElement();
        insertPolicy("p-1", "r1", "CURRENT", 2L);
        assertThrows(RuntimeException.class, () -> insertPolicy("p-1", "r2", "CURRENT", 2L));

        RetentionPolicy existing = policyMapper.selectOne(new LambdaQueryWrapper<RetentionPolicy>()
                .eq(RetentionPolicy::getTenantId, TENANT).eq(RetentionPolicy::getPolicyRevision, "r1"));
        assertNotNull(existing);
        insertPolicy("p-2", "r1", "REVOKED", null);
        DbProductionPolicyCatalog catalog = new DbProductionPolicyCatalog(dataElementMapper, policyMapper);
        assertEquals("p-1", catalog.resolve(key()).orElseThrow().policyId(), "REVOKEDのp-2は候補に数えない");

        insertPolicy("p-3", "r1", "CANDIDATE", null);
        assertTrue(catalog.resolve(key()).isEmpty(), "policy_idが2種類あれば曖昧として解決しない");
    }

    @Test
    void 生成列は書き込み対象にならず非CURRENTではNULLになる() {
        RetentionPolicy candidate = insertPolicy("p-1", "r1", "CANDIDATE", null);
        RetentionPolicy reloaded = policyMapper.selectById(candidate.getId());
        assertNull(reloaded.getCurrentKey());
    }

    private PolicyLookupKey key() {
        return new PolicyLookupKey(MODULE, TENANT, "17", PolicyLookupKey.TENANT_WIDE, ELEMENT, ACTION, PROVIDER);
    }

    private static ProductionCatalogBinding binding(String policyId) {
        return new ProductionCatalogBinding(policyId, MODULE, ProductionCatalogBinding.TENANT_WIDE_ACTOR_BINDING,
                ELEMENT, ACTION, PROVIDER, PolicyLookupKey.TENANT_WIDE);
    }

    private void registerElement() {
        PiiDataElement element = new PiiDataElement();
        element.setTenantId(TENANT);
        element.setDataElementId(ELEMENT);
        element.setStorageKind("AI_PAYLOAD");
        element.setStorageRef("t_ai_recommendation_run.prompt");
        assertEquals(1, dataElementMapper.insert(element));
        PiiDataElement reloaded = dataElementMapper.selectById(element.getId());
        assertEquals("UNKNOWN", reloaded.getSubjectAttribution(), "分類の既定はUNKNOWN（fail-closed）");
    }

    private RetentionPolicy insertPolicy(String policyId, String revision, String status, Long activatedBy) {
        RetentionPolicy policy = new RetentionPolicy();
        policy.setTenantId(TENANT);
        policy.setPolicyId(policyId);
        policy.setPolicyRevision(revision);
        policy.setModuleCode(MODULE);
        policy.setDataElementId(ELEMENT);
        policy.setTargetAction(ACTION);
        policy.setTargetProvider(PROVIDER);
        policy.setStatus(status);
        policy.setCurrentKey("forged-current-key");
        policy.setEnvelopeSchemaVersion(1);
        policy.setEnvelopeCanonical(new byte[]{1, 2, 3});
        policy.setEnvelopeSignature(new byte[]{4, 5});
        policy.setEnvelopeDigest("a".repeat(64));
        policy.setAuthorityId("authority-test");
        policy.setKeyId("key-test");
        policy.setRegisteredBy(1L);
        policy.setRegisteredAt(LocalDateTime.of(2026, 11, 14, 9, 0));
        if (activatedBy != null) {
            policy.setActivatedBy(activatedBy);
            policy.setActivatedAt(LocalDateTime.of(2026, 11, 14, 10, 0));
        }
        assertEquals(1, policyMapper.insert(policy));
        return policy;
    }
}
