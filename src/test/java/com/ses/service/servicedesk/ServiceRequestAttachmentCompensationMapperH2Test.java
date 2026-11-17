package com.ses.service.servicedesk;

import com.ses.entity.ServiceRequestAttachmentCompensation;
import com.ses.mapper.ServiceRequestAttachmentCompensationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-I18N review P3-5: 補償台帳の再試行対象SQL（{@code next_retry_at <= #{now}}）を実DB（H2）で実行する。
 * 基準時刻はDBの現在時刻ではなく呼出し側のClockの時刻（P3-N2-6）。他testの行と混ざらないよう専用tenantの行だけを見る。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ServiceRequestAttachmentCompensationMapperH2Test {

    @Autowired
    private ServiceRequestAttachmentCompensationMapper mapper;

    @Test
    void 再試行対象は渡した時刻以前のRETRY行だけで境界の時刻を含む() {
        String tenantId = "rb-i18n-compensation-" + UUID.randomUUID();
        // DBの現在時刻と無関係であることを示すため、遠い過去の時刻を基準にする。
        LocalDateTime now = LocalDateTime.of(2001, 2, 3, 4, 5, 6);
        Long past = insert(tenantId, "past", "RETRY", now.minusSeconds(1)).getId();
        Long boundary = insert(tenantId, "boundary", "RETRY", now).getId();
        insert(tenantId, "future", "RETRY", now.plusSeconds(1));
        insert(tenantId, "completed", "COMPLETED", now.minusMinutes(10));

        List<Long> due = mapper.selectDue(now, 1000).stream()
                .filter(row -> tenantId.equals(row.getTenantId()))
                .map(ServiceRequestAttachmentCompensation::getId)
                .toList();

        assertThat(due).containsExactly(past, boundary);
    }

    private ServiceRequestAttachmentCompensation insert(String tenantId, String key, String status, LocalDateTime nextRetryAt) {
        ServiceRequestAttachmentCompensation row = new ServiceRequestAttachmentCompensation();
        row.setTenantId(tenantId);
        row.setServiceRequestId(1L);
        row.setDocumentId(1L);
        row.setVisibility("INTERNAL");
        row.setFileName("a.pdf");
        row.setFileSize(1L);
        row.setBusinessKey(key);
        row.setStatus(status);
        row.setAttemptCount(1);
        row.setNextRetryAt(nextRetryAt);
        row.setCreatedAt(nextRetryAt);
        row.setUpdatedAt(nextRetryAt);
        mapper.insert(row);
        return row;
    }
}
