package com.ses.service.changerequest.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.document.DocumentRegisterRequest;
import com.ses.entity.Document;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerCareerMapper;
import com.ses.mapper.EngineerChangeRequestMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.SkillTagMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.DocumentService;
import com.ses.service.EngineerSalesService;
import com.ses.service.EngineerSkillService;
import com.ses.service.NotificationService;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.approval.ApprovalTargetAdapterRegistry;
import com.ses.service.changerequest.EngineerChangeRequestService.SkillSheetConfirmResult;
import com.ses.service.changerequest.EngineerChangeRequestService.SkillSheetPreview;
import com.ses.service.document.DocumentLinkCommandService;
import com.ses.service.security.OrganizationScopeService;
import com.ses.service.skillsheet.SkillSheetGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NF01細粒度epoch計画 §4.3（confirmSkillSheet）・M7の一部をfastで固定する: スキルシート確定は、PDF生成と
 * {@code registerGenerated}（NOT_SUPPORTED。scan・storage・commitNew）を文書行のlockなしで終えてから、
 * ENGINEER linkの挿入（文書行のX lock・epoch増分）と確認日時の記録を行う。
 *
 * <p>RB-PRERUN 1: linkの文書存在確認（非lock読み）は、文書行のcommitより後に始まったtransactionで行わなければならない。
 * 外側のtransactionで先にpreviewを読むと、REPEATABLE READのread viewが文書のcommitより前に作られ、初回の確定だけが
 * 404になる（実MySQLでの再現は {@code SkillSheetConfirmFirstAttemptMySqlTest}）。H2はこのread viewを再現しないため、
 * ここでは「registerGeneratedの時点で開いているtransactionが無く、linkと確認日時の記録はその後に始まるtransactionの中で
 * 行う」ことを、transaction managerの呼出し順で固定する。</p>
 *
 * <p>RB-PRERUN 追加 P3-1: PDFはpreviewと同じ読取専用transaction（同じread view）で生成し、登録する文書と確認版
 * （fingerprint）を同じ時点のデータから作る。</p>
 */
class EngineerChangeRequestSkillSheetLockOrderTest {

    private static final long ENGINEER_ID = 7L;
    private static final String FINGERPRINT = "fp-1";

    private DocumentService documentService;
    private DocumentLinkCommandService linkCommand;
    private SkillSheetGenerator generator;
    private DocumentLinkMapper documentLinkMapper;
    private DocumentMapper documentMapper;
    private EngineerMapper engineerMapper;
    private PlatformTransactionManager transactionManager;
    private Clock clock;
    private EngineerChangeRequestServiceImpl service;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        linkCommand = mock(DocumentLinkCommandService.class);
        generator = mock(SkillSheetGenerator.class);
        documentLinkMapper = mock(DocumentLinkMapper.class);
        documentMapper = mock(DocumentMapper.class);
        engineerMapper = mock(EngineerMapper.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        clock = Clock.fixed(Instant.parse("2026-11-15T01:00:00Z"), ZoneId.of("Asia/Tokyo"));
        service = spy(new EngineerChangeRequestServiceImpl(
                mock(EngineerChangeRequestMapper.class), engineerMapper, mock(EngineerCareerMapper.class),
                mock(EngineerSkillService.class), mock(SkillTagMapper.class), mock(ApprovalRequestMapper.class),
                documentLinkMapper, mock(ContractMapper.class), mock(CustomerMapper.class), mock(ProjectMapper.class),
                mock(SysUserMapper.class), mock(EngineerSalesService.class), mock(ApprovalTargetAdapterRegistry.class),
                mock(ApprovalEngineService.class), documentService, documentMapper, mock(DocumentVersionMapper.class),
                mock(NotificationService.class), mock(OrganizationScopeService.class), generator, new ObjectMapper(),
                clock, linkCommand, transactionManager));
        doReturn(new SkillSheetPreview("要員A", null, null, null, null, null, null, List.of(), List.of(),
                FINGERPRINT, null, null, false)).when(service).skillSheetPreview(ENGINEER_ID);
        when(generator.generatePdf(ENGINEER_ID)).thenReturn(new byte[]{1, 2, 3});
    }

    @Test
    void スキルシート確定はregisterGeneratedの後に初めて文書lockを伴うlinkを作る() {
        Document registered = registeredDocument();
        when(documentService.registerGenerated(any(DocumentRegisterRequest.class), any(InputStream.class)))
                .thenAnswer(invocation -> {
                    // 登録時点では文書行のlockを伴う呼出し（link・link付帯情報の記録）がまだ無いこと
                    assertTrue(Mockito.mockingDetails(linkCommand).getInvocations().isEmpty(),
                            "registerGeneratedより前にlink命令を呼ばない");
                    assertTrue(Mockito.mockingDetails(documentService).getInvocations().stream()
                                    .noneMatch(call -> call.getMethod().getName().equals("link")),
                            "registerGeneratedより前にDocumentService.linkを呼ばない");
                    return registered;
                });

        SkillSheetConfirmResult result = service.confirmSkillSheet(ENGINEER_ID, FINGERPRINT);

        assertEquals(FINGERPRINT, result.fingerprint());
        assertEquals(LocalDateTime.now(clock), result.confirmedAt());
        ArgumentCaptor<DocumentRegisterRequest> request = ArgumentCaptor.forClass(DocumentRegisterRequest.class);
        InOrder order = inOrder(documentService, linkCommand);
        order.verify(documentService).registerGenerated(request.capture(), any(InputStream.class));
        order.verify(documentService).link(55L, "ENGINEER", ENGINEER_ID);
        order.verify(linkCommand).recordSkillSheetConfirmation(eq("tenant-a"), eq(55L), eq(ENGINEER_ID),
                eq(LocalDateTime.now(clock)), eq(FINGERPRINT));
        assertEquals("SKILL_SHEET", request.getValue().getDocumentType());
        assertNull(request.getValue().getTargetType(), "初期linkはcommitNewで作らずlockを伴うlinkで作る");
        // link付帯情報の全列書戻し（updateById）は行わない
        verifyNoInteractions(documentLinkMapper, documentMapper, engineerMapper);
    }

