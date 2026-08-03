package com.ses.service.impl;

import com.ses.entity.ApprovalParticipant;
import com.ses.entity.ApprovalRequest;
import com.ses.mapper.ApprovalParticipantMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.service.approval.ApprovalViewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** P1-08: 承認一覧の可視性をSQLで絞った後にページ境界を適用する回帰。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApprovalViewPageBoundaryTest {

    @Autowired ApprovalViewService viewService;
    @Autowired ApprovalRequestMapper requestMapper;
    @Autowired ApprovalParticipantMapper participantMapper;

    @Test
    void 可視参加者だけをSQLで絞ってからページングする() {
        long viewerId = 900_000_000L + Math.abs(System.nanoTime() % 100_000_000L);
        String prefix = "pb-" + System.nanoTime();
        LocalDateTime now = LocalDateTime.now();

        for (int index = 1; index <= 3; index++) {
            ApprovalRequest request = ApprovalRequest.builder()
                    .requestNo(prefix + "-" + index)
                    .requestType("test.page")
                    .targetType("TEST")
                    .targetId((long) index)
                    .targetVersion(1L)
                    .applicantId(viewerId + 100L)
                    .amountSnapshot(BigDecimal.valueOf(index))
                    .payloadJson("{}")
                    .routeSnapshotJson("{}")
                    .status("in_review")
                    .currentStep(1)
                    .roundNo(1)
                    .requestedAt(now.minusMinutes(index))
                    .version(1)
                    .build();
            requestMapper.insert(request);
            participantMapper.insert(ApprovalParticipant.builder()
                    .requestId(request.getId())
                    .userId(viewerId)
                    .participantRole("approver")
                    .roundNo(1)
                    .build());
        }

        ApprovalRequest invisible = ApprovalRequest.builder()
                .requestNo(prefix + "-invisible")
                .requestType("test.page")
                .targetType("TEST")
                .targetId(99L)
                .targetVersion(1L)
                .applicantId(viewerId + 200L)
                .amountSnapshot(BigDecimal.ZERO)
                .payloadJson("{}")
                .routeSnapshotJson("{}")
                .status("in_review")
                .currentStep(1)
                .roundNo(1)
                .requestedAt(now.plusMinutes(1))
                .version(1)
                .build();
        requestMapper.insert(invisible);

        var firstPage = viewService.list("all", null, 1, 2, viewerId, "営業", null);
        var secondPage = viewService.list("all", null, 2, 2, viewerId, "営業", null);

        assertEquals(3L, firstPage.total());
        assertEquals(2, firstPage.records().size());
        assertEquals(prefix + "-1", firstPage.records().get(0).requestNo());
        assertEquals(prefix + "-2", firstPage.records().get(1).requestNo());
        assertEquals(1, secondPage.records().size());
        assertEquals(prefix + "-3", secondPage.records().get(0).requestNo());
    }
}
