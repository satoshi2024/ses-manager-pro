package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.config.feature.FeatureSwitchFilter;
import com.ses.controller.page.PrivacyDsarPageController;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.dsar.PrivacyDsarCommands;
import com.ses.privacy.production.dsar.PrivacyDsarViews;
import com.ses.privacy.production.dsar.PrivacyExportPackageService;
import com.ses.privacy.production.dsar.PrivacyRequestService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NF07 DSAR API（A2a・A2b）: OFF は404、service 層の再認可で403、409・503 の ApiResult、CSRF 必須、subject 参照は本文だけ、
 * A2b の開示資料の区画・草案・承認・専用ダウンロード・交付記録（package の取得は専用ダウンロードだけで、外部へ送る入口は持たない）、
 * controller は DSAR の feature package の型に依存する。
 */
@WebMvcTest(PrivacyDsarApiController.class)
class PrivacyDsarApiControllerTest {

    private static final PrivacyDsarViews.Transition TRANSITION =
            new PrivacyDsarViews.Transition(12L, "DSAR-20261116-ABCDEFGH", "IDENTITY_VERIFIED", 3L);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PrivacyRequestService requestService;

    @MockBean
    private PrivacyExportPackageService packageService;

    @Test
    @WithMockUser(roles = "管理者")
    void 公開スイッチOFFならFeatureSwitchFilterと同じ本文の404を返す() throws Exception {
        when(requestService.overview(any(), anyLong(), anyLong()))
                .thenThrow(new PrivacyAccessGuard.PrivacyEntryNotFoundException());
        when(requestService.intake(any())).thenThrow(new PrivacyAccessGuard.PrivacyEntryNotFoundException());

        mockMvc.perform(get("/api/privacy/dsar"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(FeatureSwitchFilter.NOT_FOUND_MESSAGE))
                .andExpect(jsonPath("$.data").doesNotExist());
        mockMvc.perform(post("/api/privacy/dsar").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestType\":\"ACCESS\",\"channel\":\"EMAIL\",\"intakeTicketRef\":\"T-1\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(FeatureSwitchFilter.NOT_FOUND_MESSAGE));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void service層の再認可で拒否されれば403のApiResultを返す() throws Exception {
        when(requestService.detail(12L)).thenThrow(BusinessException.of(403, "error.accessDenied"));

        mockMvc.perform(get("/api/privacy/dsar/12"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 一覧は前提と受付の要約を返しsubject参照を含めない() throws Exception {
        PrivacyDsarViews.Overview overview = new PrivacyDsarViews.Overview(
                new PrivacyDsarViews.Readiness(false, List.of("d06"), false, null),
                List.of(new PrivacyDsarViews.Summary(12L, "DSAR-20261116-ABCDEFGH", "ACCESS", "REQUESTED", "EMAIL",
                        "2026-11-16 10:00", null, false, false, 0L)), 1L, 1L, 20L);
        when(requestService.overview("REQUESTED", 2L, 50L)).thenReturn(overview);

        mockMvc.perform(get("/api/privacy/dsar").param("status", "REQUESTED").param("page", "2").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.readiness.identityReady").value(false))
                .andExpect(jsonPath("$.data.readiness.missing[0]").value("d06"))
                .andExpect(jsonPath("$.data.requests[0].requestNo").value("DSAR-20261116-ABCDEFGH"))
                .andExpect(jsonPath("$.data.requests[0].subjectRef").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 詳細は候補の件数だけを返す() throws Exception {
        PrivacyDsarViews.Detail detail = new PrivacyDsarViews.Detail(12L, "DSAR-20261116-ABCDEFGH", "ACCESS",
                "IDENTITY_VERIFIED", "EMAIL", "2026-11-16 10:00", "2026-12-16", false, "T-1", "VERIFIED", "EV-1",
                "D06-NF07-2026-0006", "2026-11-16 11:00", null, 3L,
                List.of(new PrivacyDsarViews.Candidate(5L, "ENGINEER:7", "ENGINEER", "CANDIDATE", null, null, true,
                        false, List.of(new PrivacyDsarViews.FootprintEntry("engineer-profile", "ENGINEER_PROFILE", 1,
                                "MIXED_THIRD_PARTY")))),
                0, null, null, List.of("ADD_CANDIDATE", "SCOPE"),
                new PrivacyDsarViews.Readiness(true, List.of(), true, 30));
        when(requestService.detail(12L)).thenReturn(detail);

        mockMvc.perform(get("/api/privacy/dsar/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].subjectRef").value("ENGINEER:7"))
                .andExpect(jsonPath("$.data.candidates[0].footprint[0].rowCount").value(1))
                .andExpect(jsonPath("$.data.candidates[0].footprint[0].fields").doesNotExist())
                .andExpect(jsonPath("$.data.candidates[0].footprintOmitted").value(false))
                .andExpect(jsonPath("$.data.nextActions[1]").value("SCOPE"));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 状態を変える要求は本文をそのままserviceへ渡し遷移の結果を返す() throws Exception {
        when(requestService.intake(any())).thenReturn(new PrivacyDsarViews.Transition(12L, "DSAR-1", "REQUESTED", 0L));
        when(requestService.verifyIdentity(eq(12L), any())).thenReturn(TRANSITION);
        when(requestService.addCandidate(eq(12L), any())).thenReturn(TRANSITION);
        when(requestService.resolveCandidate(eq(12L), eq(5L), any())).thenReturn(TRANSITION);
        when(requestService.scope(eq(12L), any())).thenReturn(TRANSITION);
        when(requestService.block(eq(12L), any())).thenReturn(TRANSITION);
        when(requestService.cancel(eq(12L), any())).thenReturn(TRANSITION);

        mockMvc.perform(post("/api/privacy/dsar").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestType\":\"ACCESS\",\"channel\":\"EMAIL\",\"intakeTicketRef\":\"T-1\","
                                + "\"receivedAt\":\"2026-11-16T10:00\",\"reopenOfRequestId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));
        ArgumentCaptor<PrivacyDsarCommands.Intake> intake = ArgumentCaptor.forClass(PrivacyDsarCommands.Intake.class);
        verify(requestService).intake(intake.capture());
        assertThat(intake.getValue().intakeTicketRef()).isEqualTo("T-1");
        assertThat(intake.getValue().reopenOfRequestId()).isEqualTo(3L);

        mockMvc.perform(post("/api/privacy/dsar/12/identity").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":2,\"identityEvidenceRef\":\"EV-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowVersion").value(3));
        mockMvc.perform(post("/api/privacy/dsar/12/candidates").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":3,\"subjectRef\":\"ENGINEER:7\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<PrivacyDsarCommands.CandidateAddition> addition =
                ArgumentCaptor.forClass(PrivacyDsarCommands.CandidateAddition.class);
        verify(requestService).addCandidate(eq(12L), addition.capture());
        assertThat(addition.getValue().subjectRef()).isEqualTo("ENGINEER:7");

        mockMvc.perform(put("/api/privacy/dsar/12/candidates/5").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":4,\"linkState\":\"SELECTED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/privacy/dsar/12/scope").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":5}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/privacy/dsar/12/block").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":5,\"reasonCode\":\"MULTIPLE_CANDIDATES\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/privacy/dsar/12/cancel").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":5,\"reasonCode\":\"WITHDRAWN\",\"evidenceRef\":\"T-2\"}"))
                .andExpect(status().isOk());
        verify(requestService).resolveCandidate(eq(12L), eq(5L), any());
        verify(requestService).scope(eq(12L), any());
        verify(requestService).block(eq(12L), any());
        verify(requestService).cancel(eq(12L), any());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void CSRF_tokenが無い状態変更は拒否されserviceを呼ばない() throws Exception {
        mockMvc.perform(post("/api/privacy/dsar/12/scope").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":5}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/privacy/dsar/12/candidates/5").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":4,\"linkState\":\"SELECTED\"}"))
                .andExpect(status().isForbidden());

        verify(requestService, never()).scope(anyLong(), any());
        verify(requestService, never()).resolveCandidate(anyLong(), anyLong(), any());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 決定の欠落とCAS競合は409で返し照会の失敗は503で返す() throws Exception {
        when(requestService.verifyIdentity(eq(12L), any()))
                .thenThrow(BusinessException.of(409, "error.privacy.dsarDecisionUnknown"));
        when(requestService.scope(eq(12L), any()))
                .thenThrow(BusinessException.of(409, "error.privacy.dsarConflict"));
        when(requestService.addCandidate(eq(12L), any()))
                .thenThrow(BusinessException.of(503, "error.privacy.dsarSourceUnavailable"));

        mockMvc.perform(post("/api/privacy/dsar/12/identity").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":0,\"identityEvidenceRef\":\"EV-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
        mockMvc.perform(post("/api/privacy/dsar/12/scope").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":1}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/privacy/dsar/12/candidates").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":1,\"subjectRef\":\"ENGINEER:7\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
    }

    /**
     * NF07-A2b で入口を足した: 開示資料の区画・草案・承認・専用ダウンロード・交付記録。package の取得は {@code /{id}/package/download}
     * だけで、system から外部へ送る入口（export・送信）は持たない。
     */
    @Test
    void 入口はDSARのprefixの下に限り開示packageの取得は専用ダウンロードだけで外部へ送る入口を持たない() {
        List<String> paths = new ArrayList<>();
        for (Method method : PrivacyDsarApiController.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(PatchMapping.class)).as(method.getName()).isFalse();
            assertThat(method.isAnnotationPresent(DeleteMapping.class)).as(method.getName()).isFalse();
            assertThat(method.isAnnotationPresent(RequestMapping.class)).as(method.getName()).isFalse();
            paths.addAll(mappingPaths(method));
        }
        assertThat(paths).containsExactlyInAnyOrder("", "/{id}", "", "/{id}/identity", "/{id}/candidates",
                "/{id}/candidates/{linkId}", "/{id}/scope", "/{id}/block", "/{id}/cancel",
                "/{id}/delivery", "/{id}/preview", "/{id}/approve", "/{id}/package/download", "/{id}/delivery",
                "/{id}/approve/preview", "/{id}/resolution");
        assertThat(paths).filteredOn(path -> path.contains("package")).containsExactly("/{id}/package/download");
        assertThat(paths).noneMatch(path -> path.contains("export") || path.contains("send")
                || path.contains("mail") || path.contains("files"));
        assertThat(PrivacyDsarApiController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/privacy/dsar");
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 開示資料の区画は件数と理由とhashだけを返し保存名と記録の値を返さない() throws Exception {
        PrivacyDsarViews.DeliverySection section = new PrivacyDsarViews.DeliverySection("PREVIEWED", 5L, true, true,
                true, false, new PrivacyDsarViews.HoldStatus("NONE"),
                new PrivacyDsarViews.PackageSummary(7L, "DRAFT", "a".repeat(64), 120L, "2026-11-16 10:00",
                        "2026-12-16 10:00", false, false),
                false, false, null, "REDACTION_RULES_UNSET", 0,
                List.of(new PrivacyDsarViews.ProviderCount("engineer-profile", 2)),
                List.of(new PrivacyDsarViews.ManualReviewItem(null, "MAILBOX", "PROVIDER_UNREGISTERED", null)),
                null, false, null, List.of("APPROVE"), null);
        when(packageService.section(12L)).thenReturn(section);

        mockMvc.perform(get("/api/privacy/dsar/12/delivery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentPackage.contentSha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.data.currentPackage.storedName").doesNotExist())
                .andExpect(jsonPath("$.data.manualReview[0].reason").value("PROVIDER_UNREGISTERED"))
                .andExpect(jsonPath("$.data.hold.state").value("NONE"))
                .andExpect(jsonPath("$.data.actions[0]").value("APPROVE"))
                .andExpect(jsonPath("$.data.holdConflictResolutions").isEmpty());
    }

    /** HYG-FINAL P3-F: 本人が HELD のときの手動対応の警告（記録前の結果コード・記録後の印）を API で返す。 */
    @Test
    @WithMockUser(roles = "管理者")
    void 本人がHELDのとき手動対応の区画は警告になる結果コードと記録した結果の印を返す() throws Exception {
        PrivacyDsarViews.DeliverySection section = new PrivacyDsarViews.DeliverySection("CONFIRMED", 5L, false, false,
                true, false, new PrivacyDsarViews.HoldStatus("HELD"), null, false, false, null, null, null,
                List.of(), List.of(), null, false,
                new PrivacyDsarViews.ManualResolutionRecord("DELETED", "DEL-1", "2026-11-16 10:00", true),
                List.of(), List.of("DELETED"));
        when(packageService.section(13L)).thenReturn(section);

        mockMvc.perform(get("/api/privacy/dsar/13/delivery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hold.state").value("HELD"))
                .andExpect(jsonPath("$.data.holdConflictResolutions[0]").value("DELETED"))
                .andExpect(jsonPath("$.data.manualResolution.holdConflict").value(true));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 草案と承認と交付記録は本文をそのままserviceへ渡しCSRF_tokenが無ければ拒否する() throws Exception {
        when(packageService.preview(eq(12L), any())).thenReturn(TRANSITION);
        when(packageService.approve(eq(12L), any())).thenReturn(TRANSITION);
        when(packageService.recordDelivery(eq(12L), any())).thenReturn(TRANSITION);
        String sha = "b".repeat(64);

        mockMvc.perform(post("/api/privacy/dsar/12/preview").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":4}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/privacy/dsar/12/approve").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":5,\"packageId\":7,\"contentSha256\":\"" + sha + "\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<PrivacyDsarCommands.Approval> approval = ArgumentCaptor.forClass(PrivacyDsarCommands.Approval.class);
        verify(packageService).approve(eq(12L), approval.capture());
        assertThat(approval.getValue().packageId()).isEqualTo(7L);
        assertThat(approval.getValue().contentSha256()).isEqualTo(sha);
        mockMvc.perform(post("/api/privacy/dsar/12/delivery").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":6,\"packageId\":7,\"contentSha256\":\"" + sha
                                + "\",\"deliveryMethod\":\"POSTAL\",\"deliveryEvidenceRef\":\"SEND-1\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<PrivacyDsarCommands.Delivery> delivery = ArgumentCaptor.forClass(PrivacyDsarCommands.Delivery.class);
        verify(packageService).recordDelivery(eq(12L), delivery.capture());
        assertThat(delivery.getValue().deliveryMethod()).isEqualTo("POSTAL");

        for (String path : List.of("/preview", "/approve", "/delivery")) {
            mockMvc.perform(post("/api/privacy/dsar/12" + path).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rowVersion\":5}"))
                    .andExpect(status().isForbidden());
        }
        verify(packageService).preview(eq(12L), any());
        verify(packageService).approve(eq(12L), any());
        verify(packageService).recordDelivery(eq(12L), any());
    }

    /**
     * review P2-3: 承認者の草案の確認は inline の JSON で返し、保存させない。拒否は ApiResult の JSON。
     * HYG-FINAL P3-C: 内容 hash は {@code X-Content-Sha256} header で受け取り、URL の query の hash は読まない（access log に残さない）。
     */
    @Test
    @WithMockUser(roles = "管理者")
    void 承認者の草案の確認はinlineで保存させず拒否はApiResultで返す() throws Exception {
        byte[] content = "{\"format\":\"nf07-dsar-export-package-v1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = "c".repeat(64);
        when(packageService.reviewDraft(12L, 7L, sha))
                .thenReturn(new PrivacyDsarViews.PackageDownload("DSAR-20261116-ABCDEFGH-draft.json", content));
        when(packageService.reviewDraft(12L, 8L, sha))
                .thenThrow(BusinessException.of(403, "error.privacy.dsarDraftReviewByPreparer"));
        when(packageService.reviewDraft(12L, 7L, null))
                .thenThrow(BusinessException.of(400, "error.privacy.dsarInvalidRequest"));

        mockMvc.perform(get("/api/privacy/dsar/12/approve/preview").param("packageId", "7")
                        .header(PrivacyDsarApiController.CONTENT_SHA256_HEADER, sha))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(content))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline;")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Content-Type-Options", "nosniff"));
        mockMvc.perform(get("/api/privacy/dsar/12/approve/preview").param("packageId", "8")
                        .header(PrivacyDsarApiController.CONTENT_SHA256_HEADER, sha))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(get("/api/privacy/dsar/12/approve/preview").param("packageId", "7"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        // 旧形式（query の hash）は読まず、header が無いものとして service が 400 にする
        mockMvc.perform(get("/api/privacy/dsar/12/approve/preview").param("packageId", "7").param("contentSha256", sha))
                .andExpect(status().isBadRequest());
        verify(packageService, org.mockito.Mockito.times(2)).reviewDraft(12L, 7L, null);
    }

    /** review P2-2: 手動対応の完了は本文をそのまま渡し、CSRF token が無ければ拒否する。 */
    @Test
    @WithMockUser(roles = "管理者")
    void 手動対応の完了は本文をそのままserviceへ渡しCSRF_tokenが無ければ拒否する() throws Exception {
        PrivacyDsarViews.Transition recorded = new PrivacyDsarViews.Transition(12L, "DSAR-1", "CONFIRMED", 5L);
        when(requestService.recordManualResolution(eq(12L), any())).thenReturn(recorded);
        when(packageService.resolutionRecorded(recorded))
                .thenReturn(new PrivacyDsarViews.ResolutionRecorded(12L, "DSAR-1", "CONFIRMED", 5L, "NOT_HELD", false));

        mockMvc.perform(post("/api/privacy/dsar/12/resolution").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":4,\"resolutionCode\":\"DELETED\",\"evidenceRef\":\"ANS-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.rowVersion").value(5))
                .andExpect(jsonPath("$.data.holdState").value("NOT_HELD"))
                .andExpect(jsonPath("$.data.holdConflict").value(false));
        ArgumentCaptor<PrivacyDsarCommands.ManualResolution> resolution =
                ArgumentCaptor.forClass(PrivacyDsarCommands.ManualResolution.class);
        verify(requestService).recordManualResolution(eq(12L), resolution.capture());
        assertThat(resolution.getValue().resolutionCode()).isEqualTo("DELETED");
        assertThat(resolution.getValue().evidenceRef()).isEqualTo("ANS-1");
        verify(packageService).resolutionRecorded(recorded);

        mockMvc.perform(post("/api/privacy/dsar/12/resolution").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":4,\"resolutionCode\":\"DELETED\",\"evidenceRef\":\"ANS-1\"}"))
                .andExpect(status().isForbidden());
        verify(requestService).recordManualResolution(eq(12L), any());
    }

    /**
     * HYG-FINAL P3-F の残り（RB-NF07-HOLD）: 本人が HELD のとき削除・利用停止を記録した応答そのものに警告の印を返す
     * （区画を読み直す前に画面が警告できる）。記録は止めない。
     */
    @Test
    @WithMockUser(roles = "管理者")
    void 本人がHELDのとき削除の手動対応の記録の応答はholdの状態と警告の印を返す() throws Exception {
        PrivacyDsarViews.Transition recorded = new PrivacyDsarViews.Transition(13L, "DSAR-2", "CONFIRMED", 6L);
        when(requestService.recordManualResolution(eq(13L), any())).thenReturn(recorded);
        when(packageService.resolutionRecorded(recorded))
                .thenReturn(new PrivacyDsarViews.ResolutionRecorded(13L, "DSAR-2", "CONFIRMED", 6L, "HELD", true));

        mockMvc.perform(post("/api/privacy/dsar/13/resolution").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowVersion\":5,\"resolutionCode\":\"DELETED\",\"evidenceRef\":\"DEL-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.holdState").value("HELD"))
                .andExpect(jsonPath("$.data.holdConflict").value(true));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 専用ダウンロードは承認したpackageだけを添付として返し保存も推測もさせない() throws Exception {
        byte[] content = "{\"format\":\"nf07-dsar-export-package-v1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(packageService.download(12L, 7L))
                .thenReturn(new PrivacyDsarViews.PackageDownload("DSAR-20261116-ABCDEFGH.json", content));

        mockMvc.perform(get("/api/privacy/dsar/12/package/download").param("packageId", "7"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(content))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString("DSAR-20261116-ABCDEFGH.json")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 専用ダウンロードの各拒否はApiResultのJSONで返しpackage_idが無ければserviceを呼ばない() throws Exception {
        when(packageService.download(12L, 8L))
                .thenThrow(BusinessException.of(409, "error.privacy.dsarPackageNotApproved"));
        when(packageService.download(12L, 9L)).thenThrow(BusinessException.of(403, "error.accessDenied"));
        when(packageService.download(13L, 7L)).thenThrow(BusinessException.of(404, "error.privacy.dsarNotFound"));
        when(packageService.download(14L, 7L)).thenThrow(new PrivacyAccessGuard.PrivacyEntryNotFoundException());
        when(packageService.download(15L, 7L))
                .thenThrow(BusinessException.of(409, "error.privacy.dsarPackageIntegrity"));

        mockMvc.perform(get("/api/privacy/dsar/12/package/download").param("packageId", "8"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
        mockMvc.perform(get("/api/privacy/dsar/12/package/download").param("packageId", "9"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(get("/api/privacy/dsar/13/package/download").param("packageId", "7"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/privacy/dsar/14/package/download").param("packageId", "7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(FeatureSwitchFilter.NOT_FOUND_MESSAGE));
        mockMvc.perform(get("/api/privacy/dsar/15/package/download").param("packageId", "7"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/privacy/dsar/12/package/download"))
                .andExpect(status().is4xxClientError());
        verify(packageService, never()).download(eq(12L), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void controllerはDSARのfeature_packageの型に依存する() {
        for (Class<?> controller : List.of(PrivacyDsarApiController.class, PrivacyDsarPageController.class)) {
            for (Constructor<?> constructor : controller.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes()).allSatisfy(type ->
                        assertThat(type.getPackageName()).isEqualTo("com.ses.privacy.production.dsar"));
            }
        }
    }

    @Test
    void 画面の入口もservice層の再認可を通しOFFは404で権限が無ければ403にする() {
        PrivacyRequestService service = mock(PrivacyRequestService.class);
        PrivacyDsarPageController page = new PrivacyDsarPageController(service);

        assertThat(page.list()).isEqualTo("privacy/dsar/list");
        ExtendedModelMap model = new ExtendedModelMap();
        assertThat(page.detail(12L, model)).isEqualTo("privacy/dsar/detail");
        assertThat(model.get("requestId")).isEqualTo(12L);
        assertThatThrownBy(() -> page.detail(0L, new ExtendedModelMap()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        doThrow(new PrivacyAccessGuard.PrivacyEntryNotFoundException()).when(service).requirePageAccess();
        assertThatThrownBy(page::list).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        doThrow(BusinessException.of(403, "error.accessDenied")).when(service).requirePageAccess();
        assertThatThrownBy(() -> page.detail(12L, new ExtendedModelMap()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static List<String> mappingPaths(Method method) {
        GetMapping get = method.getAnnotation(GetMapping.class);
        PostMapping post = method.getAnnotation(PostMapping.class);
        PutMapping put = method.getAnnotation(PutMapping.class);
        String[] values = get != null ? get.value() : post != null ? post.value() : put != null ? put.value() : null;
        if (values == null) {
            return List.of();
        }
        return values.length == 0 ? List.of("") : List.of(values);
    }
}