    @Test
    void 文書行のcommit時点でtransactionは開いておらずlinkの存在確認は後から始まるtransactionで行う() {
        Document registered = registeredDocument();
        when(documentService.registerGenerated(any(DocumentRegisterRequest.class), any(InputStream.class)))
                .thenAnswer(invocation -> {
                    // registerGenerated（NOT_SUPPORTED）が文書行を別接続でcommitする時点で、このmethodが開いた
                    // transactionが残っていないこと（残っていればread viewが文書のcommitより前に作られている）
                    assertEquals(0, openTransactions(), "文書のcommit前に開いたtransactionを閉じていること");
                    return registered;
                });
        doAnswer(invocation -> {
            assertEquals(1, openTransactions(), "linkは文書のcommit後に始めたtransactionの中で呼ぶ");
            return null;
        }).when(documentService).link(anyLong(), eq("ENGINEER"), anyLong());
        when(linkCommand.recordSkillSheetConfirmation(any(), anyLong(), anyLong(), any(), any()))
                .thenAnswer(invocation -> {
                    assertEquals(1, openTransactions(), "確認日時の記録はlinkと同じtransactionで行う");
                    return 1;
                });
        when(generator.generatePdf(ENGINEER_ID)).thenAnswer(invocation -> {
            // previewと同じ読取専用transaction（同じread view）の中でPDFを作る（RB-PRERUN 追加 P3-1）
            assertEquals(1, openTransactions(), "PDFはpreviewと同じtransactionの中で生成する");
            return new byte[]{1, 2, 3};
        });

        service.confirmSkillSheet(ENGINEER_ID, FINGERPRINT);

        InOrder order = inOrder(transactionManager, service, generator, documentService, linkCommand);
        order.verify(transactionManager).getTransaction(argThat(TransactionDefinition::isReadOnly));
        order.verify(service).skillSheetPreview(ENGINEER_ID);
        order.verify(generator).generatePdf(ENGINEER_ID);
        order.verify(transactionManager).commit(any());
        order.verify(documentService).registerGenerated(any(DocumentRegisterRequest.class), any(InputStream.class));
        order.verify(transactionManager).getTransaction(argThat(definition -> !definition.isReadOnly()
                && definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED));
        order.verify(documentService).link(55L, "ENGINEER", ENGINEER_ID);
        order.verify(linkCommand).recordSkillSheetConfirmation(eq("tenant-a"), eq(55L), eq(ENGINEER_ID),
                any(LocalDateTime.class), eq(FINGERPRINT));
        order.verify(transactionManager).commit(any());
        verify(transactionManager, times(2)).getTransaction(any());
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    void 確定methodはproxyで外側のtransactionを開かない() throws NoSuchMethodException {
        Transactional transactional = EngineerChangeRequestServiceImpl.class
                .getMethod("confirmSkillSheet", Long.class, String.class).getAnnotation(Transactional.class);
        assertNotNull(transactional, "呼出元のtransactionを止めるためNOT_SUPPORTEDを明示すること");
        assertEquals(Propagation.NOT_SUPPORTED, transactional.propagation(),
                "外側のtransactionがあると文書のcommitより前にread viewが作られ、初回の確定が404になる");
    }

    @Test
    void fingerprintが古ければ409で文書を登録せず書込みtransactionも始めない() {
        BusinessException stale = assertThrows(BusinessException.class,
                () -> service.confirmSkillSheet(ENGINEER_ID, "fp-old"));

        assertEquals(409, stale.getCode());
        assertEquals("error.changeRequest.skillSheetStale", stale.getMessageKey());
        verifyNoInteractions(generator, documentService, linkCommand);
        verify(transactionManager, times(1)).getTransaction(any());
        verify(transactionManager).getTransaction(argThat(TransactionDefinition::isReadOnly));
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    private Document registeredDocument() {
        Document registered = new Document();
        registered.setId(55L);
        registered.setTenantId("tenant-a");
        return registered;
    }

    /** transaction managerから取得したtransactionのうち、まだcommit/rollbackしていない数。 */
    private long openTransactions() {
        long begun = Mockito.mockingDetails(transactionManager).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("getTransaction")).count();
        long ended = Mockito.mockingDetails(transactionManager).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("commit")
                        || call.getMethod().getName().equals("rollback")).count();
        return begun - ended;
    }
}
