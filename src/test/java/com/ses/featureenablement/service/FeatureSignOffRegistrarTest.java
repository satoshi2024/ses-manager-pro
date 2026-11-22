package com.ses.featureenablement.service;

import com.ses.common.enums.FileKind;
import com.ses.dto.file.StoredFile;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.mapper.FeatureSignOffMapper;
import com.ses.service.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * review rb-fe-a1 1a4fd48d 差分 P3-N1: 登録の rollback の後の文書の削除は、持ち主の署名の行が残る保存名を消さず
 * （{@code discardUnregistered} を呼ばない）、commit した登録では何も消さない。{@code discardUnregistered} の拒否（metadata の行・
 * backup の書込み静止）は log だけで、孤児の清掃に任せる。
 */
class FeatureSignOffRegistrarTest {

    private static final String STORED = "0123456789abcdef0123456789abcdef.pdf";
    private static final byte[] PDF = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    private FeatureSignOffMapper signOffMapper;
    private FileStorageService fileStorage;
    private FeatureSignOffRegistrar registrar;

    @BeforeEach
    void setUp() {
        signOffMapper = mock(FeatureSignOffMapper.class);
        fileStorage = mock(FileStorageService.class);
        when(fileStorage.store(any(byte[].class), anyString(), eq(FileKind.FEATURE_SIGN_OFF)))
                .thenReturn(new StoredFile(STORED, "signoff.pdf", PDF.length));
        registrar = new FeatureSignOffRegistrar(null, signOffMapper, fileStorage, FeatureEnablementTenant.fixed("default"));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void rollbackで持ち主の行が無ければ登録されなかった文書を消す() {
        registrar.storeDocument(PDF, "signoff.pdf");

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(fileStorage).discardUnregistered(STORED, FileKind.FEATURE_SIGN_OFF);
    }

    @Test
    void rollbackでも持ち主の署名の行が残る保存名は消さない() {
        when(signOffMapper.countByStoredNames(STORED, STORED)).thenReturn(1L);
        registrar.storeDocument(PDF, "signoff.pdf");

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(fileStorage, never()).discardUnregistered(anyString(), any());
    }

    @Test
    void commitした登録の文書は消さない() {
        registrar.storeDocument(PDF, "signoff.pdf");

        complete(TransactionSynchronization.STATUS_COMMITTED);

        verify(fileStorage, never()).discardUnregistered(anyString(), any());
        verify(signOffMapper, never()).countByStoredNames(anyString(), anyString());
    }

    @Test
    void 削除が拒まれてもrollbackの後始末は例外を外へ出さない() {
        doThrow(new com.ses.backup.quiesce.BackupWriteQuiescedException("uploads:file-storage.discard-unregistered"))
                .when(fileStorage).discardUnregistered(STORED, FileKind.FEATURE_SIGN_OFF);
        registrar.storeDocument(PDF, "signoff.pdf");

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(fileStorage).discardUnregistered(STORED, FileKind.FEATURE_SIGN_OFF);
    }

    private static void complete(int status) {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        for (TransactionSynchronization synchronization : synchronizations) {
            synchronization.afterCompletion(status);
        }
    }
}
