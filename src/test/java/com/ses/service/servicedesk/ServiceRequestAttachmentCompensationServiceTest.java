package com.ses.service.servicedesk;

import com.ses.entity.ServiceAttachmentLink;
import com.ses.entity.ServiceRequestAttachmentCompensation;
import com.ses.mapper.ServiceRequestAttachmentCompensationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3-N2-6: 添付確定の補償台帳は注入したClockで日時を決める（LocalDateTime.now() を直接使わない）。
 * 再試行の対象もDBの現在時刻ではなく同じClockの時刻で選ぶ。
 */
class ServiceRequestAttachmentCompensationServiceTest {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Instant FIXED = Instant.parse("2026-11-17T01:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED, TOKYO);

    private final ServiceRequestAttachmentCompensationMapper mapper = mock(ServiceRequestAttachmentCompensationMapper.class);
    private final ServiceRequestAttachmentCommitService commitService = mock(ServiceRequestAttachmentCommitService.class);
    private final ServiceRequestAttachmentCompensationService service = new ServiceRequestAttachmentCompensationService(
            mapper, commitService, Clock.fixed(FIXED, TOKYO));

    @Test
    void 失敗の記録は固定Clockの時刻で作成し5分後に再試行する() {
        service.record("default", 10L, 20L, 30L, "PORTAL_VISIBLE", "a.pdf", 100L, "bk-1",
                new IllegalStateException("内部の詳細"));

        ArgumentCaptor<ServiceRequestAttachmentCompensation> row = ArgumentCaptor.forClass(ServiceRequestAttachmentCompensation.class);
        verify(mapper).insert(row.capture());
        assertEquals("RETRY", row.getValue().getStatus());
        assertEquals(NOW, row.getValue().getCreatedAt());
        assertEquals(NOW, row.getValue().getUpdatedAt());
        assertEquals(NOW.plusMinutes(ServiceRequestAttachmentCompensationService.RETRY_DELAY_MINUTES),
                row.getValue().getNextRetryAt());
        // 例外のmessageは保存しない（型名だけ）
        assertEquals("IllegalStateException", row.getValue().getLastError());
    }

    @Test
    void 再試行はClockの時刻で対象を選び成功時は解決時刻を失敗時は次回時刻を同じClockで決める() {
        ServiceRequestAttachmentCompensation succeeded = due(1L, "bk-ok");
        ServiceRequestAttachmentCompensation failed = due(2L, "bk-ng");
        when(mapper.selectDue(eq(NOW), anyInt())).thenReturn(List.of(succeeded, failed));
        when(commitService.commit(any(), any(), any(), any(), any(), any(), any(), eq("bk-ok")))
                .thenReturn(new ServiceAttachmentLink());
        when(commitService.commit(any(), any(), any(), any(), any(), any(), any(), eq("bk-ng")))
                .thenThrow(new IllegalStateException("失敗"));

        int completed = service.retryDue(10);

        assertEquals(1, completed);
        assertEquals("COMPLETED", succeeded.getStatus());
        assertEquals(NOW, succeeded.getResolvedAt());
        assertEquals(NOW, succeeded.getUpdatedAt());
        assertEquals("RETRY", failed.getStatus());
        assertNull(failed.getResolvedAt());
        assertEquals(2, failed.getAttemptCount());
        assertEquals(NOW.plusMinutes(ServiceRequestAttachmentCompensationService.RETRY_DELAY_MINUTES), failed.getNextRetryAt());
        assertEquals(NOW, failed.getUpdatedAt());
        verify(mapper).updateById(succeeded);
        verify(mapper).updateById(failed);
    }

    @Test
    void 同じ業務キーの再試行待ちがあれば記録しない() {
        when(mapper.selectRetry("default", "bk-dup")).thenReturn(due(3L, "bk-dup"));

        service.record("default", 10L, 20L, 30L, "INTERNAL", "a.pdf", 100L, "bk-dup", null);

        verify(mapper, never()).insert(any(ServiceRequestAttachmentCompensation.class));
    }

    private static ServiceRequestAttachmentCompensation due(Long id, String businessKey) {
        ServiceRequestAttachmentCompensation row = new ServiceRequestAttachmentCompensation();
        row.setId(id);
        row.setTenantId("default");
        row.setServiceRequestId(10L);
        row.setCommentId(20L);
        row.setDocumentId(30L);
        row.setVisibility("INTERNAL");
        row.setFileName("a.pdf");
        row.setFileSize(100L);
        row.setBusinessKey(businessKey);
        row.setStatus("RETRY");
        row.setAttemptCount(1);
        row.setNextRetryAt(NOW.minusMinutes(1));
        return row;
    }
}
