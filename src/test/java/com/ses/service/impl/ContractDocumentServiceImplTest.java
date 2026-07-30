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

        ContractDocumentMapper baseMapper = mock(ContractDocumentMapper.class);

        service = new ContractDocumentServiceImpl(templateMapper, contractMapper, cloudSignClient,
                pdfFontUtils, metadataMapperProvider, fileScannerProvider);
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
}
