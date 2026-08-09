package com.ses.service.attendance;

import com.ses.common.exception.BusinessException;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.ApprovalRequest;
import com.ses.mapper.AttendanceMonthMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T070 R2-P1-05の理由必須・target version CAS・承認後遷移を直接回帰する。 */
@ExtendWith(MockitoExtension.class)
class AttendanceReopenApprovalAdapterTest {

    @Mock
    private AttendanceMonthMapper attendanceMonthMapper;

    @InjectMocks
    private AttendanceReopenApprovalAdapter adapter;

    @Test
    void 理由なし再open申請はfailClosed() {
        when(attendanceMonthMapper.selectById(10L)).thenReturn(month(10L, 3));

        assertThrows(BusinessException.class,
                () -> adapter.snapshot(10L, Map.of("reason", " ")));
    }

    @Test
    void 承認適用は締め済みとversionをCASする() {
        when(attendanceMonthMapper.selectById(10L)).thenReturn(month(10L, 3));
        when(attendanceMonthMapper.update(isNull(), any())).thenReturn(1);
        ApprovalRequest request = ApprovalRequest.builder().targetId(10L).targetVersion(3L).build();

        adapter.applyApproved(request);

        verify(attendanceMonthMapper).update(isNull(), any());
        assertEquals(3L, adapter.snapshot(10L, Map.of("reason", "訂正根拠")).targetVersion());
    }

    private AttendanceMonth month(Long id, int version) {
        AttendanceMonth month = AttendanceMonth.builder().engineerId(20L).organizationId(30L)
                .workMonth(java.time.LocalDate.of(2026, 8, 1)).status("締め済").version(version).build();
        month.setId(id);
        return month;
    }
}
