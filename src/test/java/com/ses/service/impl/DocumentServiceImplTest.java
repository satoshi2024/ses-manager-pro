package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.document.DocumentRegisterRequest;
import com.ses.dto.document.IntegrityFinding;
import com.ses.entity.Document;
import com.ses.entity.DocumentAccessLog;
import com.ses.entity.DocumentDisposalRequest;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentType;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.DocumentAccessLogMapper;
import com.ses.mapper.DocumentDisposalRequestMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentTypeMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import com.ses.service.storage.DocumentStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * T022 DocumentService 単体テスト。
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

    @Mock DocumentMapper documentMapper;
    @Mock DocumentVersionMapper documentVersionMapper;
    @Mock DocumentLinkMapper documentLinkMapper;
    @Mock DocumentAccessLogMapper documentAccessLogMapper;
    @Mock DocumentDisposalRequestMapper documentDisposalRequestMapper;
    @Mock DocumentTypeMapper documentTypeMapper;
    @Mock com.ses.service.security.DataScopeService dataScopeService;
    @Mock DocumentStorage documentStorage;
    @Mock ObjectProvider<FileScanner> fileScannerProvider;
    @Mock FileScanner fileScanner;
    @Mock com.ses.mapper.DocumentHashClaimMapper documentHashClaimMapper;
    @Mock ObjectProvider<com.ses.service.security.AuthorizationService> authorizationServiceProvider;
    @Mock ObjectProvider<com.ses.service.EngineerAccountLinkService> engineerAccountLinkServiceProvider;
    @Mock ObjectProvider<com.ses.service.security.OrganizationScopeService> organizationScopeServiceProvider;
    @Mock com.ses.service.AssetScopeService assetScopeService;

    DocumentServiceImpl sut;

    @BeforeEach
    void setUp() {
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(config, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Document.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, DocumentVersion.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, DocumentLink.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, DocumentAccessLog.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, DocumentDisposalRequest.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, DocumentType.class);

        var principal = User.withUsername("1").password("").authorities("ROLE_管理者").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        lenient().when(fileScannerProvider.getIfAvailable()).thenReturn(fileScanner);
        lenient().when(fileScanner.scan(any(), any())).thenReturn(FileScanResult.clean("test"));
        lenient().when(authorizationServiceProvider.getIfAvailable()).thenReturn(null);
        lenient().when(engineerAccountLinkServiceProvider.getIfAvailable()).thenReturn(null);
        lenient().when(organizationScopeServiceProvider.getIfAvailable()).thenReturn(null);

        // ObjectProvider は型消去で @InjectMocks が取り違えるため、コンストラクタで明示配線する
        sut = new DocumentServiceImpl(
                documentMapper,
                documentVersionMapper,
                documentLinkMapper,
                documentAccessLogMapper,
                documentDisposalRequestMapper,
                documentTypeMapper,
                dataScopeService,
                documentStorage,
                fileScannerProvider,
                null,
                null,
                documentHashClaimMapper,
                authorizationServiceProvider,
                engineerAccountLinkServiceProvider,
                organizationScopeServiceProvider,
                assetScopeService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void computeSha256_fixedInput_returnsExpectedHex() {
        byte[] input = "hello".getBytes(StandardCharsets.UTF_8);
        String hash = DocumentServiceImpl.computeSha256(input);
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash);
    }

    @Test
    void registerGenerated_idempotentKey_doesNotCreateSecondVersion() {
        DocumentVersion existingVersion = new DocumentVersion();
        existingVersion.setId(99L);
        existingVersion.setDocumentId(1L);

        Document existingDoc = new Document();
        existingDoc.setId(1L);
        existingDoc.setStatus("DRAFT");

        when(documentVersionMapper.findByIdempotencyKey(anyString(), eq("GENERATED"), eq("INVOICE:1"), eq("v1")))
                .thenReturn(existingVersion);
        when(documentMapper.selectById(1L)).thenReturn(existingDoc);

        var req = DocumentRegisterRequest.builder()
                .documentType("INVOICE_OUT")
                .sourceType("GENERATED")
                .businessKey("INVOICE:1")
                .versionDiscriminator("v1")
                .direction("OUTGOING")
                .build();

        Document result = sut.registerGenerated(req, new ByteArrayInputStream("content".getBytes()));

        verify(documentMapper, never()).insert(any(Document.class));
        verify(documentVersionMapper, never()).insert(any(DocumentVersion.class));
        assertEquals(1L, result.getId());
    }

    @Test
    void registerGenerated_scanInfected_throws400ScanRejected() {
        when(fileScanner.scan(any(), any())).thenReturn(FileScanResult.infected("test"));
        when(documentVersionMapper.findByIdempotencyKey(anyString(), anyString(), anyString(), anyString())).thenReturn(null);

        var req = DocumentRegisterRequest.builder()
                .documentType("CONTRACT")
                .sourceType("GENERATED")
                .businessKey("CONTRACT:99")
                .versionDiscriminator("v1")
                .direction("OUTGOING")
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                sut.registerGenerated(req, new ByteArrayInputStream("bad content".getBytes())));

        assertEquals(400, ex.getCode());
        assertEquals("error.file.scanRejected", ex.getMessageKey());
    }

    @Test
    void registerGenerated_newDocument_versionNoIs1() {
        when(documentVersionMapper.findByIdempotencyKey(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        doNothing().when(documentStorage).put(anyString(), any(InputStream.class), anyBoolean());
        doNothing().when(documentStorage).promote(anyString());
        when(documentMapper.insert(any(Document.class))).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            d.setId(10L);
            return 1;
        });
        when(documentVersionMapper.findLatestByDocumentId(anyLong())).thenReturn(null);
        when(documentVersionMapper.insert(any(DocumentVersion.class))).thenReturn(1);
        when(documentAccessLogMapper.insert(any(DocumentAccessLog.class))).thenReturn(1);

        var req = DocumentRegisterRequest.builder()
                .documentType("CONTRACT")
                .sourceType("GENERATED")
                .businessKey("CONTRACT:5")
                .versionDiscriminator("v1")
                .direction("OUTGOING")
                .originalName("contract.pdf")
                .build();

        sut.registerGenerated(req, new ByteArrayInputStream("pdf".getBytes()));

        ArgumentCaptor<DocumentVersion> versionCaptor = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(documentVersionMapper).insert(versionCaptor.capture());
        assertEquals(1, versionCaptor.getValue().getVersionNo());
    }

    @Test
    void registerReceived_hashClaim重複はstorage保存前に409を返す() {
        when(documentVersionMapper.findByIdempotencyKey(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        when(documentMapper.insert(any(Document.class))).thenAnswer(inv -> {
            ((Document) inv.getArgument(0)).setId(10L);
            return 1;
        });
        when(documentHashClaimMapper.insertClaim(anyString(), eq("ORDER_RECEIVED"), anyString(), eq(10L)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate"));
        var req = DocumentRegisterRequest.builder()
                .documentType("ORDER_RECEIVED").sourceType("RECEIVED")
                .businessKey("ORDER_RECEIVED:1").versionDiscriminator("1").build();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                sut.registerReceived(req, new ByteArrayInputStream("same".getBytes())));

        assertEquals(409, ex.getCode());
        assertEquals("error.order.duplicateSourceDocument", ex.getMessageKey());
        verify(documentStorage, never()).put(anyString(), any(InputStream.class), anyBoolean());
    }

    @Test
    void registerReceived_transactionRollbackでstorage実体を補償削除する() {
        when(documentVersionMapper.findByIdempotencyKey(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        when(documentMapper.insert(any(Document.class))).thenAnswer(inv -> {
            ((Document) inv.getArgument(0)).setId(11L);
            return 1;
        });
        when(documentHashClaimMapper.insertClaim(anyString(), eq("ORDER_RECEIVED"), anyString(), eq(11L))).thenReturn(1);
        when(documentVersionMapper.findLatestByDocumentId(11L)).thenReturn(null);
        when(documentVersionMapper.insert(any(DocumentVersion.class))).thenReturn(1);
        when(documentAccessLogMapper.insert(any(DocumentAccessLog.class))).thenReturn(1);
        var req = DocumentRegisterRequest.builder()
                .documentType("ORDER_RECEIVED").sourceType("RECEIVED")
                .businessKey("ORDER_RECEIVED:2").versionDiscriminator("1").build();

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            sut.registerReceived(req, new ByteArrayInputStream("content".getBytes()));
            var synchronizations = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertFalse(synchronizations.isEmpty());
            synchronizations.forEach(sync -> sync.afterCompletion(
                    org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        verify(documentStorage).delete(anyString());
    }

    @Test
    void registerReceived_storagePutFailureでもtransaction中に即時cleanupする() {
        when(documentVersionMapper.findByIdempotencyKey(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        when(documentMapper.insert(any(Document.class))).thenAnswer(inv -> {
            ((Document) inv.getArgument(0)).setId(12L);
            return 1;
        });
        when(documentHashClaimMapper.insertClaim(anyString(), eq("ORDER_RECEIVED"), anyString(), eq(12L)))
                .thenReturn(1);
        doThrow(new RuntimeException("simulated put failure"))
                .when(documentStorage).put(anyString(), any(InputStream.class), anyBoolean());
        var req = DocumentRegisterRequest.builder()
                .documentType("ORDER_RECEIVED").sourceType("RECEIVED")
                .businessKey("ORDER_RECEIVED:3").versionDiscriminator("1").build();

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            assertThrows(RuntimeException.class, () ->
                    sut.registerReceived(req, new ByteArrayInputStream("content".getBytes())));
            verify(documentStorage).delete(anyString());
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager
                    .getSynchronizations().isEmpty(), "put前にrollback補償が登録されているべき");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void addVersion_confirmedDocument_updatesStatusToAmended() {
        Document doc = new Document();
        doc.setId(10L);
        doc.setStatus("CONFIRMED");
        doc.setVersion(1L);
        when(documentMapper.selectById(10L)).thenReturn(doc);
        when(documentVersionMapper.findByIdempotencyKey(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        doNothing().when(documentStorage).put(anyString(), any(InputStream.class), anyBoolean());
        doNothing().when(documentStorage).promote(anyString());
        when(documentMapper.update(any(), any())).thenReturn(1);

        DocumentVersion latest = new DocumentVersion();
        latest.setVersionNo(1);
        when(documentVersionMapper.findLatestByDocumentId(10L)).thenReturn(latest);
        when(documentVersionMapper.insert(any(DocumentVersion.class))).thenReturn(1);
        when(documentAccessLogMapper.insert(any(DocumentAccessLog.class))).thenReturn(1);

        var req = DocumentRegisterRequest.builder()
                .documentType("CONTRACT")
                .sourceType("RECEIVED")
                .businessKey("CONTRACT:5")
                .versionDiscriminator("v2")
                .direction("INCOMING")
                .originalName("contract_v2.pdf")
                .build();

        DocumentVersion result = sut.addVersion(10L, req, new ByteArrayInputStream("pdf2".getBytes()));

        ArgumentCaptor<DocumentVersion> cap = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(documentVersionMapper).insert(cap.capture());
        assertEquals(2, cap.getValue().getVersionNo());
        verify(documentMapper).update(any(), any());
    }

    @Test
    void addVersion_optimisticLockConflict_throws409() {
        Document doc = new Document();
        doc.setId(10L);
        doc.setStatus("CONFIRMED");
        doc.setVersion(1L);
        when(documentMapper.selectById(10L)).thenReturn(doc);
        when(documentVersionMapper.findByIdempotencyKey(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        doNothing().when(documentStorage).put(anyString(), any(InputStream.class), anyBoolean());
        when(documentMapper.update(any(), any())).thenReturn(0); // CAS 失敗

        var req = DocumentRegisterRequest.builder()
                .documentType("CONTRACT")
                .sourceType("RECEIVED")
                .businessKey("CONTRACT:5")
                .versionDiscriminator("v2")
                .direction("INCOMING")
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                sut.addVersion(10L, req, new ByteArrayInputStream("pdf2".getBytes())));
        assertEquals(409, ex.getCode());
        assertEquals("error.document.optimisticLock", ex.getMessageKey());
    }

    @Test
    void requestDisposal_legalHoldActive_throwsBusinessException() {
        Document doc = new Document();
        doc.setId(5L);
        doc.setLegalHoldFlag(1);
        doc.setRetentionUntil(LocalDate.now().plusYears(5));
        doc.setStatus("CONFIRMED");
        when(documentMapper.selectById(5L)).thenReturn(doc);

        var ex = assertThrows(BusinessException.class, () -> sut.requestDisposal(5L, "廃棄理由"));
        assertEquals(400, ex.getCode());
        assertEquals("error.document.legalHoldActive", ex.getMessageKey());
    }

    @Test
    void requestDisposal_retentionUntilNull_throwsBusinessException() {
        Document doc = new Document();
        doc.setId(6L);
        doc.setLegalHoldFlag(0);
        doc.setRetentionUntil(null);
        doc.setStatus("CONFIRMED");
        when(documentMapper.selectById(6L)).thenReturn(doc);

        var ex = assertThrows(BusinessException.class, () -> sut.requestDisposal(6L, "廃棄理由"));
        assertEquals(400, ex.getCode());
        assertEquals("error.document.retentionUndetermined", ex.getMessageKey());
    }

    @Test
    void placeLegalHold_optimisticLockConflict_throwsBusinessException() {
        Document doc = new Document();
        doc.setId(7L);
        doc.setLegalHoldFlag(0);
        doc.setVersion(1L);
        when(documentMapper.selectById(7L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(0);

        var ex = assertThrows(BusinessException.class, () -> sut.placeLegalHold(7L, true, "訴訟対応"));
        assertEquals(409, ex.getCode());
    }

    @Test
    void executeDisposal_legalHoldActive_throws400() {
        DocumentDisposalRequest req = new DocumentDisposalRequest();
        req.setId(200L);
        req.setDocumentId(5L);
        req.setStatus("APPROVED");
        when(documentDisposalRequestMapper.selectById(200L)).thenReturn(req);

        Document doc = new Document();
        doc.setId(5L);
        doc.setLegalHoldFlag(1); // 途中で hold が設定された
        when(documentMapper.selectById(5L)).thenReturn(doc);

        BusinessException ex = assertThrows(BusinessException.class, () -> sut.executeDisposal(200L));
        assertEquals(400, ex.getCode());
        assertEquals("error.document.legalHoldActive", ex.getMessageKey());
    }

    @Test
    void approveDisposal_selfApproval_throwsBusinessException() {
        DocumentDisposalRequest req = new DocumentDisposalRequest();
        req.setId(200L);
        req.setDocumentId(5L);
        req.setStatus("PENDING");
        req.setRequestedBy(1L); // SecurityContext の 1L と一致

        when(documentDisposalRequestMapper.selectById(200L)).thenReturn(req);

        var ex = assertThrows(BusinessException.class, () -> sut.approveDisposal(200L));
        assertEquals(400, ex.getCode());
        assertEquals("error.document.disposalSelfApproval", ex.getMessageKey());
    }

    @Test
    void computeRetentionUntil_undeterminedStartRule_returnsNull() {
        Document doc = new Document();
        doc.setId(30L);
        doc.setDocumentType("CONTRACT");

        DocumentType type = new DocumentType();
        type.setCode("CONTRACT");
        type.setRetentionYears(10);
        type.setRetentionStartRule("CLOSED_AT");
        when(documentTypeMapper.selectOne(any())).thenReturn(type);

        LocalDate result = sut.computeRetentionUntil(doc);

        assertNull(result, "CLOSED_AT起算日が未確定の場合はnullを返さなければならない");
    }

    @Test
    void verifyIntegrity_hashMismatch_returnsMismatchFinding() {
        DocumentVersion v = new DocumentVersion();
        v.setId(101L);
        v.setDocumentId(50L);
        v.setStorageKey("path/to/key.pdf");
        v.setSha256("0000000000000000000000000000000000000000000000000000000000000000");

        when(documentVersionMapper.findByDocumentId(50L)).thenReturn(List.of(v));
        when(documentStorage.open("path/to/key.pdf")).thenReturn(new ByteArrayInputStream("actual bytes".getBytes()));

        List<IntegrityFinding> findings = sut.verifyIntegrity(50L);

        assertEquals(1, findings.size());
        assertEquals("HASH_MISMATCH", findings.get(0).getFindingType());
        assertEquals(50L, findings.get(0).getDocumentId());
    }

    @Test
    void verifyIntegrity_storageMissing_returnsMissingFinding() {
        DocumentVersion v = new DocumentVersion();
        v.setId(102L);
        v.setDocumentId(51L);
        v.setStorageKey("path/missing.pdf");
        v.setSha256("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");

        when(documentVersionMapper.findByDocumentId(51L)).thenReturn(List.of(v));
        when(documentStorage.open("path/missing.pdf")).thenThrow(new RuntimeException("File not found"));

        List<IntegrityFinding> findings = sut.verifyIntegrity(51L);

        assertEquals(1, findings.size());
        assertEquals("STORAGE_MISSING", findings.get(0).getFindingType());
        assertEquals(51L, findings.get(0).getDocumentId());
    }

    private void loginAsRole(String role) {
        com.ses.entity.SysUser user = new com.ses.entity.SysUser();
        user.setId(99L);
        user.setUsername("sales");
        user.setRole(role);
        com.ses.config.LoginUser principal = new com.ses.config.LoginUser(
                user, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
    }

    @Test
    void placeLegalHold_outOfScope_throws403AndDoesNotUpdate() {
        loginAsRole("営業");
        Document doc = new Document();
        doc.setId(70L);
        doc.setDocumentType("CONTRACT");
        doc.setLegalHoldFlag(0);
        doc.setVersion(1L);
        when(documentMapper.selectById(70L)).thenReturn(doc);
        when(documentLinkMapper.selectList(any())).thenReturn(List.of());

        var ex = assertThrows(BusinessException.class, () -> sut.placeLegalHold(70L, true, "訴訟"));
        assertEquals(403, ex.getCode());
        verify(documentMapper, never()).update(any(), any());
    }

    @Test
    void requestDisposal_outOfScope_throws403AndDoesNotInsert() {
        loginAsRole("営業");
        Document doc = new Document();
        doc.setId(71L);
        doc.setDocumentType("CONTRACT");
        doc.setLegalHoldFlag(0);
        doc.setRetentionUntil(LocalDate.now().plusYears(1));
        doc.setStatus("CONFIRMED");
        when(documentMapper.selectById(71L)).thenReturn(doc);
        when(documentLinkMapper.selectList(any())).thenReturn(List.of());

        var ex = assertThrows(BusinessException.class, () -> sut.requestDisposal(71L, "廃棄"));
        assertEquals(403, ex.getCode());
        verify(documentDisposalRequestMapper, never()).insert(any(DocumentDisposalRequest.class));
    }

    @Test
    void assertDocumentAccessAllowed_receiptDeniedForSales() {
        loginAsRole("営業");
        Document doc = new Document();
        doc.setId(80L);
        doc.setDocumentType("RECEIPT");
        DocumentLink engLink = new DocumentLink();
        engLink.setDocumentId(80L);
        engLink.setTargetType("ENGINEER");
        engLink.setTargetId(5L);
        when(documentLinkMapper.selectOne(any())).thenReturn(engLink);

        var ex = assertThrows(BusinessException.class, () -> sut.assertDocumentAccessAllowed(doc));
        assertEquals(403, ex.getCode());
    }

    @Test
    void applyDataScopeFilter_excludesReceiptForSales() {
        loginAsRole("営業");
        when(dataScopeService.isScoped()).thenReturn(false);

        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<>();
        sut.applyDataScopeFilter(wrapper);

        String segment = wrapper.getSqlSegment();
        assertTrue(segment != null && segment.contains("document_type"),
                () -> "RECEIPT除外の document_type 条件が必要: " + segment);
    }

    @Test
    void getVersionStorageKey_returnsDbKey() {
        DocumentVersion v = new DocumentVersion();
        v.setStorageKey("real-db-key");
        when(documentVersionMapper.selectOne(any())).thenReturn(v);

        assertEquals("real-db-key", sut.getVersionStorageKey(1L, 1));
    }

}
