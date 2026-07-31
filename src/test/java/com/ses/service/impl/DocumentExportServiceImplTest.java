package com.ses.service.impl;

import com.ses.dto.document.DocumentSearchQuery;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.service.storage.DocumentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Task B2 DocumentExportServiceImpl テスト。
 */
@ExtendWith(MockitoExtension.class)
class DocumentExportServiceImplTest {

    @Mock DocumentMapper documentMapper;
    @Mock DocumentVersionMapper documentVersionMapper;
    @Mock DocumentStorage documentStorage;
    @Mock com.ses.service.DocumentService documentService;

    @InjectMocks DocumentExportServiceImpl exportService;

    @Test
    void exportTaxZip_createsZipWithManifestAndFiles() throws Exception {
        Document doc = new Document();
        doc.setId(100L);
        doc.setDocumentType("CONTRACT");
        doc.setDocumentNo("CNT-2026-001");
        doc.setTitle("システム開発委託契約書");
        doc.setCounterpartyNameSnapshot("株式会社テスト顧客");
        doc.setTransactionDate(LocalDate.of(2026, 4, 1));
        doc.setAmount(new BigDecimal("1500000"));
        doc.setDirection("OUTGOING");

        DocumentVersion version = new DocumentVersion();
        version.setDocumentId(100L);
        version.setVersionNo(1);
        version.setStorageKey("key-100");
        version.setOriginalName("contract.pdf");
        version.setSha256("sha256-dummy-hash");

        when(documentMapper.selectList(any())).thenReturn(List.of(doc));
        when(documentVersionMapper.findByDocumentId(100L)).thenReturn(List.of(version));
        when(documentStorage.open("key-100")).thenReturn(new ByteArrayInputStream("PDF content".getBytes()));
        org.mockito.Mockito.doNothing().when(documentService).applyDataScopeFilter(any());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        exportService.exportTaxZip(new DocumentSearchQuery(), baos);

        byte[] zipBytes = baos.toByteArray();
        assertTrue(zipBytes.length > 0);

        // ZIP解凍検証
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes));
        ZipEntry entry1 = zis.getNextEntry();
        assertNotNull(entry1);
        assertEquals("manifest.csv", entry1.getName());

        String manifestContent = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(manifestContent.contains("document_id,version_no,document_type"));
        assertTrue(manifestContent.contains("100"));
        assertTrue(manifestContent.contains("株式会社テスト顧客"));
        assertTrue(manifestContent.contains("MATCH"), "manifestContent に hash 検証結果 'MATCH' が含まれていません");

        ZipEntry entry2 = zis.getNextEntry();
        assertNotNull(entry2);
        assertEquals("files/100_v1_contract.pdf", entry2.getName());

        String fileContent = new String(zis.readAllBytes());
        assertEquals("PDF content", fileContent);
    }

    @Test
    void exportTaxZip_exceedsLimit_throwsBusinessException() {
        List<Document> list = java.util.Collections.nCopies(10001, new Document());
        when(documentMapper.selectList(any())).thenReturn(list);
        org.mockito.Mockito.doNothing().when(documentService).applyDataScopeFilter(any());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        com.ses.common.exception.BusinessException ex = assertThrows(
                com.ses.common.exception.BusinessException.class,
                () -> exportService.exportTaxZip(new DocumentSearchQuery(), baos)
        );
        assertEquals("error.export.limitExceeded", ex.getMessageKey());
    }
}
