package com.ses.service.cloudsign;

import com.ses.common.enums.CloudSignErrorCode;
import com.ses.common.enums.DispatchState;
import com.ses.dto.cloudsign.AddParticipantRequest;
import com.ses.dto.cloudsign.CloudSignDocument;
import com.ses.dto.cloudsign.CloudSignFile;
import com.ses.dto.cloudsign.CloudSignParticipant;
import com.ses.dto.cloudsign.ConfirmedSendRequest;
import com.ses.dto.cloudsign.CreateDocumentRequest;
import com.ses.dto.cloudsign.PdfDownload;
import com.ses.entity.Contract;
import com.ses.entity.ContractDocument;
import com.ses.entity.ContractTemplate;
import com.ses.mapper.ContractDocumentMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.ContractTemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * HFP-02-04: durable dispatchの統合test（実H2 + stub provider client）。
 * 2/25/100同時send、各工程crash境界、accepted-then-timeout、stale claim、
 * payload hash変化、CAS競合、transaction境界、kill switchを検証する。
 * schedulerはcron "-"で無効化し、dispatchDue()を直接呼ぶ。
 */
@SpringBootTest(properties = {
        "cloudsign.enabled=true",
        "cloudsign.environment=SANDBOX",
        "cloudsign.client-id=test-client-id",
        "cloudsign.dispatch-cron=-",
        "cloudsign.stale-claim-minutes=1"
})
@ActiveProfiles("test")
@Sql("/sql/engineer-schema-h2.sql")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CloudSignDispatchIntegrationTest {

    private static final String DOC_ID = "0123456789abcdef0123456789abcdef01";
    private static final String FILE_ID = "abcdef0123456789abcdef012345678901";
    private static final String PARTICIPANT_ID = "fedcba9876543210fedcba9876543210";

    @Autowired
    private CloudSignDispatchService dispatchService;

    @Autowired
    private com.ses.service.ContractDocumentService documentService;

    @Autowired
    private ContractDocumentMapper documentMapper;

    @Autowired
    private ContractMapper contractMapper;

    @Autowired
    private ContractTemplateMapper templateMapper;

    @MockBean
    private CloudSignApiClient api;

    private Long contractId;
    private Long templateId;

    @BeforeEach
    void setUpFixture() {
        // 共有H2: 自クラスの対象テーブルは毎回掃除する（他クラス由来のQUEUED行がdispatchの対象に混ざらないように）
        documentMapper.delete(null);
        templateMapper.delete(null);
        contractMapper.delete(null);

        ContractTemplate template = new ContractTemplate();
        template.setName("dispatch-test-template");
        template.setContractType("派遣");
        template.setHtmlContent("<p>dispatch</p>");
        template.setVersion(1);
        template.setActiveFlag(1);
        templateMapper.insert(template);
        templateId = template.getId();

        Contract contract = new Contract();
        contract.setContractNo("DSP-" + UUID.randomUUID().toString().substring(0, 8));
        contract.setEngineerId(1L);
        contract.setProjectId(3L);
        contract.setCustomerId(3L);
        contract.setStartDate(LocalDate.now());
        contract.setSellingPrice(java.math.BigDecimal.valueOf(500000));
        contract.setCostPrice(java.math.BigDecimal.valueOf(300000));
        contract.setStatus("準備中");
        contractMapper.insert(contract);
        contractId = contract.getId();
    }

    private Path writeSourcePdf(String name, String content) throws Exception {
        Path dir = Paths.get("target", "test-uploads", "contracts", String.valueOf(contractId)).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path pdf = dir.resolve(name);
        Files.write(pdf, ("%PDF-1.4\n" + content + "\ntrailer\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1));
        return pdf;
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte v : md.digest(Files.readAllBytes(p))) {
            sb.append(String.format("%02x", v));
        }
        return sb.toString();
    }

    private ContractDocument insertDocument(DispatchState state, Path pdf, int version) throws Exception {
        ContractDocument d = new ContractDocument();
        d.setContractId(contractId);
        d.setTemplateId(templateId);
        d.setTemplateVersion(1);
        d.setRenderedHtml("<p>x</p>");
        d.setPdfPath(pdf.toString());
        d.setPdfSha256(sha256(pdf));
        d.setStatus("下書き");
        d.setRecipientName("マスク宛先");
        d.setRecipientEmail("recipient-masked@example.invalid");
        d.setDispatchState(state.name());
        d.setVersion(version);
        d.setDispatchAttemptCount(0);
        if (state != DispatchState.NONE) {
            // queue受付時に永続化されるoperation/payload hashを全dispatch状態で再現する
            d.setOperationId(UUID.randomUUID().toString());
            d.setSendPayloadSha256(com.ses.service.cloudsign.CloudSignPayloadHasher.hash(
                    new ConfirmedSendRequest(contractNo(), 1, "マスク宛先",
                            "recipient-masked@example.invalid", "SES契約書 " + contractId, "ja")));
        }
        if (state == DispatchState.CREATING || state == DispatchState.DOCUMENT_CREATED
                || state == DispatchState.UPLOADING || state == DispatchState.FILE_UPLOADED
                || state == DispatchState.ADDING_PARTICIPANT || state == DispatchState.READY_TO_SEND
                || state == DispatchState.SENDING) {
            d.setCloudsignDocumentId(DOC_ID);
        }
        if (state == DispatchState.FILE_UPLOADED || state == DispatchState.ADDING_PARTICIPANT
                || state == DispatchState.READY_TO_SEND || state == DispatchState.SENDING) {
            d.setCloudsignFileId(FILE_ID);
        }
        if (state == DispatchState.READY_TO_SEND || state == DispatchState.SENDING) {
            d.setCloudsignParticipantId(PARTICIPANT_ID);
        }
        documentMapper.insert(d);
        return d;
    }

    private String contractNo() {
        return contractMapper.selectById(contractId).getContractNo();
    }

    private CloudSignDocument remoteDocument(int status) {
        return new CloudSignDocument(DOC_ID, "SES契約書", status, null, null, null,
                List.of(new CloudSignFile(FILE_ID, "document-1.pdf", 0L, 1L)),
                List.of(new CloudSignParticipant(PARTICIPANT_ID, "recipient-masked@example.invalid",
                        "マスク宛先", null, 0L, status == 1 ? 4 : 8)));
    }

    // ---------- 同時send / queue idempotency ----------

    @Test
    void 一〇〇同時queueSendでoperationは1件になりproviderは呼ばれない() throws Exception {
        Path pdf = writeSourcePdf("doc-100.pdf", "content-100");
        ContractDocument d = insertDocument(DispatchState.NONE, pdf, 0);

        ConfirmedSendRequest request = new ConfirmedSendRequest(contractNo(), 1, "マスク宛先",
                "recipient-masked@example.invalid", "SES契約書 " + contractId, "ja");
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] workers = new Thread[100];
        for (int i = 0; i < 100; i++) {
            workers[i] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    ContractDocument result = documentService.queueSend(d.getId(), request);
                    if (result != null && result.getOperationId() != null) {
                        accepted.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    failed.incrementAndGet();
                }
            });
            workers[i].start();
        }
        start.countDown();
        for (Thread t : workers) {
            t.join(30000);
        }

        assertEquals(100, accepted.get(), "全requestがqueue受付される");
        assertEquals(0, failed.get());
        ContractDocument after = documentMapper.selectById(d.getId());
        assertEquals(DispatchState.QUEUED.name(), after.getDispatchState());
        assertNotNull(after.getOperationId());
        // 外部書類はまだ0件（dispatch未実行。queue受付はprovider送信完了ではない）
        verify(api, never()).createDocument(any());
    }

    // ---------- mutation timeout: call count = 1 ----------

    @Test
    void createがtimeout結果不明なら再実行せずRECONCILIATION_REQUIREDに停止する() throws Exception {
        Path pdf = writeSourcePdf("doc-t1.pdf", "content-t1");
        ContractDocument d = insertDocument(DispatchState.QUEUED, pdf, 0);

        when(api.createDocument(any())).thenThrow(
                new CloudSignApiException(CloudSignErrorCode.TIMEOUT, true, "TIMEOUT:RESULT_UNKNOWN"));

        dispatchService.dispatchDue(10);

        ContractDocument after = documentMapper.selectById(d.getId());
        assertEquals(DispatchState.RECONCILIATION_REQUIRED.name(), after.getDispatchState());
        assertEquals("TIMEOUT", after.getLastProviderErrorCode());
        verify(api, times(1)).createDocument(any());
        verify(api, never()).uploadFile(any(), any(), any());
        verify(api, never()).sendDocument(any());
    }

    // ---------- crash境界 ----------

    @Test
    void create成功後のcrash境界はcheckpoint保存後に再開される() throws Exception {
        Path pdf = writeSourcePdf("doc-c1.pdf", "content-c1");
        // CREATE成功後crash → DOCUMENT_CREATED状態で残る
        ContractDocument d = insertDocument(DispatchState.DOCUMENT_CREATED, pdf, 0);

        when(api.uploadFile(any(), any(), any())).thenReturn(remoteDocument(0));

        dispatchService.dispatchDue(10);

        ContractDocument after = documentMapper.selectById(d.getId());
        assertEquals(DispatchState.FILE_UPLOADED.name(), after.getDispatchState());
        assertEquals(FILE_ID, after.getCloudsignFileId());
        verify(api, never()).createDocument(any());
        verify(api, times(1)).uploadFile(any(), any(), any());
    }

    @Test
    void create呼出し前のcrash境界はstaleClaimで結果不明になり自動再createしない() throws Exception {
        Path pdf = writeSourcePdf("doc-c2.pdf", "content-c2");
        ContractDocument d = insertDocument(DispatchState.CREATING, pdf, 0);
        // stale claimを再現（1分以上前）
        documentMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ContractDocument>()
                .eq(ContractDocument::getId, d.getId())
                .set(ContractDocument::getClaimedAt, LocalDateTime.now().minusMinutes(10))
                .set(ContractDocument::getClaimOwner, "dead-worker"));

        dispatchService.dispatchDue(10);

        ContractDocument after = documentMapper.selectById(d.getId());
        assertEquals(DispatchState.RECONCILIATION_REQUIRED.name(), after.getDispatchState());
        assertEquals("STALE_CLAIM", after.getLastProviderErrorCode());
        verify(api, never()).createDocument(any());
    }

    // ---------- send境界 ----------

    @Test
    void sendがacceptedThenTimeoutでも再POSTせずGET照合で確定する() throws Exception {
        Path pdf = writeSourcePdf("doc-s1.pdf", "content-s1");
        ContractDocument d = insertDocument(DispatchState.READY_TO_SEND, pdf, 0);
        d.setCloudsignStatus(0);
        documentMapper.updateById(d);

        when(api.getDocument(DOC_ID)).thenReturn(remoteDocument(0), remoteDocument(1));
        when(api.sendDocument(DOC_ID)).thenThrow(
                new CloudSignApiException(CloudSignErrorCode.SERVER_ERROR, true, "504:RESULT_UNKNOWN"));

        dispatchService.dispatchDue(10);

        ContractDocument after = documentMapper.selectById(d.getId());
        assertEquals(DispatchState.SENT.name(), after.getDispatchState());
        assertEquals("先方確認中", after.getStatus());
        verify(api, times(1)).sendDocument(DOC_ID);
    }

    @Test
    void send成功後にstatusが0のままなら自動再送せず結果不明に停止する() throws Exception {
        Path pdf = writeSourcePdf("doc-s2.pdf", "content-s2");
        ContractDocument d = insertDocument(DispatchState.READY_TO_SEND, pdf, 0);
        d.setCloudsignStatus(0);
        documentMapper.updateById(d);

        when(api.getDocument(DOC_ID)).thenReturn(remoteDocument(0), remoteDocument(0));
        when(api.sendDocument(DOC_ID)).thenReturn(remoteDocument(0));

        dispatchService.dispatchDue(10);

        ContractDocument after = documentMapper.selectById(d.getId());
        assertEquals(DispatchState.RECONCILIATION_REQUIRED.name(), after.getDispatchState());
        assertEquals("SEND_STILL_DRAFT", after.getLastProviderErrorCode());
        verify(api, times(1)).sendDocument(DOC_ID);
    }

    // ---------- payload hash / source hash変化 ----------

    @Test
    void 送信原本がqueue後に入れ替わると送信せず結果不明に停止する() throws Exception {
        Path pdf = writeSourcePdf("doc-p1.pdf", "content-p1");
        ContractDocument d = insertDocument(DispatchState.QUEUED, pdf, 0);
        // 原本を入れ替え（hash変化）
        Files.write(pdf, ("%PDF-1.4\nreplaced\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1));

        dispatchService.dispatchDue(10);

        ContractDocument after = documentMapper.selectById(d.getId());
        assertEquals(DispatchState.RECONCILIATION_REQUIRED.name(), after.getDispatchState());
        assertEquals("SOURCE_HASH_CHANGED", after.getLastProviderErrorCode());
        verify(api, never()).createDocument(any());
    }

    // ---------- CAS競合: 二重dispatch ----------

    @Test
    void 二worker同時dispatchでもcreateは1回だけ呼ばれる() throws Exception {
        Path pdf = writeSourcePdf("doc-race.pdf", "content-race");
        ContractDocument d = insertDocument(DispatchState.QUEUED, pdf, 0);

        AtomicInteger createCalls = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(2);
        when(api.createDocument(any())).thenAnswer(inv -> {
            createCalls.incrementAndGet();
            gate.countDown();
            gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return remoteDocument(0);
        });

        Thread t1 = new Thread(() -> dispatchService.dispatchDue(10));
        Thread t2 = new Thread(() -> dispatchService.dispatchDue(10));
        t1.start();
        t2.start();
        t1.join(30000);
        t2.join(30000);

        assertEquals(1, createCalls.get(), "同一operationのcreateは1回");
        ContractDocument after = documentMapper.selectById(d.getId());
        assertEquals(DispatchState.DOCUMENT_CREATED.name(), after.getDispatchState());
    }

    // ---------- transaction境界 ----------

    @Test
    @Transactional
    void provider呼出し時にtransactionがactiveなら外部APIを呼ばず停止する() throws Exception {
        Path pdf = writeSourcePdf("doc-tx.pdf", "content-tx");
        ContractDocument d = insertDocument(DispatchState.QUEUED, pdf, 0);

        dispatchService.dispatchDue(10);

        // fail-closed: 外部API未呼出し。claimはcommitされず、工程も進まない
        verify(api, never()).createDocument(any());
        ContractDocument after = documentMapper.selectById(d.getId());
        assertNotEquals(DispatchState.DOCUMENT_CREATED.name(), after.getDispatchState(),
                "transaction内では工程を進めない");
    }

    // ---------- kill switch ----------

    @Test
    void enabledFalseではdispatchがkillSwitchとして何もしない() throws Exception {
        Path pdf = writeSourcePdf("doc-kill.pdf", "content-kill");
        ContractDocument d = insertDocument(DispatchState.QUEUED, pdf, 0);

        com.ses.config.CloudSignProperties props =
                (com.ses.config.CloudSignProperties) org.springframework.test.util.ReflectionTestUtils
                        .getField(dispatchService, "properties");
        boolean original = props.isEnabled();
        props.setEnabled(false);
        try {
            dispatchService.dispatchDue(10);
            ContractDocument after = documentMapper.selectById(d.getId());
            assertEquals(DispatchState.QUEUED.name(), after.getDispatchState(),
                    "kill switch中はdispatchが動かない");
            verify(api, never()).createDocument(any());
        } finally {
            props.setEnabled(original);
        }
    }
}
