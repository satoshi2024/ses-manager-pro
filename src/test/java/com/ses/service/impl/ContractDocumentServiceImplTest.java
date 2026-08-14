package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Contract;
import com.ses.entity.ContractDocument;
import com.ses.entity.ContractTemplate;
import com.ses.entity.FileSecurityMetadata;
import com.ses.mapper.ContractDocumentMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.ContractTemplateMapper;
import com.ses.mapper.FileSecurityMetadataMapper;
import com.ses.service.CloudSignClient;
import com.ses.service.DocumentService;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ContractDocumentServiceImplTest {

    private ContractTemplateMapper templateMapper;
    private ContractMapper contractMapper;
    private CloudSignClient cloudSignClient;
    private com.ses.common.util.PdfFontUtils pdfFontUtils;
    private FileSecurityMetadataMapper metadataMapper;
    private FileScanner fileScanner;
    private ContractDocumentServiceImpl service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        templateMapper = mock(ContractTemplateMapper.class);
        contractMapper = mock(ContractMapper.class);
        cloudSignClient = mock(CloudSignClient.class);
        pdfFontUtils = mock(com.ses.common.util.PdfFontUtils.class);
        metadataMapper = mock(FileSecurityMetadataMapper.class);
        when(metadataMapper.insert(any(FileSecurityMetadata.class))).thenReturn(1);
        when(metadataMapper.updateById(any(FileSecurityMetadata.class))).thenReturn(1);
        fileScanner = mock(FileScanner.class);

        ObjectProvider<FileSecurityMetadataMapper> metadataMapperProvider = mock(ObjectProvider.class);
        when(metadataMapperProvider.getIfAvailable()).thenReturn(metadataMapper);

        ObjectProvider<FileScanner> fileScannerProvider = mock(ObjectProvider.class);
        when(fileScannerProvider.getIfAvailable()).thenReturn(fileScanner);

        ObjectProvider<DocumentService> documentServiceProvider = mock(ObjectProvider.class);

        ContractDocumentMapper baseMapper = mock(ContractDocumentMapper.class);

        service = new ContractDocumentServiceImpl(templateMapper, contractMapper, cloudSignClient,
                pdfFontUtils, metadataMapperProvider, fileScannerProvider, documentServiceProvider);
        ReflectionTestUtils.setField(service, "baseMapper", baseMapper);
        ReflectionTestUtils.setField(service, "uploadBase", tempDir.toString());

        try {
            com.lowagie.text.pdf.BaseFont font = com.lowagie.text.pdf.BaseFont.createFont(
                    com.lowagie.text.pdf.BaseFont.HELVETICA, com.lowagie.text.pdf.BaseFont.WINANSI, com.lowagie.text.pdf.BaseFont.NOT_EMBEDDED);
            when(pdfFontUtils.resolveCjkFont()).thenReturn(font);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void 自前生成PDFはスキャンをスキップしCLEANメタデータを記録する() throws Exception {
        ContractTemplate template = new ContractTemplate();
        template.setId(100L);
        template.setActiveFlag(1);
        template.setHtmlContent("<p>Contract for ${recipientName}</p>");
        template.setVersion(1);
        when(templateMapper.selectById(100L)).thenReturn(template);

        Contract contract = new Contract();
        contract.setId(1L);
        contract.setContractNo("C-2026-001");
        contract.setStartDate(LocalDate.now());
        when(contractMapper.selectById(1L)).thenReturn(contract);

        ContractDocument doc = service.create(1L, 100L, "山田太郎", "yamada@example.com");
        assertNotNull(doc);
        assertNotNull(doc.getPdfPath());

        // FileSecurityMetadataMapper に PUBLISHED / CLEAN で登録されたことを確認
        verify(metadataMapper).insert(argThat((FileSecurityMetadata m) ->
                "PUBLISHED".equals(m.getStorageState()) && "CLEAN".equals(m.getScanStatus())));
        verifyNoInteractions(fileScanner);
    }

    @Test
    void 外部からの署名PDF同期時にClean判定で登録されDownload可能になる() throws Exception {
        ContractDocument doc = new ContractDocument();
        doc.setId(10L);
        doc.setContractId(1L);
        doc.setCloudsignDocumentId("cs-doc-1");
        
        ContractDocumentMapper baseMapper = (ContractDocumentMapper) ReflectionTestUtils.getField(service, "baseMapper");
        when(baseMapper.selectById(10L)).thenReturn(doc);

        byte[] pdfBytes = "dummy pdf content".getBytes();
        CloudSignClient.Result result = new CloudSignClient.Result("cs-doc-1", "file-1", "完了", pdfBytes, null);
        when(cloudSignClient.status("cs-doc-1")).thenReturn(result);
        when(fileScanner.scan(any(), any())).thenReturn(FileScanResult.clean("clean"));

        service.sync(10L);

        verify(fileScanner).scan(any(), any());
        verify(metadataMapper).insert(argThat((FileSecurityMetadata m) ->
                "PUBLISHED".equals(m.getStorageState()) && "CLEAN".equals(m.getScanStatus())));
    }

    @Test
    void Metadataが未登録またはCleanでない場合はDownloadを拒否する() throws Exception {
        ContractDocument doc = new ContractDocument();
        doc.setId(10L);
        Path pdfFile = tempDir.resolve("contracts").resolve("10").resolve("signed-10.pdf");
        Files.createDirectories(pdfFile.getParent());
        Files.write(pdfFile, "pdf data".getBytes());
        doc.setSignedPdfPath(pdfFile.toString());

        ContractDocumentMapper baseMapper = (ContractDocumentMapper) ReflectionTestUtils.getField(service, "baseMapper");
        when(baseMapper.selectById(10L)).thenReturn(doc);

        // Metadataなし
        when(metadataMapper.selectByStoredName(eq("default"), any())).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.download(10L));

        // MetadataがQUARANTINED
        FileSecurityMetadata badMetadata = new FileSecurityMetadata();
        badMetadata.setStorageState("QUARANTINED");
        badMetadata.setScanStatus("INFECTED");
        when(metadataMapper.selectByStoredName(eq("default"), any())).thenReturn(badMetadata);
        assertThrows(BusinessException.class, () -> service.download(10L));
    }

    @Test
    void scannerBean不在時は外部署名PDF同期でscanRejectedとなりQUARANTINED登録される() throws Exception {
        ContractDocument doc = new ContractDocument();
        doc.setId(20L);
        doc.setContractId(1L);
        doc.setCloudsignDocumentId("cs-doc-20");

        ContractDocumentMapper baseMapper = (ContractDocumentMapper) ReflectionTestUtils.getField(service, "baseMapper");
        when(baseMapper.selectById(20L)).thenReturn(doc);

        // scannerProvider returns null (scanner disabled)
        ObjectProvider<FileScanner> nullScannerProvider = mock(ObjectProvider.class);
        when(nullScannerProvider.getIfAvailable()).thenReturn(null);
        ReflectionTestUtils.setField(service, "fileScannerProvider", nullScannerProvider);

        byte[] pdfBytes = "suspicious pdf content".getBytes();
        CloudSignClient.Result result = new CloudSignClient.Result("cs-doc-20", "file-20", "完了", pdfBytes, null);
        when(cloudSignClient.status("cs-doc-20")).thenReturn(result);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.sync(20L));
        assertEquals("error.file.scanRejected", ex.getMessageKey());

        verify(metadataMapper).insert(argThat((FileSecurityMetadata m) ->
                "QUARANTINED".equals(m.getStorageState()) && "UNAVAILABLE".equals(m.getScanStatus())));
    }

    @Test
    void backfillRunnerはscanner不在時にUNAVAILABLEかつQUARANTINEDでバックフィル登録する() throws Exception {
        Path contractDir = tempDir.resolve("contracts").resolve("99");
        Files.createDirectories(contractDir);
        Path pdfFile = contractDir.resolve("doc-99.pdf");
        Files.write(pdfFile, "pdf content".getBytes());

        FileSecurityMetadataMapper mapper = mock(FileSecurityMetadataMapper.class);
        when(mapper.selectByStoredName(eq("default"), any())).thenReturn(null);

        ObjectProvider<FileSecurityMetadataMapper> metaProv = mock(ObjectProvider.class);
        when(metaProv.getIfAvailable()).thenReturn(mapper);

        ObjectProvider<FileScanner> scanProv = mock(ObjectProvider.class);
        when(scanProv.getIfAvailable()).thenReturn(null);

        com.ses.config.ContractDocumentBackfillRunner runner =
                new com.ses.config.ContractDocumentBackfillRunner(metaProv, scanProv);
        ReflectionTestUtils.setField(runner, "uploadBase", tempDir.toString());

        runner.run(mock(org.springframework.boot.ApplicationArguments.class));

        verify(mapper).insert(argThat((FileSecurityMetadata m) ->
                "QUARANTINED".equals(m.getStorageState())
                        && "UNAVAILABLE".equals(m.getScanStatus())
                        && Long.valueOf(99L).equals(m.getOwnerId())));
    }

    // ===== HFP-02-01 characterization: 現行defectをredで再現する =====

    @Test
    void syncは締結済PDFのhashで送信原本pdfSha256を上書きする() throws Exception {
        ContractDocument doc = new ContractDocument();
        doc.setId(30L);
        doc.setContractId(1L);
        doc.setCloudsignDocumentId("cs-doc-30");
        doc.setPdfSha256("a".repeat(64));

        ContractDocumentMapper baseMapper = (ContractDocumentMapper) ReflectionTestUtils.getField(service, "baseMapper");
        when(baseMapper.selectById(30L)).thenReturn(doc);

        byte[] signedPdf = "signed pdf content".getBytes();
        CloudSignClient.Result result = new CloudSignClient.Result("cs-doc-30", "file-30", "完了", signedPdf, null);
        when(cloudSignClient.status("cs-doc-30")).thenReturn(result);
        when(fileScanner.scan(any(), any())).thenReturn(FileScanResult.clean("clean"));

        service.sync(30L);

        // red: 現行はpdfSha256をsigned hashで上書きするため、送信原本の同一性が証明不能になる
        assertEquals("a".repeat(64), doc.getPdfSha256(), "送信原本hash(pdfSha256)は不変でなければならない");
    }

    @Test
    void syncは証明書がnullでも締結完了として成功しartifact欠落を記録しない() throws Exception {
        ContractDocument doc = new ContractDocument();
        doc.setId(31L);
        doc.setContractId(1L);
        doc.setCloudsignDocumentId("cs-doc-31");
        doc.setPdfSha256("a".repeat(64));

        ContractDocumentMapper baseMapper = (ContractDocumentMapper) ReflectionTestUtils.getField(service, "baseMapper");
        when(baseMapper.selectById(31L)).thenReturn(doc);

        byte[] signedPdf = "signed pdf content".getBytes();
        CloudSignClient.Result result = new CloudSignClient.Result("cs-doc-31", "file-31", "完了", signedPdf, null);
        when(cloudSignClient.status("cs-doc-31")).thenReturn(result);
        when(fileScanner.scan(any(), any())).thenReturn(FileScanResult.clean("clean"));

        service.sync(31L);

        // red: 締結済みなのに証明書が取得できていないことを成功扱いせず、
        // artifact欠落がerrorMessage等で記録されるべきだが現行は無視する
        assertNotNull(doc.getErrorMessage(), "証明書未取得の状態を記録しなければならない");
    }

    @Test
    void syncは外部PDFのscanにFileKind_CONTRACT_PDFを使わない() throws Exception {
        ContractDocument doc = new ContractDocument();
        doc.setId(32L);
        doc.setContractId(1L);
        doc.setCloudsignDocumentId("cs-doc-32");

        ContractDocumentMapper baseMapper = (ContractDocumentMapper) ReflectionTestUtils.getField(service, "baseMapper");
        when(baseMapper.selectById(32L)).thenReturn(doc);

        byte[] signedPdf = "signed pdf content".getBytes();
        CloudSignClient.Result result = new CloudSignClient.Result("cs-doc-32", "file-32", "完了", signedPdf, null);
        when(cloudSignClient.status("cs-doc-32")).thenReturn(result);
        when(fileScanner.scan(any(), any())).thenReturn(FileScanResult.clean("clean"));

        service.sync(32L);

        // red: 現行はFileKind.SKILL_SHEETを誤用するため、PDF専用の許可種別(CONTRACT_PDF)でscanされるべき
        verify(fileScanner).scan(any(), argThat(k -> "CONTRACT_PDF".equals(k.name())));
    }

    @Test
    void syncはprovider呼出しを含むmethodにTransactionアノテーションを張らない() throws Exception {
        // red: 現行は@Transactionalのままprovider GET/downloadを呼ぶ（長時間transactionと外部副作用混在）
        var annotation = org.springframework.transaction.annotation.Transactional.class;
        var method = ContractDocumentServiceImpl.class.getMethod("sync", Long.class);
        assertNull(method.getAnnotation(annotation), "外部呼出しを含むsyncはtransaction外でなければならない");
    }

    @Test
    void syncは証明書をpdf拡張子とapplicationPdfで保存しない() throws Exception {
        ContractDocument doc = new ContractDocument();
        doc.setId(33L);
        doc.setContractId(1L);
        doc.setCloudsignDocumentId("cs-doc-33");

        ContractDocumentMapper baseMapper = (ContractDocumentMapper) ReflectionTestUtils.getField(service, "baseMapper");
        when(baseMapper.selectById(33L)).thenReturn(doc);

        byte[] signedPdf = "signed pdf content".getBytes();
        byte[] certPdf = "certificate pdf content".getBytes();
        CloudSignClient.Result result = new CloudSignClient.Result("cs-doc-33", "file-33", "完了", signedPdf, certPdf);
        when(cloudSignClient.status("cs-doc-33")).thenReturn(result);
        when(fileScanner.scan(any(), any())).thenReturn(FileScanResult.clean("clean"));

        service.sync(33L);

        // red: 現行はcertificate-33.dat として保存しPDF扱いしない
        assertTrue(doc.getCertificatePath().endsWith(".pdf"),
                "証明書はPDFとして保存されるべきだが現行は " + doc.getCertificatePath());
    }

    @Test
    void 二重sendは同じ書類でproviderを2回呼び重複リスクがある() throws Exception {
        ContractDocumentMapper baseMapper = (ContractDocumentMapper) ReflectionTestUtils.getField(service, "baseMapper");
        when(baseMapper.selectById(40L)).thenAnswer(inv -> {
            ContractDocument d = new ContractDocument();
            d.setId(40L);
            d.setStatus("下書き");
            return d;
        });

        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        when(cloudSignClient.send(any())).thenAnswer(inv -> {
            calls.incrementAndGet();
            barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return new CloudSignClient.Result("cs-doc-40", "f-40", "送信中", null, null);
        });

        Thread t1 = new Thread(() -> { try { service.send(40L); } catch (Exception ignored) { } });
        Thread t2 = new Thread(() -> { try { service.send(40L); } catch (Exception ignored) { } });
        t1.start();
        t2.start();
        t1.join(5000);
        t2.join(5000);

        // red: 両threadがstatus=下書きを読んでからproviderを呼ぶため、外部書類が2件作られる
        assertEquals(1, calls.get(), "同一書類のsendでprovider createは1回でなければならない");
    }
}
