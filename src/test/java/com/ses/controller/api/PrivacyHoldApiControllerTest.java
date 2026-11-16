package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.config.feature.FeatureSwitchFilter;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.retention.hold.PrivacyHoldOverview;
import com.ses.privacy.production.retention.hold.PrivacyHoldReleaseRequest;
import com.ses.privacy.production.retention.hold.PrivacyHoldReleaseResult;
import com.ses.privacy.production.retention.hold.PrivacyHoldReleaseService;
import com.ses.privacy.production.retention.hold.PrivacyHoldService;
import com.ses.privacy.production.retention.hold.PrivacyHoldStartRequest;
import com.ses.privacy.production.retention.hold.PrivacyHoldStartResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NF07 hold API（A1H-1・NF07-REL）: OFF は404、service 層の再認可で403、409・503 の ApiResult、CSRF 必須。解除の入口は
 * {@code POST /api/privacy/holds/{holdId}/release} の1つだけで、開放前は service が404を返す。
 */
@WebMvcTest(PrivacyHoldApiController.class)
class PrivacyHoldApiControllerTest {

    private static final String START_BODY = "{\"targetType\":\"SUBJECT\",\"subjectRef\":\"ENGINEER:10\","
            + "\"reasonCode\":\"LITIGATION\",\"reasonText\":\"理由\",\"ticketRef\":\"TICKET-1\","
            + "\"unattributableChoices\":[{\"dataElementId\":\"DOCUMENT:IMPORT\",\"choice\":\"ACCEPT_RISK\"}]}";
    private static final String HOLD_ID = "ph-" + "3".repeat(32);
    private static final String RELEASE_BODY = "{\"reasonCode\":\"LITIGATION_CLOSED\",\"reasonText\":\"理由\","
            + "\"ticketRef\":\"TICKET-2\",\"binding\":{\"tenantId\":\"default\",\"subjectRef\":\"ENGINEER:10\","
            + "\"holdId\":\"" + HOLD_ID + "\",\"expectedHoldRevision\":1,\"reasonHash\":\"" + "a".repeat(64) + "\","
            + "\"commandId\":\"hr-" + "b".repeat(32) + "\",\"expectedJournalRevision\":1,"
            + "\"expectedJournalHash\":\"" + "c".repeat(64) + "\",\"requesterRef\":\"sys_user:1\"},"
            + "\"issuer\":{\"authorityId\":\"issuer-a\",\"keyId\":\"k1\",\"issuedAt\":\"2026-11-16T02:00:00Z\","
            + "\"expiresAt\":\"2026-11-16T04:00:00Z\",\"signature\":\"AAAA\"},"
            + "\"approver\":{\"authorityId\":\"approver-b\",\"keyId\":\"k1\",\"issuedAt\":\"2026-11-16T02:00:00Z\","
            + "\"expiresAt\":\"2026-11-16T04:00:00Z\",\"signature\":\"BBBB\"}}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PrivacyHoldService holdService;

    @MockBean
    private PrivacyHoldReleaseService releaseService;

