package com.ses.service.changerequest.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NF01細粒度epoch計画 §4.3（confirmSkillSheet）・M7の一部をfastで固定する: スキルシート確定は、PDF生成と
 * {@code registerGenerated}（NOT_SUPPORTED。scan・storage・commitNew）を文書行のlockなしで終えてから、
 * ENGINEER linkの挿入（文書行のX lock・epoch増分）と確認日時の記録を行う。
 */
class EngineerChangeRequestSkillSheetLockOrderTest {

    private static final long ENGINEER_ID = 7L;
    private static final String FINGERPRINT = "fp-1";

    @Test
    void スキルシート確定はregisterGeneratedの後に初めて文書lockを伴うlinkを作る() {
        DocumentService documentService = mock(DocumentService.class);
        DocumentLinkCommandService linkCommand = mock(DocumentLinkCommandService.class);
        SkillSheetGenerator generator = mock(SkillSheetGenerator.class);
        DocumentLinkMapper documentLinkMapper = mock(DocumentLinkMapper.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        EngineerMapper engineerMapper = mock(EngineerMapper.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T01:00:00Z"), ZoneId.of("Asia/Tokyo"));
        EngineerChangeRequestServiceImpl service = spy(new EngineerChangeRequestServiceImpl(
                mock(EngineerChangeRequestMapper.class), engineerMapper, mock(EngineerCareerMapper.class),
                mock(EngineerSkillService.class), mock(SkillTagMapper.class), mock(ApprovalRequestMapper.class),
                documentLinkMapper, mock(ContractMapper.class), mock(CustomerMapper.class), mock(ProjectMapper.class),
                mock(SysUserMapper.class), mock(EngineerSalesService.class), mock(ApprovalTargetAdapterRegistry.class),
                mock(ApprovalEngineService.class), documentService, documentMapper, mock(DocumentVersionMapper.class),
                mock(NotificationService.class), mock(OrganizationScopeService.class), generator, new ObjectMapper(),
                clock, linkCommand));
        doReturn(new SkillSheetPreview("要員A", null, null, null, null, null, null, List.of(), List.of(),
                FINGERPRINT, null, null, false)).when(service).skillSheetPreview(ENGINEER_ID);
        when(generator.generatePdf(ENGINEER_ID)).thenReturn(new byte[]{1, 2, 3});
        Document registered = new Document();
        registered.setId(55L);
        registered.setTenantId("tenant-a");
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
        ArgumentCaptor<DocumentRegisterRequest> request = ArgumentCaptor.forClass(DocumentRegisterRequest.class);
        InOrder order = inOrder(documentService, linkCommand);
        order.verify(documentService).registerGenerated(request.capture(), any(InputStream.class));
        order.verify(documentService).link(55L, "ENGINEER", ENGINEER_ID);
        order.verify(linkCommand).recordSkillSheetConfirmation(eq("tenant-a"), eq(55L), eq(ENGINEER_ID),
                eq(LocalDateTime.now(clock)), eq(FINGERPRINT));
        assertEquals("SKILL_SHEET", request.getValue().getDocumentType());
        assertEquals(null, request.getValue().getTargetType(), "初期linkはcommitNewで作らずlockを伴うlinkで作る");
        // link付帯情報の全列書戻し（updateById）は行わない
        verifyNoInteractions(documentLinkMapper, documentMapper, engineerMapper);
    }
}
