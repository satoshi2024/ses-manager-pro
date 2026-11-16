package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.exception.SafeErrorPolicy;
import com.ses.config.LoginUser;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.config.feature.FeatureSwitches;
import com.ses.dto.lifecycle.dual.DualConfirmationResultDto;
import com.ses.dto.lifecycle.dual.DualConfirmationSubmitRequest;
import com.ses.dto.lifecycle.dual.DualConfirmationViewDto;
import com.ses.entity.Engineer;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleDualConfirmation;
import com.ses.entity.LifecycleDualProjection;
import com.ses.entity.LifecycleDualSubmission;
import com.ses.entity.LifecycleTask;
import com.ses.entity.SysUser;
import com.ses.mapper.AuthorizationEpochMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.LifecycleDualConfirmationMapper;
import com.ses.mapper.LifecycleDualProjectionMapper;
import com.ses.mapper.LifecycleDualSubmissionDocumentMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.document.DocumentEvidenceEligibility;
import com.ses.service.document.DocumentEvidenceStorageVerifier;
import com.ses.service.lifecycle.dual.DualConfirmationChannel;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationAttemptService;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationCoordinator;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationDenyRecorder;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationDependencyReader;
import com.ses.service.security.ActionPermissionResolver;
import com.ses.service.security.ExplicitActorAuthorizationEvaluator;
import com.ses.service.security.ExplicitActorAuthorizationEvaluator.ExplicitActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U12（NF01細粒度epoch計画 §6.2、W3-21a）: 二者確認APIのroute・menu・子スイッチ・未知property・Idempotency-Key・CSRF。
 *
 * <p>coordinatorは実物（{@link SpyBean}）で、協力部品だけをmockにする。子スイッチOFF・headerとbodyの形式違反はDBへ触れる前に
 * 拒否されるため、実物の判定順（スイッチ → Idempotency-Key → body → 主体）をMVC経由で確かめられる。route対応の試験だけは
 * coordinatorの結果を差し替える。</p>
 *
 * <p>W3-21b: 照会（A1・A5）の応答でのcomment本文の表示範囲（review P3-3の最小公開）を、実物のcoordinatorの照会経路で確かめる。</p>
 */
@WebMvcTest(LifecycleDualConfirmationApiController.class)
@Import({LifecycleDualConfirmationCoordinator.class, LifecycleDualConfirmationApiControllerTest.Switches.class})
class LifecycleDualConfirmationApiControllerTest {

    private static final String TENANT = "tenant-a";
    private static final String KEY = "ui-op-0001";
    private static final String SUBMIT_BODY =
            "{\"taskGeneration\":0,\"documentSelections\":[{\"documentId\":10,\"documentVersionId\":20}],"
                    + "\"comment\":\"確認をお願いします\"}";
    private static final String DECISION_BODY =
            "{\"submissionRevision\":1,\"evidenceSha256\":\"" + "a".repeat(64) + "\",\"decision\":\"CONFIRM\"}";

    /** 子スイッチを試験ごとに切り替えるため、実物の {@link FeatureSwitches} を可変の設定値で作る。 */
    @TestConfiguration
    static class Switches {
        @Bean
        FeatureSwitchProperties featureSwitchProperties() {
            return new FeatureSwitchProperties();
        }

