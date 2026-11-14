package com.ses.privacy.production.retention.policy;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.SysUser;
import com.ses.entity.privacy.RetentionPolicy;
import com.ses.mapper.privacy.RetentionPolicyMapper;
import com.ses.privacy.foundation.PolicyDecisionStatus;
import com.ses.privacy.foundation.PolicyEvaluationQuery;
import com.ses.privacy.foundation.PolicyExplanationResult;
import com.ses.privacy.foundation.PolicyFailureReason;
import com.ses.privacy.foundation.PolicyReadonlyQueryPort;
import com.ses.privacy.production.PrivacyAccessGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 説明API（§3.1）の service: 公開スイッチ・権限の再判定、tenant 一致、未知は fail-closed。 */
class PrivacyPolicyExplanationServiceTest {

    private PrivacyAccessGuard guard;
    private PolicyReadonlyQueryPort port;
    private ObjectProvider<PolicyReadonlyQueryPort> portProvider;
    private RetentionPolicyMapper mapper;
    private PrivacyPolicyExplanationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        guard = mock(PrivacyAccessGuard.class);
        port = mock(PolicyReadonlyQueryPort.class);
        portProvider = mock(ObjectProvider.class);
        when(portProvider.getIfAvailable()).thenReturn(port);
        mapper = mock(RetentionPolicyMapper.class);
        service = new PrivacyPolicyExplanationService(guard, portProvider, mapper, new OidcSecurityProperties());
        authenticate("default");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 公開スイッチOFFなら何も読まずに404の例外をそのまま返す() {
        doThrow(new PrivacyAccessGuard.PrivacyEntryNotFoundException())
                .when(guard).requireAllowed(PrivacyAccessGuard.Capability.RETENTION, "privacy.policy.view");

        assertThrows(PrivacyAccessGuard.PrivacyEntryNotFoundException.class, () -> service.list(1, 10));
        assertThrows(PrivacyAccessGuard.PrivacyEntryNotFoundException.class, () -> service.detail(1));
        assertThrows(PrivacyAccessGuard.PrivacyEntryNotFoundException.class,
                () -> service.explain("NF08_AI_SUMMARY", "AI_PROMPT_LOG", "EXTERNAL_DISPATCH", "gemini-adapter"));
        verifyNoInteractions(mapper, port);
    }

    @Test
    void 権限が無ければ403の例外をそのまま返し何も読まない() {
        doThrow(BusinessException.of(403, "error.accessDenied"))
                .when(guard).requireAllowed(PrivacyAccessGuard.Capability.RETENTION, "privacy.policy.view");

        BusinessException error = assertThrows(BusinessException.class, () -> service.list(1, 10));
        assertEquals(403, error.getCode());
        verifyNoInteractions(mapper, port);
    }

