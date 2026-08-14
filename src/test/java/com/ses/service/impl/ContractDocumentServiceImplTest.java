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

        service = new ContractDocumentServiceImpl(templateMapper, contractMapper,
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
    void Metadataが未登録またはCleanでない場合は送信原本Downloadを拒否する() throws Exception {
        ContractDocument doc = new ContractDocument();
        doc.setId(10L);
        Path pdfFile = tempDir.resolve("contracts").resolve("10").resolve("document-10.pdf");
        Files.createDirectories(pdfFile.getParent());
        Files.write(pdfFile, "pdf data".getBytes());
        doc.setPdfPath(pdfFile.toString());

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
    void downloadは送信原本のみを返し署名PDFパスを返さない() throws Exception {
        // 三artifact分離: source downloadはpdfPathだけを対象にする（signed/certificateはartifact service）
        ContractDocument doc = new ContractDocument();
        doc.setId(11L);
        Path dir = tempDir.resolve("contracts").resolve("11");
        Files.createDirectories(dir);
        Path source = dir.resolve("document-11.pdf");
        Files.write(source, "source pdf".getBytes());
        doc.setPdfPath(source.toString());
        doc.setSignedPdfPath(dir.resolve("signed-11.pdf").toString());

        ContractDocumentMapper baseMapper = (ContractDocumentMapper) ReflectionTestUtils.getField(service, "baseMapper");
        when(baseMapper.selectById(11L)).thenReturn(doc);

        FileSecurityMetadata ok = new FileSecurityMetadata();
        ok.setStorageState("PUBLISHED");
        ok.setScanStatus("CLEAN");
        when(metadataMapper.selectByStoredName(eq("default"), any())).thenReturn(ok);

        byte[] result = service.download(11L);
        assertArrayEquals("source pdf".getBytes(), result);
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

    // ===== HFP-02-01/06: sync(旧実装)撤去後の三hash不変性 =====
    // 旧sync()はCloudSignArtifactService(締結後回収)とCloudSignSyncService(状態同期)へ置き換え済み。
    // pdfSha256は送信原本hashとして不変であり、signed/certificateは別hash列・別archive idで管理される。

    @Test
    void 二重queueSendは同じoperationとして扱いproviderを呼ばない() throws Exception {
        ContractDocumentMapper baseMapper = (ContractDocumentMapper) ReflectionTestUtils.getField(service, "baseMapper");
        ContractDocument doc = new ContractDocument();
        doc.setId(40L);
        doc.setContractId(1L);
        doc.setTemplateId(100L);
        doc.setTemplateVersion(1);
        doc.setStatus("下書き");
        doc.setDispatchState(com.ses.common.enums.DispatchState.NONE.name());
        doc.setVersion(0);
        doc.setRecipientName("マスク宛先");
        doc.setRecipientEmail("recipient-masked@example.invalid");
        when(baseMapper.selectById(40L)).thenReturn(doc);

        Contract contract = new Contract();
        contract.setId(1L);
        contract.setContractNo("C-2026-001");
        when(contractMapper.selectById(1L)).thenReturn(contract);

        // 実在するPDFを作成し、保存hashをdocへ設定する
        Path dir = tempDir.resolve("contracts").resolve("1");
        Files.createDirectories(dir);
        Path pdf = dir.resolve("document-40.pdf");
        Files.write(pdf, "%PDF-1.4\n1 0 obj\nendobj\ntrailer\n%%EOF\n".getBytes());
        doc.setPdfPath(pdf.toString());
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte v : md.digest(Files.readAllBytes(pdf))) {
            sb.append(String.format("%02x", v));
        }
        doc.setPdfSha256(sb.toString());

        when(baseMapper.casQueue(eq(40L), eq(0), any(), any())).thenAnswer(inv -> {
            doc.setDispatchState(com.ses.common.enums.DispatchState.QUEUED.name());
            doc.setOperationId(inv.getArgument(2));
            doc.setSendPayloadSha256(inv.getArgument(3));
            return 1;
        });

        com.ses.dto.cloudsign.ConfirmedSendRequest request =
                new com.ses.dto.cloudsign.ConfirmedSendRequest("C-2026-001", 1, "マスク宛先",
                        "recipient-masked@example.invalid", "SES契約書 1", "ja");

        ContractDocument queued = service.queueSend(40L, request);
        assertEquals(com.ses.common.enums.DispatchState.QUEUED.name(), queued.getDispatchState());
        assertNotNull(queued.getOperationId());

        // 二重クリック: 同一payloadの再queueは既存operationを返し、casQueueを再実行しない
        ContractDocument again = service.queueSend(40L, request);
        assertEquals(queued.getOperationId(), again.getOperationId());
        verify(baseMapper, times(1)).casQueue(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    void queueSendはpayload不一致を拒否し外部APIを呼ばない() throws Exception {
        ContractDocumentMapper baseMapper = (ContractDocumentMapper) ReflectionTestUtils.getField(service, "baseMapper");
        ContractDocument doc = new ContractDocument();
        doc.setId(41L);
        doc.setContractId(1L);
        doc.setTemplateId(100L);
        doc.setTemplateVersion(1);
        doc.setStatus("下書き");
        doc.setDispatchState(com.ses.common.enums.DispatchState.NONE.name());
        doc.setVersion(0);
        doc.setRecipientName("マスク宛先");
        doc.setRecipientEmail("recipient-masked@example.invalid");
        when(baseMapper.selectById(41L)).thenReturn(doc);

        Contract contract = new Contract();
        contract.setId(1L);
        contract.setContractNo("C-2026-001");
        when(contractMapper.selectById(1L)).thenReturn(contract);

        Path dir = tempDir.resolve("contracts").resolve("1");
        Files.createDirectories(dir);
        Path pdf = dir.resolve("document-41.pdf");
        Files.write(pdf, "%PDF-1.4\n1 0 obj\nendobj\ntrailer\n%%EOF\n".getBytes());
        doc.setPdfPath(pdf.toString());
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte v : md.digest(Files.readAllBytes(pdf))) {
            sb.append(String.format("%02x", v));
        }
        doc.setPdfSha256(sb.toString());

        // 確認時の契約番号と現在の契約番号が不一致 → payloadChanged
        com.ses.dto.cloudsign.ConfirmedSendRequest wrong =
                new com.ses.dto.cloudsign.ConfirmedSendRequest("C-OLD-001", 1, "マスク宛先",
                        "recipient-masked@example.invalid", "SES契約書 1", "ja");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.queueSend(41L, wrong));
        assertEquals("error.contract.document.payloadChanged", ex.getMessageKey());
    }
}