    @Test
    @WithMockUser(roles = "管理者")
    void 公開スイッチOFFならFeatureSwitchFilterと同じ本文の404を返す() throws Exception {
        when(holdService.overview()).thenThrow(new PrivacyAccessGuard.PrivacyEntryNotFoundException());

        mockMvc.perform(get("/api/privacy/holds"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(FeatureSwitchFilter.NOT_FOUND_MESSAGE))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void service層の再認可で拒否されれば403のApiResultを返す() throws Exception {
        when(holdService.overview()).thenThrow(BusinessException.of(403, "error.accessDenied"));

        mockMvc.perform(get("/api/privacy/holds"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 一覧はgateの状態と帰属できないelementと前提を返す() throws Exception {
        PrivacyHoldOverview overview = new PrivacyHoldOverview("VERIFIED", null, "AVAILABLE", false, List.of("d05"),
                List.of("DATA_ELEMENT", "SUBJECT"),
                List.of(new PrivacyHoldOverview.HoldEntry("ph-" + "1".repeat(32), "SUBJECT", "ENGINEER:10", null,
                        "ACTIVE", "LITIGATION", "TICKET-1", "2026-11-16T03:00:00Z", 1, false, "d".repeat(64), null)),
                List.of(new PrivacyHoldOverview.ElementEntry("DOCUMENT:IMPORT", 3L, false)),
                List.of("AUDIT_LOG"), List.of(), List.of("DOCUMENT:IMPORT"), false);
        when(holdService.overview()).thenReturn(overview);

        mockMvc.perform(get("/api/privacy/holds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.gateState").value("VERIFIED"))
                .andExpect(jsonPath("$.data.startReady").value(false))
                .andExpect(jsonPath("$.data.missingPrerequisites[0]").value("d05"))
                .andExpect(jsonPath("$.data.holds[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.holds[0].journalEventHash").value("d".repeat(64)))
                .andExpect(jsonPath("$.data.releaseReady").value(false))
                .andExpect(jsonPath("$.data.unattributableElements[0].dataElementId").value("DOCUMENT:IMPORT"))
                .andExpect(jsonPath("$.data.unattributableElements[0].tenantRowCount").value(3))
                .andExpect(jsonPath("$.data.unclassifiedElements[0]").value("AUDIT_LOG"));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 開始は本文のsubject参照と選択をserviceへ渡しACK後の結果を返す() throws Exception {
        when(holdService.start(any())).thenReturn(
                new PrivacyHoldStartResult("ph-" + "2".repeat(32), "SUBJECT", "ACTIVE", 1));

        mockMvc.perform(post("/api/privacy/holds").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(START_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.journalRevision").value(1));

        ArgumentCaptor<PrivacyHoldStartRequest> captor = ArgumentCaptor.forClass(PrivacyHoldStartRequest.class);
        verify(holdService).start(captor.capture());
        assertThat(captor.getValue().subjectRef()).isEqualTo("ENGINEER:10");
        assertThat(captor.getValue().unattributableChoices()).hasSize(1);
        assertThat(captor.getValue().unattributableChoices().get(0).choice()).isEqualTo("ACCEPT_RISK");
    }

    @Test
    @WithMockUser(roles = "管理者")
    void CSRF_tokenが無い開始は拒否されserviceを呼ばない() throws Exception {
        mockMvc.perform(post("/api/privacy/holds").contentType(MediaType.APPLICATION_JSON).content(START_BODY))
                .andExpect(status().isForbidden());

        verify(holdService, never()).start(any());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 決定の欠落は409で返す() throws Exception {
        when(holdService.start(any())).thenThrow(BusinessException.of(409, "error.privacy.decisionUnknown"));

        mockMvc.perform(post("/api/privacy/holds").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(START_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void Journal不達とACK不明は503で返す() throws Exception {
        when(holdService.start(any())).thenThrow(BusinessException.of(503, "error.privacy.holdAckUnknown"));

        mockMvc.perform(post("/api/privacy/holds").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(START_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 解除はpathのhold_IDと本文の署名付きproofをserviceへ渡し結果を返す() throws Exception {
        when(releaseService.release(any(), any())).thenReturn(
                new PrivacyHoldReleaseResult(HOLD_ID, "RELEASED", 2, false));

        mockMvc.perform(post("/api/privacy/holds/" + HOLD_ID + "/release").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(RELEASE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("RELEASED"))
                .andExpect(jsonPath("$.data.releaseRevision").value(2))
                .andExpect(jsonPath("$.data.replayed").value(false));

        ArgumentCaptor<PrivacyHoldReleaseRequest> captor = ArgumentCaptor.forClass(PrivacyHoldReleaseRequest.class);
        verify(releaseService).release(org.mockito.ArgumentMatchers.eq(HOLD_ID), captor.capture());
        assertThat(captor.getValue().binding().subjectRef()).isEqualTo("ENGINEER:10");
        assertThat(captor.getValue().binding().expectedJournalRevision()).isEqualTo(1L);
        assertThat(captor.getValue().issuer().authorityId()).isEqualTo("issuer-a");
        assertThat(captor.getValue().approver().signature()).isEqualTo("BBBB");
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 開放前の解除はFeatureSwitchFilterと同じ本文の404を返す() throws Exception {
        when(releaseService.release(any(), any())).thenThrow(new PrivacyAccessGuard.PrivacyEntryNotFoundException());

        mockMvc.perform(post("/api/privacy/holds/" + HOLD_ID + "/release").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(RELEASE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(FeatureSwitchFilter.NOT_FOUND_MESSAGE))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 解除のproofの拒否は403_状態の競合は409で返す() throws Exception {
        when(releaseService.release(any(), any()))
                .thenThrow(BusinessException.of(403, "error.privacy.holdReleaseProofRejected"))
                .thenThrow(BusinessException.of(409, "error.privacy.holdReleaseStale"));

        mockMvc.perform(post("/api/privacy/holds/" + HOLD_ID + "/release").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(RELEASE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(post("/api/privacy/holds/" + HOLD_ID + "/release").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(RELEASE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void CSRF_tokenが無い解除は拒否されserviceを呼ばない() throws Exception {
        mockMvc.perform(post("/api/privacy/holds/" + HOLD_ID + "/release")
                        .contentType(MediaType.APPLICATION_JSON).content(RELEASE_BODY))
                .andExpect(status().isForbidden());

        verify(releaseService, never()).release(any(), any());
    }

    @Test
    void 入口はGETとPOSTだけで解除の入口はhold_IDのreleaseの1つだけ() {
        List<String> mappings = Arrays.stream(PrivacyHoldApiController.class.getDeclaredMethods())
                .flatMap(method -> mappingPaths(method).stream())
                .toList();
        for (Method method : PrivacyHoldApiController.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(PutMapping.class)).isFalse();
            assertThat(method.isAnnotationPresent(PatchMapping.class)).isFalse();
            assertThat(method.isAnnotationPresent(DeleteMapping.class)).isFalse();
            assertThat(method.isAnnotationPresent(RequestMapping.class)).isFalse();
        }
        assertThat(mappings.stream().filter(path -> path.contains("release")).toList())
                .containsExactly("/{holdId}/release");
        assertThat(PrivacyHoldApiController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/privacy/holds");
    }

    @Test
    void controllerは保全holdのfeature_packageの型に依存する() {
        for (Constructor<?> constructor : PrivacyHoldApiController.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterTypes()).allSatisfy(type ->
                    assertThat(type.getPackageName()).startsWith("com.ses.privacy.production.retention."));
        }
    }

    private static List<String> mappingPaths(Method method) {
        GetMapping get = method.getAnnotation(GetMapping.class);
        PostMapping post = method.getAnnotation(PostMapping.class);
        if (get != null) {
            return List.of(get.value());
        }
        if (post != null) {
            return List.of(post.value());
        }
        return List.of();
    }
}