    @Test
    void 照会者のtenantが権限評価の設定tenantと違えば403にする() {
        authenticate("tenant-other");

        assertEquals(403, assertThrows(BusinessException.class, () -> service.list(1, 10)).getCode());
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.explain("NF08_AI_SUMMARY", "AI_PROMPT_LOG", "EXTERNAL_DISPATCH", "gemini-adapter"))
                .getCode());
        verifyNoInteractions(mapper, port);
    }

    @Test
    void 一覧は登録情報だけを返しenvelopeのbytesを含めない() {
        Page<RetentionPolicy> rows = new Page<>(1, 10);
        RetentionPolicy row = row();
        row.setEnvelopeCanonical(new byte[]{9});
        rows.setRecords(List.of(row));
        when(mapper.selectPage(any(), any())).thenReturn(rows);

        IPage<PrivacyPolicySummary> page = service.list(1, 10);

        assertEquals(1, page.getRecords().size());
        PrivacyPolicySummary summary = page.getRecords().get(0);
        assertEquals("policy-1", summary.policyId());
        assertEquals("CURRENT", summary.registrationStatus());
        verifyNoInteractions(port);
    }

    @Test
    void 一覧の照会失敗は503に閉じる() {
        when(mapper.selectPage(any(), any())).thenThrow(new IllegalStateException("db down"));

        assertEquals(503, assertThrows(BusinessException.class, () -> service.list(1, 10)).getCode());
    }

    @Test
    void 照会条件が不正なら照会portを呼ばず400にする() {
        for (String[] invalid : new String[][]{
                {null, "AI_PROMPT_LOG", "EXTERNAL_DISPATCH", "gemini-adapter"},
                {"NF08_AI_SUMMARY", " ", "EXTERNAL_DISPATCH", "gemini-adapter"},
                {"NF08 AI", "AI_PROMPT_LOG", "EXTERNAL_DISPATCH", "gemini-adapter"},
                {"NF08_AI_SUMMARY", "AI_PROMPT_LOG", "EXTERNAL_DISPATCH", "gemini|adapter"},
                {"M".repeat(51), "AI_PROMPT_LOG", "EXTERNAL_DISPATCH", "gemini-adapter"}}) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.explain(invalid[0], invalid[1], invalid[2], invalid[3]));
            assertEquals(400, error.getCode());
        }
        verifyNoInteractions(port);
    }

    @Test
    void 照会は認証済みのtenantとactorで行い実行認可を返さない() {
        when(port.evaluate(any())).thenReturn(new PolicyExplanationResult(PolicyDecisionStatus.POLICY_UNKNOWN,
                PolicyFailureReason.POLICY_NOT_FOUND, null, null, "AI_PROMPT_LOG", "EXTERNAL_DISPATCH",
                "gemini-adapter", null, Optional.empty(), Optional.empty(), "未登録"));

        PrivacyPolicyExplanation explanation =
                service.explain("NF08_AI_SUMMARY", "AI_PROMPT_LOG", "EXTERNAL_DISPATCH", "gemini-adapter");

        ArgumentCaptor<PolicyEvaluationQuery> query = ArgumentCaptor.forClass(PolicyEvaluationQuery.class);
        verify(port).evaluate(query.capture());
        assertEquals("default", query.getValue().tenantId());
        assertEquals("17", query.getValue().actorId());
        assertEquals("POLICY_UNKNOWN", explanation.decisionStatus());
        assertEquals("POLICY_NOT_FOUND", explanation.failureReason());
        assertFalse(explanation.executionAuthorized());
        assertNull(explanation.effectiveFrom());
    }

    @Test
    void 照会portが無い_例外_nullの場合はPOLICY_UNKNOWNに閉じる() {
        when(portProvider.getIfAvailable()).thenReturn(null);
        assertEquals("POLICY_UNKNOWN", explain().decisionStatus());

        when(portProvider.getIfAvailable()).thenReturn(port);
        when(port.evaluate(any())).thenThrow(new IllegalStateException("source down"));
        PrivacyPolicyExplanation failed = explain();
        assertEquals("POLICY_UNKNOWN", failed.decisionStatus());
        assertFalse(failed.explanation().contains("source down"), "例外の詳細を返さない");

        PolicyReadonlyQueryPort nullPort = mock(PolicyReadonlyQueryPort.class);
        when(portProvider.getIfAvailable()).thenReturn(nullPort);
        assertEquals("POLICY_UNKNOWN", explain().decisionStatus());
    }

    @Test
    void prod以外の照会portは常にPOLICY_UNKNOWNを返す() {
        PolicyExplanationResult result = new UnavailablePolicyReadonlyQueryPort().evaluate(
                new PolicyEvaluationQuery("default", "17", "NF08_AI_SUMMARY", "AI_PROMPT_LOG",
                        "EXTERNAL_DISPATCH", "gemini-adapter"));

        assertEquals(PolicyDecisionStatus.POLICY_UNKNOWN, result.status());
        assertEquals(PolicyFailureReason.STORE_UNAVAILABLE, result.failureReason());
        assertEquals(Optional.empty(), result.effectiveFrom());
    }

    @Test
    void 詳細は該当行が無ければ404で照会portを呼ばず有れば同じkeyで説明照会する() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.detail(5)).getCode());
        verify(port, never()).evaluate(any());

        when(mapper.selectOne(any())).thenReturn(row());
        when(port.evaluate(any())).thenReturn(new PolicyExplanationResult(PolicyDecisionStatus.POLICY_DRIFT,
                PolicyFailureReason.UNKNOWN_TRUST_ANCHOR, null, null, "AI_PROMPT_LOG", "EXTERNAL_DISPATCH",
                "gemini-adapter", null, Optional.empty(), Optional.of(Instant.EPOCH), "anchor未登録"));

        PrivacyPolicyDetail detail = service.detail(5);

        assertEquals("policy-1", detail.policy().policyId());
        assertEquals("POLICY_DRIFT", detail.evaluation().decisionStatus());
        ArgumentCaptor<PolicyEvaluationQuery> query = ArgumentCaptor.forClass(PolicyEvaluationQuery.class);
        verify(port).evaluate(query.capture());
        assertEquals("NF08_AI_SUMMARY", query.getValue().moduleCode());
        assertEquals("gemini-adapter", query.getValue().targetProvider());
    }

    private PrivacyPolicyExplanation explain() {
        return service.explain("NF08_AI_SUMMARY", "AI_PROMPT_LOG", "EXTERNAL_DISPATCH", "gemini-adapter");
    }

    private static RetentionPolicy row() {
        RetentionPolicy row = new RetentionPolicy();
        row.setId(5L);
        row.setPolicyId("policy-1");
        row.setPolicyRevision("r1");
        row.setModuleCode("NF08_AI_SUMMARY");
        row.setDataElementId("AI_PROMPT_LOG");
        row.setTargetAction("EXTERNAL_DISPATCH");
        row.setTargetProvider("gemini-adapter");
        row.setStatus("CURRENT");
        row.setEnvelopeDigest("a".repeat(64));
        return row;
    }

    private static void authenticate(String tenantId) {
        SysUser user = new SysUser();
        user.setId(17L);
        user.setRole("管理者");
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_管理者"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(user, authorities, tenantId), null, authorities));
    }
}
