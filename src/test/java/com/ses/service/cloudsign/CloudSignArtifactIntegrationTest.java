package com.ses.service.cloudsign;

import com.ses.common.enums.DispatchState;
import com.ses.common.enums.FileKind;
import com.ses.dto.cloudsign.PdfDownload;
import com.ses.entity.ContractDocument;
import com.ses.mapper.ContractDocumentMapper;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * HFP-02-06: 締結済みPDF・証明書の安全回収と三hashの統合test（実H2 + stub provider/scanner）。
 * quarantine → 検証 → CONTRACT_PDF scan → SHA-256 → 文書台帳(atomic) → 別archive/別hash保存を検証する。
 */
@SpringBootTest(properties = {
        "cloudsign.enabled=true",
        "cloudsign.environment=SANDBOX",
        "cloudsign.client-id=test-client-id",
        "cloudsign.dispatch-cron=-",
        "cloudsign.poll-cron=-",
        "cloudsign.legacy-read-base-path=./target/test-uploads"
})
@ActiveProfiles("test")
@Sql("/sql/engineer-schema-h2.sql")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CloudSignArtifactIntegrationTest {

    private static final String DOC_ID = "0123456789abcdef0123456789abcdef01";
    private static final String FILE_ID = "abcdef0123456789abcdef012345678901";

    @Autowired
    private CloudSignArtifactService artifactService;

    @Autowired
    private ContractDocumentMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private CloudSignApiClient api;

    @MockBean
    private FileScanner fileScanner;

    @BeforeEach
    void clean() {
        mapper.delete(null);
        jdbcTemplate.execute("DELETE FROM t_document_version");
        jdbcTemplate.execute("DELETE FROM t_document_link");
        jdbcTemplate.execute("DELETE FROM t_document");
        when(fileScanner.scan(any(), any())).thenReturn(FileScanResult.clean("clean"));
    }

    private ContractDocument insertCompleted(String signedPath, String certPath) {
        ContractDocument d = new ContractDocument();
        d.setContractId(1L);
        d.setTemplateId(1L);
        d.setTemplateVersion(1);
        d.setRenderedHtml("<p>x</p>");
        d.setRecipientName("マスク宛先");
        d.setRecipientEmail("art-masked@example.invalid");
        d.setStatus("締結済");
        d.setCloudsignDocumentId(DOC_ID);
        d.setCloudsignFileId(FILE_ID);
        d.setDispatchState(DispatchState.COMPLETED.name());
        d.setCloudsignStatus(2);
        d.setCompletedAt(LocalDateTime.now());
        d.setVersion(0);
        d.setDispatchAttemptCount(0);
        d.setOperationId(UUID.randomUUID().toString());
        d.setPdfSha256("s".repeat(64));
        d.setSignedPdfPath(signedPath);
        d.setCertificatePath(certPath);
        mapper.insert(d);
        return d;
    }

    private PdfDownload pdfDownload(String content) throws Exception {
        Path temp = Files.createTempFile("cloudsign-test-", ".pdf");
        Files.write(temp, ("%PDF-1.4\n" + content + "\ntrailer\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1));
        return new PdfDownload(temp, Files.size(temp), "application/pdf");
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte v : md.digest(data)) {
            sb.append(String.format("%02x", v));
        }
        return sb.toString();
    }

    @Test
    void 締結済行のsignedとcertificateを別hash別archiveで保存しsourceHashは不変() throws Exception {
        ContractDocument d = insertCompleted(null, null);
        PdfDownload signed = pdfDownload("signed-1");
        PdfDownload cert = pdfDownload("cert-1");
        when(api.downloadFile(DOC_ID, FILE_ID)).thenReturn(signed);
        when(api.downloadCertificate(DOC_ID)).thenReturn(cert);

        artifactService.collectPending(10);

        ContractDocument after = mapper.selectById(d.getId());
        assertNotNull(after.getSignedArchiveDocumentId(), "signedは文書台帳に登録される");
        assertNotNull(after.getCertificateArchiveDocumentId(), "certificateは文書台帳に登録される");
        assertNotEquals(after.getSignedPdfSha256(), after.getCertificateSha256(),
                "signed/certificateは別hash");
        assertEquals("s".repeat(64), after.getPdfSha256(), "送信原本hashは不変");
        // 台帳に2件（SIGNED_PDF / ESIGN_CERT）、contentTypeはapplication/pdf
        Integer signedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_document d JOIN t_document_version v ON v.document_id=d.id "
                        + "WHERE d.document_type='SIGNED_PDF' AND v.content_type='application/pdf'",
                Integer.class);
        Integer certCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_document d JOIN t_document_version v ON v.document_id=d.id "
                        + "WHERE d.document_type='ESIGN_CERT' AND v.content_type='application/pdf'",
                Integer.class);
        assertEquals(1, signedCount);
        assertEquals(1, certCount);
    }

    @Test
    void 同一artifactの再取得は同一hashならnoOpで二重登録しない() throws Exception {
        ContractDocument d = insertCompleted(null, null);
        PdfDownload signed = pdfDownload("signed-2");
        when(api.downloadFile(DOC_ID, FILE_ID)).thenReturn(signed);

        artifactService.collectPending(10);
        ContractDocument once = mapper.selectById(d.getId());
        assertNotNull(once.getSignedArchiveDocumentId());

        // 再実行: archive idがあるためdownloadも再登録もされない
        artifactService.collectPending(10);
        verify(api, times(1)).downloadFile(DOC_ID, FILE_ID);
        Integer ledgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_document WHERE document_type='SIGNED_PDF'", Integer.class);
        assertEquals(1, ledgerCount, "同一artifactの二重登録をしない");
    }

    @Test
    void 相違hashは既存版を上書きせずfindingにする() throws Exception {
        ContractDocument d = insertCompleted(null, null);
        // 部分状態: hashのみ存在しarchive未登録（移行途中のcrash相当）
        d.setSignedPdfSha256("d".repeat(64));
        d.setCertificateSha256("e".repeat(64));
        mapper.updateById(d);
        PdfDownload different = pdfDownload("different-content");
        when(api.downloadFile(DOC_ID, FILE_ID)).thenReturn(different);
        when(api.downloadCertificate(DOC_ID)).thenReturn(pdfDownload("cert-different"));

        artifactService.collectPending(10);

        ContractDocument after = mapper.selectById(d.getId());
        assertEquals("d".repeat(64), after.getSignedPdfSha256(), "旧hashを保持");
        assertNull(after.getSignedArchiveDocumentId(), "上書きせず停止");
        assertTrue(after.getLastProviderErrorCode().startsWith("ARTIFACT_HASH_CHANGED"));
    }

    @Test
    void scannerUNAVAILABLEはquarantineのまま公開しない() throws Exception {
        ContractDocument d = insertCompleted(null, null);
        when(api.downloadFile(DOC_ID, FILE_ID)).thenReturn(pdfDownload("signed-3"));
        when(api.downloadCertificate(DOC_ID)).thenReturn(pdfDownload("cert-3"));
        when(fileScanner.scan(any(), eq(FileKind.CONTRACT_PDF)))
                .thenReturn(FileScanResult.unavailable("scanner down"));

        artifactService.collectPending(10);

        ContractDocument after = mapper.selectById(d.getId());
        assertNull(after.getSignedArchiveDocumentId(), "scanner不在なら公開しない");
        assertTrue(after.getLastProviderErrorCode().contains("SCAN_REJECTED:UNAVAILABLE"),
                "scanner不在は公開しない: " + after.getLastProviderErrorCode());
    }

    @Test
    void scannerINFECTEDはquarantineのまま公開しない() throws Exception {
        ContractDocument d = insertCompleted(null, null);
        when(api.downloadFile(DOC_ID, FILE_ID)).thenReturn(pdfDownload("signed-4"));
        when(api.downloadCertificate(DOC_ID)).thenReturn(pdfDownload("cert-4"));
        when(fileScanner.scan(any(), eq(FileKind.CONTRACT_PDF)))
                .thenReturn(new FileScanResult(FileScanResult.Status.INFECTED, "eicar", "virus"));

        artifactService.collectPending(10);

        ContractDocument after = mapper.selectById(d.getId());
        assertNull(after.getSignedArchiveDocumentId());
        assertTrue(after.getLastProviderErrorCode().contains("SCAN_REJECTED:INFECTED"),
                "感染は公開しない: " + after.getLastProviderErrorCode());
    }

    @Test
    void PDFでないbodyはMAGIC_EOFで拒否する() throws Exception {
        ContractDocument d = insertCompleted(null, null);
        Path temp = Files.createTempFile("cloudsign-bad-", ".bin");
        Files.write(temp, "not a pdf".getBytes());
        Path tempCert = Files.createTempFile("cloudsign-bad2-", ".bin");
        Files.write(tempCert, "not a pdf either".getBytes());
        when(api.downloadFile(DOC_ID, FILE_ID))
                .thenReturn(new PdfDownload(temp, Files.size(temp), "application/pdf"));
        when(api.downloadCertificate(anyString()))
                .thenReturn(new PdfDownload(tempCert, Files.size(tempCert), "application/pdf"));

        artifactService.collectPending(10);

        ContractDocument after = mapper.selectById(d.getId());
        assertNull(after.getSignedArchiveDocumentId());
        assertTrue(after.getLastProviderErrorCode().contains("INVALID:MAGIC_EOF"),
                "magic/EOF不適合を拒否する: " + after.getLastProviderErrorCode());
    }

    @Test
    void content型がPDFでない場合は拒否する() throws Exception {
        ContractDocument d = insertCompleted(null, null);
        Path temp = Files.createTempFile("cloudsign-ct-", ".pdf");
        Files.write(temp, ("%PDF-1.4\nx\ntrailer\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1));
        Path tempCert = Files.createTempFile("cloudsign-ct2-", ".pdf");
        Files.write(tempCert, ("%PDF-1.4\ny\ntrailer\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1));
        when(api.downloadFile(anyString(), anyString()))
                .thenReturn(new PdfDownload(temp, Files.size(temp), "application/octet-stream"));
        when(api.downloadCertificate(anyString()))
                .thenReturn(new PdfDownload(tempCert, Files.size(tempCert), "application/octet-stream"));

        artifactService.collectPending(10);

        ContractDocument after = mapper.selectById(d.getId());
        assertNull(after.getSignedArchiveDocumentId());
        assertNull(after.getCertificateArchiveDocumentId());
        assertTrue(after.getLastProviderErrorCode().contains("INVALID:CONTENT_TYPE"),
                "content-type不適合を拒否する: " + after.getLastProviderErrorCode());
    }

    @Test
    void 送信時fileIDが無い場合は回収しない() throws Exception {
        ContractDocument d = insertCompleted(null, null);
        // update-strategy not_nullのため、明示UpdateWrapperでNULL化する
        mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ContractDocument>()
                .eq(ContractDocument::getId, d.getId())
                .set(ContractDocument::getCloudsignFileId, null));

        artifactService.collectPending(10);

        ContractDocument after = mapper.selectById(d.getId());
        assertNull(after.getSignedArchiveDocumentId());
        verify(api, never()).downloadFile(any(), any());
    }

    @Test
    void legacyローカルpathはprovider再取得せず台帳へ移行する() throws Exception {
        Path dir = Files.createTempDirectory("legacy-art");
        Path signed = dir.resolve("signed-legacy.pdf");
        Files.write(signed, ("%PDF-1.4\nlegacy-signed\ntrailer\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1));
        ContractDocument d = insertCompleted(signed.toString(), null);
        d.setSignedPdfSha256(sha256(Files.readAllBytes(signed)));
        mapper.updateById(d);

        artifactService.collectPending(10);

        ContractDocument after = mapper.selectById(d.getId());
        assertNotNull(after.getSignedArchiveDocumentId(), "ローカル移行で台帳登録される");
        assertEquals(d.getSignedPdfSha256(), after.getSignedPdfSha256());
        verify(api, never()).downloadFile(any(), any());
    }

    @Test
    void downloadは文書台帳経由でsignedとcertificateを別名で返す() throws Exception {
        ContractDocument d = insertCompleted(null, null);
        when(api.downloadFile(DOC_ID, FILE_ID)).thenReturn(pdfDownload("signed-5"));
        when(api.downloadCertificate(DOC_ID)).thenReturn(pdfDownload("cert-5"));

        artifactService.collectPending(10);
        ContractDocument after = mapper.selectById(d.getId());

        CloudSignArtifactService.ArtifactDownload signed = artifactService.downloadSigned(after);
        assertEquals("signed-" + d.getId() + ".pdf", signed.fileName());
        assertEquals("application/pdf", signed.contentType());
        assertEquals("%PDF-1.4\nsigned-5\ntrailer\n%%EOF\n",
                new String(signed.stream().readAllBytes(), StandardCharsets.ISO_8859_1));

        CloudSignArtifactService.ArtifactDownload cert = artifactService.downloadCertificate(after);
        assertEquals("certificate-" + d.getId() + ".pdf", cert.fileName());
        assertEquals("application/pdf", cert.contentType());
        assertEquals("%PDF-1.4\ncert-5\ntrailer\n%%EOF\n",
                new String(cert.stream().readAllBytes(), StandardCharsets.ISO_8859_1));
    }

    @Test
    void scanはCONTRACT_PDF種別で実行される() throws Exception {
        ContractDocument d = insertCompleted(null, null);
        when(api.downloadFile(DOC_ID, FILE_ID)).thenReturn(pdfDownload("signed-6"));
        when(api.downloadCertificate(DOC_ID)).thenReturn(pdfDownload("cert-6"));

        artifactService.collectPending(10);

        verify(fileScanner, atLeast(2)).scan(any(), eq(FileKind.CONTRACT_PDF));
    }
}
