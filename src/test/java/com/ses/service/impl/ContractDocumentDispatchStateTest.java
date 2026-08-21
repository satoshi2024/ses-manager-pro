package com.ses.service.impl;

import com.ses.common.enums.DispatchState;
import com.ses.config.ContractDocumentDispatchBackfill;
import com.ses.entity.ContractDocument;
import com.ses.mapper.ContractDocumentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HFP-02-02: V103の新列・状態CAS・legacy backfill分類を実H2(MySQL mode)で検証する。
 * 投入した行は本テスト自身が挿入したものだけを使う（共有H2規約）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ContractDocumentDispatchStateTest {

    @Autowired
    private ContractDocumentMapper mapper;

    @Autowired
    private ContractDocumentDispatchBackfill backfill;

    private ContractDocument insertRow(String marker, String status, String externalId, String signedPath) {
        ContractDocument d = new ContractDocument();
        d.setContractId(1L);
        d.setTemplateId(1L);
        d.setTemplateVersion(1);
        d.setRenderedHtml("<p>" + marker + "</p>");
        d.setRecipientName("マスク宛先");
        d.setRecipientEmail(marker + "@example.invalid");
        d.setStatus(status);
        d.setCloudsignDocumentId(externalId);
        d.setSignedPdfPath(signedPath);
        d.setDispatchState(DispatchState.NONE.name());
        d.setVersion(0);
        d.setDispatchAttemptCount(0);
        mapper.insert(d);
        return d;
    }

    @Test
    void 状態CASはversionとstateの両方が一致したときだけ遷移する() {
        ContractDocument d = insertRow("cas-" + System.nanoTime(), "下書き", null, null);

        // version不一致 → 0件
        assertEquals(0, mapper.casTransition(d.getId(), 99, DispatchState.NONE.name(),
                DispatchState.QUEUED.name()));
        // state不一致 → 0件
        assertEquals(0, mapper.casTransition(d.getId(), 0, DispatchState.QUEUED.name(),
                DispatchState.CREATING.name()));
        // 一致 → 1件、versionが進む
        assertEquals(1, mapper.casTransition(d.getId(), 0, DispatchState.NONE.name(),
                DispatchState.QUEUED.name()));
        ContractDocument after = mapper.selectById(d.getId());
        assertEquals(DispatchState.QUEUED.name(), after.getDispatchState());
        assertEquals(1, after.getVersion());

        // 二重遷移はCASで拒否（二重クリック/二重worker対策）
        assertEquals(0, mapper.casTransition(d.getId(), 0, DispatchState.NONE.name(),
                DispatchState.QUEUED.name()));
    }

    @Test
    void workerClaimCASは一度だけ成功し他workerはclaimできない() {
        ContractDocument d = insertRow("claim-" + System.nanoTime(), "下書き", null, null);
        LocalDateTime now = LocalDateTime.now();

        assertEquals(1, mapper.casClaim(d.getId(), 0, DispatchState.NONE.name(),
                DispatchState.CREATING.name(), now, "worker-a", now));
        // staleな元version(0)を期待するworker-bの再claimは0件（version CASで排除）
        assertEquals(0, mapper.casClaim(d.getId(), 0, DispatchState.NONE.name(),
                DispatchState.CREATING.name(), now, "worker-b", now));

        ContractDocument after = mapper.selectById(d.getId());
        assertEquals("worker-a", after.getClaimOwner());
        // Round 2 REV-004: claimはretry回数(dispatch_attempt_count)を増やさない
        assertEquals(0, after.getDispatchAttemptCount());
    }

    @Test
    void retryWaitはretry回数を増やし次回試行時刻を設定する() {
        ContractDocument d = insertRow("retry-" + System.nanoTime(), "下書き", null, null);
        LocalDateTime next = LocalDateTime.now().plusMinutes(1);

        assertEquals(1, mapper.casRetryWait(d.getId(), 0, DispatchState.NONE.name(),
                DispatchState.QUEUED.name(), "TRANSIENT:SERVER_ERROR", next));

        ContractDocument after = mapper.selectById(d.getId());
        assertEquals(1, after.getDispatchAttemptCount(), "retry回数を1増やす");
        assertNotNull(after.getNextAttemptAt(), "次回試行時刻を設定する");
        assertEquals("TRANSIENT:SERVER_ERROR", after.getLastProviderErrorCode());
    }

    @Test
    void checkpointCASは外部IDと次工程を一度に保存する() {
        ContractDocument d = insertRow("cp-" + System.nanoTime(), "下書き", null, null);

        assertEquals(1, mapper.casCheckpoint(d.getId(), 0, DispatchState.NONE.name(),
                DispatchState.DOCUMENT_CREATED.name(), "0123456789abcdef0123456789abcdef01",
                null, null, 0));
        assertEquals(0, mapper.casCheckpoint(d.getId(), 0, DispatchState.NONE.name(),
                DispatchState.UPLOADING.name(), "dup-doc", null, null, 0));

        ContractDocument after = mapper.selectById(d.getId());
        assertEquals("0123456789abcdef0123456789abcdef01", after.getCloudsignDocumentId());
        assertEquals(DispatchState.DOCUMENT_CREATED.name(), after.getDispatchState());
        assertEquals(1, after.getVersion());
    }

    @Test
    void workerClaimCASはclaimed_atがある行を再claimできない() {
        ContractDocument d = insertRow("claim2-" + System.nanoTime(), "下書き", null, null);
        LocalDateTime now = LocalDateTime.now();

        assertEquals(1, mapper.casClaim(d.getId(), 0, DispatchState.NONE.name(),
                DispatchState.CREATING.name(), now, "worker-a", now));
        ContractDocument claimed = mapper.selectById(d.getId());
        // claimed_at IS NULL 条件により、同一versionでも再claim不可（HFP-02-BUG-01）
        assertEquals(0, mapper.casClaim(d.getId(), claimed.getVersion(), DispatchState.CREATING.name(),
                DispatchState.CREATING.name(), now, "worker-b", now));
    }

    @Test
    void backfillは外部IDなし締結済を要確認に分類する() {
        ContractDocument d = insertRow("bf-local-" + System.nanoTime(), "締結済", null, null);
        backfill.run(mockArgs());
        ContractDocument after = mapper.selectById(d.getId());
        assertEquals(DispatchState.RECONCILIATION_REQUIRED.name(), after.getDispatchState());
        assertEquals("BACKFILL_LOCAL_TERMINAL", after.getLastProviderErrorCode());
    }

    @Test
    void backfillは外部IDなし下書きを変更しない() {
        ContractDocument d = insertRow("bf-none-" + System.nanoTime(), "下書き", null, null);
        backfill.run(mockArgs());
        assertEquals(DispatchState.NONE.name(), mapper.selectById(d.getId()).getDispatchState());
    }

    @Test
    void backfillは確認中legacy行を要確認に分類し自動再送しない() {
        ContractDocument d = insertRow("bf-conf-" + System.nanoTime(), "先方確認中",
                "0123456789abcdef0123456789abcdef02", null);
        backfill.run(mockArgs());
        ContractDocument after = mapper.selectById(d.getId());
        assertEquals(DispatchState.RECONCILIATION_REQUIRED.name(), after.getDispatchState());
        assertEquals("BACKFILL_LEGACY_CONFIRMING", after.getLastProviderErrorCode());
    }

    @Test
    void backfillは締結済legacy行のartifactHashを再計算し移行候補としてCOMPLETEDにする() throws Exception {
        Path dir = Files.createTempDirectory("bf-completed");
        Path signed = dir.resolve("signed.pdf");
        Files.write(signed, "legacy signed pdf".getBytes());
        Path cert = dir.resolve("cert.pdf");
        Files.write(cert, "legacy certificate pdf".getBytes());

        ContractDocument d = insertRow("bf-done-" + System.nanoTime(), "締結済",
                "0123456789abcdef0123456789abcdef03", signed.toString());
        d.setCertificatePath(cert.toString());
        d.setCloudsignFileId("f-legacy");
        mapper.updateById(d);

        backfill.run(mockArgs());

        ContractDocument after = mapper.selectById(d.getId());
        assertEquals(DispatchState.COMPLETED.name(), after.getDispatchState());
        assertEquals(sha256Of(signed), after.getSignedPdfSha256());
        assertEquals(sha256Of(cert), after.getCertificateSha256());
        assertNull(after.getSignedArchiveDocumentId(), "archive移行はHFP-02-06の候補のまま");
        assertEquals("0123456789abcdef0123456789abcdef03", after.getCloudsignDocumentId(),
                "既存外部IDを保持");
    }

    @Test
    void backfillは矛盾形状を要確認に分類して停止する() {
        // 外部ID有りで下書き（矛盾）
        ContractDocument d1 = insertRow("bf-bad1-" + System.nanoTime(), "下書き",
                "0123456789abcdef0123456789abcdef04", null);
        // 締結済でsigned pathなし（矛盾）
        ContractDocument d2 = insertRow("bf-bad2-" + System.nanoTime(), "締結済",
                "0123456789abcdef0123456789abcdef05", null);

        backfill.run(mockArgs());

        assertEquals(DispatchState.RECONCILIATION_REQUIRED.name(),
                mapper.selectById(d1.getId()).getDispatchState());
        assertEquals("BACKFILL_CONTRADICTION", mapper.selectById(d1.getId()).getLastProviderErrorCode());
        assertEquals(DispatchState.RECONCILIATION_REQUIRED.name(),
                mapper.selectById(d2.getId()).getDispatchState());
        assertEquals("BACKFILL_CONTRADICTION", mapper.selectById(d2.getId()).getLastProviderErrorCode());
    }

    @Test
    void backfillは分類済みの行に触れない() {
        ContractDocument d = insertRow("bf-ido-" + System.nanoTime(), "下書き", null, null);
        mapper.casTransition(d.getId(), 0, DispatchState.NONE.name(), DispatchState.QUEUED.name());
        ContractDocument q = mapper.selectById(d.getId());
        q.setStatus("先方確認中");
        mapper.updateById(q);

        backfill.run(mockArgs());

        // QUEUEDのまま（backfillがNONE以外へ触れない）
        assertEquals(DispatchState.QUEUED.name(), mapper.selectById(d.getId()).getDispatchState());
    }

    private static org.springframework.boot.ApplicationArguments mockArgs() {
        return new org.springframework.boot.DefaultApplicationArguments(new String[0]);
    }

    private static String sha256Of(Path p) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(Files.readAllBytes(p));
        StringBuilder sb = new StringBuilder();
        for (byte v : digest) {
            sb.append(String.format("%02x", v));
        }
        return sb.toString();
    }
}