        @Bean
        FeatureSwitches featureSwitches(FeatureSwitchProperties properties) {
            return new FeatureSwitches(properties);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private MessageSource messageSource;
    @Autowired private FeatureSwitchProperties properties;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @SpyBean private LifecycleDualConfirmationCoordinator coordinator;

    // coordinatorの協力部品（DBに触れる前に拒否される試験では呼ばれない）
    @MockBean private SysUserMapper sysUserMapper;
    @MockBean private ExplicitActorAuthorizationEvaluator evaluator;
    @MockBean private LifecycleDualConfirmationDependencyReader dependencyReader;
    @MockBean private DocumentEvidenceEligibility eligibility;
    @MockBean private DocumentEvidenceStorageVerifier storageVerifier;
    @MockBean private LifecycleDualConfirmationAttemptService attemptService;
    @MockBean private LifecycleDualConfirmationDenyRecorder denyRecorder;
    @MockBean private AuthorizationEpochMapper epochMapper;
    @MockBean private LifecycleDualProjectionMapper projectionMapper;
    @MockBean private LifecycleDualSubmissionDocumentMapper submissionDocumentMapper;
    @MockBean private LifecycleDualConfirmationMapper confirmationMapper;
    @MockBean private DocumentLinkMapper documentLinkMapper;
    @MockBean private DocumentVersionMapper documentVersionMapper;
    @MockBean private DocumentMapper documentMapper;

    // web sliceのfilter・adviceが参照する部品（既存のWebMvcTestと同じ）
    @MockBean private com.ses.service.security.DataScopeService dataScopeService;
    @MockBean private com.ses.service.security.OrganizationScopeService organizationScopeService;
    @MockBean private com.ses.service.security.AuthorizationService authorizationService;
    @MockBean private com.ses.service.security.MfaService mfaService;
    @MockBean private com.ses.service.security.PersistentSessionService persistentSessionService;
    @MockBean private com.ses.service.approval.ApprovalTargetAdapterRegistry approvalTargetAdapterRegistry;

    @BeforeEach
    void setUp() {
        properties.getNf01().setEnabled(true);
        properties.getNf01DualConfirmation().setEnabled(true);
        AccountingTenantContextHolder.setTenantId(TENANT);
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("dual-sales");
        user.setPassword("password");
        user.setRole("営業");
        user.setStatus(1);
        user.setTenantId(TENANT);
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_営業")), TENANT);
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                principal.getAuthorities()));
        TestSecurityContextHolder.setContext(securityContext);
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        when(authorizationService.isAllowed(any(), any())).thenReturn(true);
        when(persistentSessionService.validateAndTouch(any(), any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
    }

    // ===================================================================== route・menu

    @Test
    void routeは既存のNF01のAPI_rootに属しaction_keyは既存のlifecycleとmyのまま() {
        assertThat(ActionPermissionResolver.resolve("GET", "/api/lifecycle/tasks/7/dual-confirmation"))
                .isEqualTo("lifecycle.view");
        assertThat(ActionPermissionResolver.resolve("GET", "/api/lifecycle/tasks/7/dual-confirmation/candidates"))
                .isEqualTo("lifecycle.view");
        assertThat(ActionPermissionResolver.resolve("POST", "/api/lifecycle/tasks/7/dual-confirmation/submissions"))
                .isEqualTo("lifecycle.create");
        assertThat(ActionPermissionResolver.resolve("POST",
                "/api/lifecycle/tasks/7/dual-confirmation/submissions/9/decision")).isEqualTo("lifecycle.create");
        assertThat(ActionPermissionResolver.resolve("GET", "/api/my/lifecycle/tasks/7/dual-confirmation"))
                .isEqualTo("my.view");
        assertThat(ActionPermissionResolver.resolve("POST", "/api/my/lifecycle/tasks/7/dual-confirmation/submissions"))
                .isEqualTo("my.create");
        for (String path : routes()) {
            assertThat(Feature.forPath(path.replaceAll("\\{[^}]*}", "1"))).as(path).contains(Feature.NF01);
        }
    }

    @Test
    void 本人の入口には確認routeが無く社内と本人で7routeだけを持つ() throws Exception {
        assertThat(routes()).containsExactlyInAnyOrder(
                "/api/lifecycle/tasks/{id}/dual-confirmation",
                "/api/lifecycle/tasks/{id}/dual-confirmation/candidates",
                "/api/lifecycle/tasks/{id}/dual-confirmation/submissions",
                "/api/lifecycle/tasks/{id}/dual-confirmation/submissions/{submissionId}/decision",
                "/api/my/lifecycle/tasks/{id}/dual-confirmation",
                "/api/my/lifecycle/tasks/{id}/dual-confirmation/candidates",
                "/api/my/lifecycle/tasks/{id}/dual-confirmation/submissions");
        assertThat(routes()).filteredOn(path -> path.startsWith("/api/my/")).noneMatch(path -> path.contains("decision"));

        mockMvc.perform(post("/api/my/lifecycle/tasks/7/dual-confirmation/submissions/9/decision").with(csrf())
                        .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(DECISION_BODY))
                .andExpect(status().is4xxClientError());
        verify(coordinator, never()).decide(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void 照会はprincipalのユーザーIDとsessionのtenantを入口の種類と一緒にcoordinatorへ渡す() throws Exception {
        doReturn(DualConfirmationViewDto.builder().taskId(7L).taskGeneration(3L).build())
                .when(coordinator).view(anyLong(), any(), any());

        mockMvc.perform(get("/api/lifecycle/tasks/7/dual-confirmation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskGeneration").value(3));
        mockMvc.perform(get("/api/my/lifecycle/tasks/7/dual-confirmation")).andExpect(status().isOk());

        ArgumentCaptor<LifecycleDualConfirmationCoordinator.Principal> principal =
                ArgumentCaptor.forClass(LifecycleDualConfirmationCoordinator.Principal.class);
        verify(coordinator).view(eq(7L), principal.capture(), eq(DualConfirmationChannel.INTERNAL));
        verify(coordinator).view(eq(7L), any(), eq(DualConfirmationChannel.SELF));
        assertThat(principal.getValue().userId()).isEqualTo(1L);
        assertThat(principal.getValue().sessionTenantId()).isEqualTo(TENANT);
    }

    @Test
    void 提出はheaderのIdempotency_Keyとbodyをcoordinatorへ渡す() throws Exception {
        doReturn(DualConfirmationResultDto.builder().taskId(7L).submissionId(55L).submissionRevision(1)
                .state("PENDING").build())
                .when(coordinator).submit(anyLong(), any(), any(), any(), anyString());

        mockMvc.perform(post("/api/my/lifecycle/tasks/7/dual-confirmation/submissions").with(csrf())
                        .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(SUBMIT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submissionId").value(55))
                .andExpect(jsonPath("$.data.state").value("PENDING"));

        ArgumentCaptor<DualConfirmationSubmitRequest> body = ArgumentCaptor.forClass(DualConfirmationSubmitRequest.class);
        verify(coordinator).submit(eq(7L), any(), eq(DualConfirmationChannel.SELF), body.capture(), eq(KEY));
        assertThat(body.getValue().getTaskGeneration()).isZero();
        assertThat(body.getValue().getDocumentSelections()).hasSize(1);
        assertThat(body.getValue().getUnknownFields()).isEmpty();
    }

    // ===================================================================== 子スイッチ

    @Test
    void 子スイッチOFFではA1からA7がすべて400で試行も拒否eventも行わない() throws Exception {
        properties.getNf01DualConfirmation().setEnabled(false);

        for (MockHttpServletRequestBuilder request : allEntries()) {
            expectError(mockMvc.perform(request.locale(Locale.JAPAN)), 400, "error.lifecycle.dualConfirmationDisabled");
        }
        verifyNoInteractions(attemptService, denyRecorder, dependencyReader);
    }

    @Test
    void 親のNF01がOFFならfilterが404にして子スイッチの判定まで届かない() throws Exception {
        properties.getNf01().setEnabled(false);

        mockMvc.perform(get("/api/lifecycle/tasks/7/dual-confirmation"))
                .andExpect(status().isNotFound());
        verify(coordinator, never()).view(anyLong(), any(), any());
    }

    // ===================================================================== bodyとheaderの検査

    @Test
    void 未知のbody_propertyは名前だけを集めて400にし拒否eventを記録しない() throws Exception {
        String unknownTop = "{\"taskGeneration\":0,\"documentSelections\":[{\"documentId\":10,"
                + "\"documentVersionId\":20}],\"tenantId\":\"tenant-b\"}";
        String unknownNested = "{\"taskGeneration\":0,\"documentSelections\":[{\"documentId\":10,"
                + "\"documentVersionId\":20,\"sha256\":\"" + "0".repeat(64) + "\"}]}";
        String unknownDecision = "{\"submissionRevision\":1,\"evidenceSha256\":\"" + "a".repeat(64)
                + "\",\"decision\":\"CONFIRM\",\"actorUserId\":2}";
        expectError(mockMvc.perform(submit("/api/lifecycle/tasks/7/dual-confirmation/submissions", KEY, unknownTop)),
                400, "error.validationError");
        expectError(mockMvc.perform(submit("/api/my/lifecycle/tasks/7/dual-confirmation/submissions", KEY,
                unknownNested)), 400, "error.validationError");
        expectError(mockMvc.perform(submit("/api/lifecycle/tasks/7/dual-confirmation/submissions/9/decision", KEY,
                unknownDecision)), 400, "error.validationError");
        verifyNoInteractions(attemptService, denyRecorder, dependencyReader);
    }

    @Test
    void Idempotency_Keyの欠落と末尾空白と許可外文字は400で正しいkeyなら次の検査へ進む() throws Exception {
        String path = "/api/lifecycle/tasks/7/dual-confirmation/submissions";
        expectError(mockMvc.perform(post(path).with(csrf()).locale(Locale.JAPAN)
                .contentType(MediaType.APPLICATION_JSON).content(SUBMIT_BODY)), 400,
                "error.lifecycle.idempotencyKeyInvalid");
        for (String key : List.of("ui-op-0001 ", "ui-op-0001\t", "ui op 0001", "ui-op:0001", "a".repeat(191))) {
            expectError(mockMvc.perform(submit(path, key, SUBMIT_BODY)), 400, "error.lifecycle.idempotencyKeyInvalid");
        }
        expectError(mockMvc.perform(submit("/api/lifecycle/tasks/7/dual-confirmation/submissions/9/decision",
                "ui-op-0001 ", DECISION_BODY)), 400, "error.lifecycle.idempotencyKeyInvalid");
        // 正しいkeyなら、次の検査（bodyの未知property）で止まる
        expectError(mockMvc.perform(submit(path, "a".repeat(190), "{\"taskGeneration\":0,\"actorUserId\":1}")),
                400, "error.validationError");
        verifyNoInteractions(attemptService, denyRecorder);
    }

    // ===================================================================== CSRF

    @Test
    void CSRF_tokenの無い提出と確認は403でcoordinatorへ届かない() throws Exception {
        mockMvc.perform(post("/api/lifecycle/tasks/7/dual-confirmation/submissions").header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(SUBMIT_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/my/lifecycle/tasks/7/dual-confirmation/submissions").header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(SUBMIT_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/lifecycle/tasks/7/dual-confirmation/submissions/9/decision")
                        .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(DECISION_BODY))
                .andExpect(status().isForbidden());
        verify(coordinator, never()).submit(anyLong(), any(), any(), any(), any());
        verify(coordinator, never()).decide(anyLong(), anyLong(), any(), any(), any());
    }

    // ===================================================================== commentの表示範囲（W3-21b、review P3-3）

    /**
     * 照会（A1・A5）の応答でcomment本文を返すのは確認者のrole（管理者・HR）とその提出の提出者本人だけ。
     * 範囲内の他の営業・マネージャーと、営業が代理提出したときの要員本人には本文を返さず有無だけを返す。
     * 本文は応答のどこにも含めない（画面で隠すだけにしない）。
     */
    @ParameterizedTest(name = "{0} 照会者={1}(id={2}) 提出者id={3} → 本文={4}")
    @CsvSource({
            "/api/lifecycle,    管理者,       1, 2, true",
            "/api/lifecycle,    HR,          1, 2, true",
            "/api/lifecycle,    営業,         1, 1, true",
            "/api/lifecycle,    営業,         1, 2, false",
            "/api/lifecycle,    マネージャー, 1, 2, false",
            "/api/my/lifecycle, 要員,         1, 1, true",
            "/api/my/lifecycle, 要員,         1, 2, false"
    })
    void commentの本文は確認者のroleとその提出の提出者だけに返し他の照会者には有無だけを返す(
            String entry, String viewerRole, long viewerUserId, long submitterUserId, boolean bodyVisible)
            throws Exception {
        stubView(viewerRole, viewerUserId, submitterUserId, "提出の説明-W321B", "差戻しの理由-W321B");

        MvcResult result = mockMvc.perform(get(entry + "/tasks/7/dual-confirmation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.state").value("REJECTED"))
                .andExpect(jsonPath("$.data.decision").value("REJECT"))
                .andExpect(jsonPath("$.data.commentsVisible").value(bodyVisible))
                .andExpect(jsonPath("$.data.submissionCommentPresent").value(true))
                .andExpect(jsonPath("$.data.decisionCommentPresent").value(true))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        if (bodyVisible) {
            assertThat(body).contains("提出の説明-W321B", "差戻しの理由-W321B");
        } else {
            assertThat(body).doesNotContain("提出の説明-W321B", "差戻しの理由-W321B");
        }
    }

    @Test
    void 空白だけのcommentは記入なしとして有無もfalseで返す() throws Exception {
        stubView("営業", 1L, 2L, "   ", null);

        mockMvc.perform(get("/api/lifecycle/tasks/7/dual-confirmation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commentsVisible").value(false))
                .andExpect(jsonPath("$.data.submissionCommentPresent").value(false))
                .andExpect(jsonPath("$.data.decisionCommentPresent").value(false));
    }

    /** 差し戻された提出（REJECTED）を照会できる状態を、実物のcoordinatorの照会経路に沿って用意する。 */
    private void stubView(String viewerRole, long viewerUserId, long submitterUserId, String submissionComment,
                          String decisionComment) {
        SysUser viewer = new SysUser();
        viewer.setId(viewerUserId);
        viewer.setRole(viewerRole);
        viewer.setStatus(1);
        viewer.setTenantId(TENANT);
        when(sysUserMapper.selectById(viewerUserId)).thenReturn(viewer);
        when(evaluator.resolveActor(eq(TENANT), eq(viewerUserId), any(LocalDate.class)))
                .thenReturn(Optional.of(new ExplicitActor(TENANT, viewerUserId, viewerRole, LocalDate.now())));

        LifecycleTask task = LifecycleTask.builder().caseId(30L).taskCode("DUAL").taskName("二者確認")
                .evidenceType("DUAL_CONFIRMATION").status("IN_PROGRESS").isEngineerVisible(1).build();
        task.setId(7L);
        LifecycleCase lcCase = LifecycleCase.builder().tenantId(TENANT).caseNo("LC-W321B").engineerId(40L)
                .status("ACTIVE").build();
        lcCase.setId(30L);
        Engineer engineer = Engineer.builder().tenantId(TENANT).build();
        engineer.setId(40L);
        when(dependencyReader.readTaskContext(TENANT, 7L))
                .thenReturn(Optional.of(new LifecycleDualConfirmationDependencyReader.TaskContext(task, lcCase, engineer)));
        when(evaluator.canViewCase(any(), any(), any())).thenReturn(true);
        when(evaluator.isTaskVisible(any(), any())).thenReturn(true);
        when(epochMapper.selectTaskGeneration(anyLong(), anyLong(), anyString()))
                .thenReturn(new AuthorizationEpochMapper.EpochRow(3L, 0));
        when(projectionMapper.selectOne(any())).thenReturn(LifecycleDualProjection.builder().tenantId(TENANT)
                .taskId(7L).caseId(30L).taskGeneration(3L).revision(1).submissionId(55L)
                .evidenceSha256("a".repeat(64)).state("REJECTED").projectionVersion(1L).build());
        when(dependencyReader.findSubmission(TENANT, 7L, 55L)).thenReturn(LifecycleDualSubmission.builder().id(55L)
                .tenantId(TENANT).caseId(30L).taskId(7L).engineerId(40L).taskGeneration(3L).revision(1)
                .submitterUserId(submitterUserId).submitterRoleSnapshot("営業").businessComment(submissionComment)
                .createdAt(LocalDateTime.now()).build());
        when(confirmationMapper.selectOne(any())).thenReturn(LifecycleDualConfirmation.builder().tenantId(TENANT)
                .taskId(7L).submissionId(55L).confirmerUserId(9L).decision("REJECT").decisionComment(decisionComment)
                .decidedAt(LocalDateTime.now()).build());
    }

    // ===================================================================== helpers

    private List<MockHttpServletRequestBuilder> allEntries() {
        return List.of(
                get("/api/lifecycle/tasks/7/dual-confirmation"),
                get("/api/lifecycle/tasks/7/dual-confirmation/candidates"),
                submit("/api/lifecycle/tasks/7/dual-confirmation/submissions", KEY, SUBMIT_BODY),
                submit("/api/lifecycle/tasks/7/dual-confirmation/submissions/9/decision", KEY, DECISION_BODY),
                get("/api/my/lifecycle/tasks/7/dual-confirmation"),
                get("/api/my/lifecycle/tasks/7/dual-confirmation/candidates"),
                submit("/api/my/lifecycle/tasks/7/dual-confirmation/submissions", KEY, SUBMIT_BODY));
    }

    private static MockHttpServletRequestBuilder submit(String path, String key, String body) {
        return post(path).with(csrf()).locale(Locale.JAPAN).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void expectError(ResultActions result, int status, String key) throws Exception {
        String expected = SafeErrorPolicy.resolveBusinessMessage(BusinessException.of(status, key), messageSource,
                Locale.JAPAN);
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(status))
                .andExpect(jsonPath("$.message").value(expected));
    }

    private Set<String> routes() {
        Set<String> paths = new TreeSet<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (!LifecycleDualConfirmationApiController.class.isAssignableFrom(entry.getValue().getBeanType())) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            paths.addAll(info.getPatternValues());
        }
        return paths;
    }
}
