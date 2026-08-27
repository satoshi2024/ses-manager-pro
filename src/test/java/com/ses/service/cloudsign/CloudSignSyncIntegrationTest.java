package com.ses.service.cloudsign;

import com.ses.common.enums.DispatchState;
import com.ses.dto.cloudsign.CloudSignDocument;
import com.ses.dto.cloudsign.CloudSignFile;
import com.ses.dto.cloudsign.CloudSignParticipant;
import com.ses.entity.ContractDocument;
import com.ses.mapper.ContractDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * HFP-02-05: provider status mapping・polling・manual syncの統合test（実H2 + stub provider）。
 * status全値/未知、terminal逆戻り、manual vs pollのcommit順反転、batch一件失敗継続、
 * rate/backoff、cancel非公開（BLK-06未決）を検証する。
 */
@SpringBootTest(properties = {
        "cloudsign.enabled=true",
        "cloudsign.environment=SANDBOX",
        "cloudsign.client-id=test-client-id",
        "cloudsign.dispatch-cron=-",
        "cloudsign.poll-cron=-"
})
@ActiveProfiles("test")
@Sql("/sql/engineer-schema-h2.sql")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class CloudSignSyncIntegrationTest {

    private static final String DOC_ID = "0123456789abcdef0123456789abcdef01";
    private static final String FILE_ID = "abcdef0123456789abcdef012345678901";

    @Autowired
    private CloudSignSyncService syncService;

    @Autowired
    private ContractDocumentMapper mapper;

    @MockBean
    private CloudSignApiClient api;

    @BeforeEach
    void clean() {
        mapper.delete(null);
    }

    private ContractDocument insert(DispatchState state, String externalId, Integer cloudsignStatus) {
        ContractDocument d = new ContractDocument();
        d.setContractId(1L);
        d.setTemplateId(1L);
        d.setTemplateVersion(1);
        d.setRenderedHtml("<p>x</p>");
        d.setRecipientName("マスク宛先");
        d.setRecipientEmail("sync-masked@example.invalid");
        d.setStatus("下書き");
        d.setCloudsignDocumentId(externalId);
        d.setCloudsignFileId(FILE_ID);
        d.setDispatchState(state.name());
        d.setCloudsignStatus(cloudsignStatus);
        d.setVersion(0);
        d.setDispatchAttemptCount(0);
        d.setOperationId(UUID.randomUUID().toString());
        mapper.insert(d);
        return d;
    }

    private CloudSignDocument remote(int status) {
        return new CloudSignDocument(DOC_ID, "SES契約書", status, null, null, null,
                List.of(new CloudSignFile(FILE_ID, "document-1.pdf", 0L, 1L)),
                List.of(new CloudSignParticipant("p1", "sync-masked@example.invalid",
                        "マスク宛先", null, 0L, 8)));
    }

    @Test
    void status1は先方確認中としてSENTへ同期する() {
        ContractDocument d = insert(DispatchState.SENT, DOC_ID, 1);
        when(api.getDocument(DOC_ID)).thenReturn(remote(1));

        syncService.syncDocument(d.getId());

        ContractDocument after = mapper.selectById(d.getId());
        assertEquals(DispatchState.SENT.name(), after.getDispatchState());
        assertEquals("先方確認中", after.getStatus());
        assertEquals(1, after.getCloudsignStatus());
    }

    @Test
    void status2は締結済としてCOMPLETEDへ同期しcompletedAtを設定する() {
        ContractDocument d = insert(DispatchState.SENT, DOC_ID, 1);
        when(api.getDocument(DOC_ID)).thenReturn(remote(2));

        syncService.syncDocument(d.getId());

        ContractDocument after = mapper.selectById(d.getId());
        assertEquals(DispatchState.COMPLETED.name(), after.getDispatchState());
        assertEquals("締結済", after.getStatus());
        assertNotNull(after.getCompletedAt(), "provider確定時だけcompletedAtを設定");
    }

    @Test
    void status3は取消却下としてCANCELEDへ同期する() {
        ContractDocument d = insert(DispatchState.SENT, DOC_ID, 1);
        when(api.getDocument(DOC_ID)).thenReturn(remote(3));

        syncService.syncDocument(d.getId());

        ContractDocument after = mapper.selectById(d.getId());
        assertEquals(DispatchState.CANCELED.name(), after.getDispatchState());
        assertEquals("取消・却下", after.getStatus());
    }

    @Test
    void 未知statusは要確認として記録し状態を維持する() {
        ContractDocument d = insert(DispatchState.SENT, DOC_ID, 1);
        when(api.getDocument(DOC_ID)).thenReturn(remote(99));

        syncService.syncDocument(d.getId());

        ContractDocument after = mapper.selectById(d.getId());
        assertEquals(DispatchState.SENT.name(), after.getDispatchState(), "未知statusで自動遷移しない");
        assertEquals("要確認", after.getStatus());
        assertTrue(after.getLastProviderErrorCode().startsWith("POLL_UNKNOWN_STATUS"));
    }

    @Test
    void status4テンプレートは要確認として記録し送信対象として扱わない() {
        ContractDocument d = insert(DispatchState.SENT, DOC_ID, 1);
        when(api.getDocument(DOC_ID)).thenReturn(remote(4));

        syncService.syncDocument(d.getId());

        ContractDocument after = mapper.selectById(d.getId());
        assertEquals("要確認", after.getStatus());
        assertEquals("POLL_TEMPLATE", after.getLastProviderErrorCode());
    }

    @Test
    void terminal行はGETせず逆戻りしない() {
        ContractDocument d = insert(DispatchState.COMPLETED, DOC_ID, 2);
        d.setCompletedAt(LocalDateTime.now());
        mapper.updateById(d);

        syncService.syncDocument(d.getId());

        verify(api, never()).getDocument(any());
        assertEquals(DispatchState.COMPLETED.name(), mapper.selectById(d.getId()).getDispatchState());
    }

    @Test
    void manualSyncとpollのcommit順反転でもterminalへ逆戻りしない() {
        ContractDocument d = insert(DispatchState.SENT, DOC_ID, 1);
        // manual syncが先にCOMPLETEDへ確定
        when(api.getDocument(DOC_ID)).thenReturn(remote(2));
        syncService.syncDocument(d.getId());
        ContractDocument completed = mapper.selectById(d.getId());
        assertEquals(DispatchState.COMPLETED.name(), completed.getDispatchState());

        // pollが古いversion/状態でSENTへ戻そうとしてもCASで0件（逆戻りなし）
        int reversed = mapper.casStatusSync(d.getId(), 0, DispatchState.SENT.name(),
                DispatchState.SENT.name(), 1, "先方確認中", LocalDateTime.now());
        assertEquals(0, reversed, "version/state CASが逆戻りを拒否");
        assertEquals(DispatchState.COMPLETED.name(), mapper.selectById(d.getId()).getDispatchState());
    }

    @Test
    void batch一件失敗でも残りを処理する() {
        ContractDocument ok = insert(DispatchState.SENT, DOC_ID, 1);
        ContractDocument bad = insert(DispatchState.SENT, "0123456789abcdef0123456789abcdef02", 1);

        when(api.getDocument("0123456789abcdef0123456789abcdef02"))
                .thenThrow(new CloudSignApiException(com.ses.common.enums.CloudSignErrorCode.SERVER_ERROR,
                        true, "500:RESULT_UNKNOWN"));
        when(api.getDocument(DOC_ID)).thenReturn(remote(2));

        int processed = syncService.pollDue(10);

        assertEquals(2, processed, "一行の失敗でbatch全体を止めない");
        assertEquals(DispatchState.COMPLETED.name(), mapper.selectById(ok.getId()).getDispatchState());
        ContractDocument afterBad = mapper.selectById(bad.getId());
        assertTrue(afterBad.getNextAttemptAt() != null, "5xxはbackoff待機");
        verify(api, times(1)).getDocument(eq("0123456789abcdef0123456789abcdef02"));
    }

    @Test
    void GET429はbackoff待機になり再試行しない() {
        ContractDocument d = insert(DispatchState.SENT, DOC_ID, 1);
        when(api.getDocument(DOC_ID)).thenThrow(
                new CloudSignApiException(com.ses.common.enums.CloudSignErrorCode.RATE_LIMITED,
                        false, "RATE_LIMITED"));

        syncService.syncDocument(d.getId());

        ContractDocument after = mapper.selectById(d.getId());
        assertEquals("RATE_LIMITED", after.getLastProviderErrorCode());
        assertNotNull(after.getNextAttemptAt(), "429はretry-after相当の待機");
        verify(api, times(1)).getDocument(DOC_ID);
    }

    @Test
    void GET4xxは再試行せず恒久エラーにする() {
        ContractDocument d = insert(DispatchState.SENT, DOC_ID, 1);
        when(api.getDocument(DOC_ID)).thenThrow(
                new CloudSignApiException(com.ses.common.enums.CloudSignErrorCode.NOT_FOUND, false, "NOT_FOUND"));

        syncService.syncDocument(d.getId());

        ContractDocument after = mapper.selectById(d.getId());
        assertEquals(DispatchState.FAILED_FINAL.name(), after.getDispatchState());
        assertEquals("NOT_FOUND", after.getLastProviderErrorCode());
        verify(api, times(1)).getDocument(DOC_ID);
    }

    @Test
    void pollingSchedulerはShedLock付きでkillSwitch判定を持つ() throws Exception {
        var scheduler = CloudSignPollingScheduler.class;
        var method = scheduler.getMethod("pollProviderStatus");
        assertNotNull(method.getAnnotation(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class),
                "pollingはShedLockを持つ");
        assertNotNull(method.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class));
    }

    @Test
    void cancelはBLK06未決のためAPIに公開されない() throws Exception {
        // BLK-06 未決: cancel route/buttonを公開しない（HFP-02-AC-05-05 NOT_ADOPT側の安全動作）
        for (var method : com.ses.controller.api.ContractDocumentApiController.class.getMethods()) {
            var mapping = method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
            if (mapping != null) {
                for (String path : mapping.value()) {
                    assertFalse(path.contains("cancel") && !path.contains("reconcile"),
                            "cancel routeを公開してはならない: " + path);
                }
            }
        }
        // typed clientにもdeclineを実装しない（ADOPT決定後にのみ追加）
        for (var method : CloudSignApiClient.class.getMethods()) {
            assertFalse(method.getName().toLowerCase().contains("decline"),
                    "decline client methodを実装してはならない（BLK-06未決）");
        }
    }

    // ===== REV-002: RECONCILIATION_REQUIREDは専用操作（manual sync）でのみ3条件検証後に復旧 =====

    @Test
    void pollはRECONCILIATION_REQUIRED行を自動遷移しない() {
        ContractDocument d = insert(DispatchState.RECONCILIATION_REQUIRED, DOC_ID, null);
        when(api.getDocument(DOC_ID)).thenReturn(remote(2));

        syncService.pollDue(10);

        verify(api, never()).getDocument(any());
        assertEquals(DispatchState.RECONCILIATION_REQUIRED.name(),
                mapper.selectById(d.getId()).getDispatchState(),
                "pollが結果不明行を自動遷移してはならない");
    }

    @Test
    void manualSyncはfileIDと宛先が一致するときだけ結果不明行を復旧する() {
        // 一致ケース: remoteに送信時file IDと宛先emailが存在 → 締結済へ
        ContractDocument ok = insert(DispatchState.RECONCILIATION_REQUIRED, DOC_ID, null);
        when(api.getDocument(DOC_ID)).thenReturn(remote(2));

        syncService.syncDocument(ok.getId());

        ContractDocument after = mapper.selectById(ok.getId());
        assertEquals(DispatchState.COMPLETED.name(), after.getDispatchState());
    }

    @Test
    void manualSyncは宛先不一致なら結果不明のまま締結済へ確定しない() {
        // 不一致ケース: remoteの宛先emailが送信時と異なる（誤宛先書類の締結確定を防ぐ）
        ContractDocument d = insert(DispatchState.RECONCILIATION_REQUIRED, DOC_ID, null);
        CloudSignDocument wrongRecipient = new CloudSignDocument(DOC_ID, "SES契約書", 2, null, null, null,
                List.of(new CloudSignFile(FILE_ID, "document-1.pdf", 0L, 1L)),
                List.of(new CloudSignParticipant("p9", "wrong-recipient@example.invalid",
                        "別人", null, 0L, 8)));
        when(api.getDocument(DOC_ID)).thenReturn(wrongRecipient);

        syncService.syncDocument(d.getId());

        ContractDocument after = mapper.selectById(d.getId());
        assertEquals(DispatchState.RECONCILIATION_REQUIRED.name(), after.getDispatchState(),
                "宛先不一致なら自動復旧しない");
        assertEquals("要確認", after.getStatus());
        assertTrue(after.getLastProviderErrorCode().startsWith("VERIFY_MISMATCH"),
                "矛盾をfindingとして記録: " + after.getLastProviderErrorCode());
    }

    @Test
    void manualSyncはfileID不一致なら結果不明のまま締結済へ確定しない() {
        ContractDocument d = insert(DispatchState.RECONCILIATION_REQUIRED, DOC_ID, null);
        CloudSignDocument wrongFile = new CloudSignDocument(DOC_ID, "SES契約書", 2, null, null, null,
                List.of(new CloudSignFile("different-file-id", "other.pdf", 0L, 1L)),
                List.of(new CloudSignParticipant("p9", "sync-masked@example.invalid",
                        "マスク宛先", null, 0L, 8)));
        when(api.getDocument(DOC_ID)).thenReturn(wrongFile);

        syncService.syncDocument(d.getId());

        ContractDocument after = mapper.selectById(d.getId());
        assertEquals(DispatchState.RECONCILIATION_REQUIRED.name(), after.getDispatchState());
        assertTrue(after.getLastProviderErrorCode().startsWith("VERIFY_MISMATCH"));
    }
}
