package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.portal.PortalCsatCreateRequest;
import com.ses.dto.portal.PortalServiceRequestDto;
import com.ses.dto.servicedesk.ServiceCommentCreateRequest;
import com.ses.dto.servicedesk.ServiceRequestCreateRequest;
import com.ses.dto.servicedesk.ServiceRequestDto;
import com.ses.dto.servicedesk.ServiceRequestStatusChangeRequest;
import com.ses.entity.Customer;
import com.ses.entity.CustomerCsat;
import com.ses.entity.ServiceRequest;
import com.ses.entity.ServiceSlaClock;
import com.ses.mapper.CustomerCsatMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.ServiceCommentMapper;
import com.ses.mapper.ServiceRequestMapper;
import com.ses.mapper.ServiceSlaClockMapper;
import com.ses.mapper.ServiceStateEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ServiceRequestServiceImplTest {

    @Autowired
    private ServiceRequestService serviceRequestService;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private ServiceRequestMapper serviceRequestMapper;

    @Autowired
    private ServiceSlaClockMapper slaClockMapper;

    @Autowired
    private ServiceCommentMapper commentMapper;

    @Autowired
    private ServiceStateEventMapper stateEventMapper;

    @Autowired
    private CustomerCsatMapper csatMapper;

    private Customer testCustomer;

    @BeforeEach
    void setUp() {
        testCustomer = new Customer();
        testCustomer.setCompanyName("株式会社テスト顧客CS");
        customerMapper.insert(testCustomer);
    }

    @Test
    @DisplayName("リクエストの新規起票とSLA時計の初期化・採番ができること")
    void testCreateRequest() {
        ServiceRequestCreateRequest req = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("CONTRACT")
                .priority("P1")
                .subject("契約期間の変更について")
                .description("来期からの契約期間の変更希望")
                .build();

        ServiceRequest created = serviceRequestService.createRequest(req, 100L, false, null);

        assertNotNull(created.getId());
        assertTrue(created.getRequestNo().startsWith("REQ-"));
        assertEquals("RECEIVED", created.getStatus());
        assertEquals(0, created.getReopenCount());

        ServiceSlaClock clock = slaClockMapper.selectOne(
                new LambdaQueryWrapper<ServiceSlaClock>()
                        .eq(ServiceSlaClock::getServiceRequestId, created.getId())
                        .eq(ServiceSlaClock::getRoundNo, 1)
        );
        assertNotNull(clock);
        assertNotNull(clock.getResponseDeadline());
        assertNotNull(clock.getResolveDeadline());
        assertEquals("RUNNING", clock.getStatus());
    }

    @Test
    @DisplayName("ステータス遷移（RECEIVED -> IN_PROGRESS -> WAITING_CUSTOMER -> IN_PROGRESS -> RESOLVED -> CLOSED -> REOPENED）とSLA時計制御")
    void testStatusTransitionAndSlaClocks() {
        ServiceRequestCreateRequest req = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("SYSTEM")
                .priority("P0")
                .subject("本番DB高負荷")
                .description("CPU使用率が100%に達しています")
                .build();
        ServiceRequest created = serviceRequestService.createRequest(req, 100L, false, null);
        Long reqId = created.getId();

        // 1. RECEIVED -> IN_PROGRESS (初回応答)
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").reason("担当エンジニア調査開始").build(),
                100L, "INTERNAL_USER", "管理者");

        ServiceRequestDto d1 = serviceRequestService.getInternalDetail(reqId);
        assertEquals("IN_PROGRESS", d1.getStatus());
        assertNotNull(d1.getFirstResponseAt(), "初回応答日時が記録されていること");
        assertNotNull(d1.getCurrentSlaClock().getFirstRespondedAt());

        // 2. IN_PROGRESS -> WAITING_CUSTOMER (一時停止)
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("WAITING_CUSTOMER").reason("再現ログの提供待ち").build(),
                100L, "INTERNAL_USER", "管理者");

        ServiceRequestDto d2 = serviceRequestService.getInternalDetail(reqId);
        assertEquals("WAITING_CUSTOMER", d2.getStatus());
        assertEquals("PAUSED", d2.getCurrentSlaClock().getStatus());
        assertNotNull(d2.getCurrentSlaClock().getLastPausedAt());

        // 3. WAITING_CUSTOMER -> IN_PROGRESS (再開・SLA期限延長)
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").reason("顧客からログ受領").build(),
                100L, "INTERNAL_USER", "管理者");

        ServiceRequestDto d3 = serviceRequestService.getInternalDetail(reqId);
        assertEquals("IN_PROGRESS", d3.getStatus());
        assertEquals("RUNNING", d3.getCurrentSlaClock().getStatus());

        // 4. IN_PROGRESS -> RESOLVED (解決・SLA解決時計停止)
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("RESOLVED").reason("インデックス追加により負荷解消").build(),
                100L, "INTERNAL_USER", "管理者");

        ServiceRequestDto d4 = serviceRequestService.getInternalDetail(reqId);
        assertEquals("RESOLVED", d4.getStatus());
        assertEquals("COMPLETED", d4.getCurrentSlaClock().getStatus());
        assertNotNull(d4.getResolvedAt());
        assertNotNull(d4.getCurrentSlaClock().getResolvedAt());

        // 5. RESOLVED -> CLOSED (終了)
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("CLOSED").reason("顧客確認完了").build(),
                100L, "INTERNAL_USER", "管理者");

        ServiceRequestDto d5 = serviceRequestService.getInternalDetail(reqId);
        assertEquals("CLOSED", d5.getStatus());
        assertNotNull(d5.getClosedAt());

        // 6. CLOSED -> REOPENED (再オープン・新SLAラウンド作成)
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("REOPENED").reason("同一事象の再発").build(),
                100L, "INTERNAL_USER", "管理者");

        ServiceRequestDto d6 = serviceRequestService.getInternalDetail(reqId);
        assertEquals("IN_PROGRESS", d6.getStatus(), "再オープン後は自動的に IN_PROGRESS になること");
        assertEquals(1, d6.getReopenCount());
        assertEquals(2, d6.getCurrentSlaClock().getRoundNo(), "新しいラウンド2のSLA時計が作成されること");
        assertEquals("RUNNING", d6.getCurrentSlaClock().getStatus());

        // 過去のラウンド1のSLA時計がそのまま保持されていること
        ServiceSlaClock clockRound1 = slaClockMapper.selectOne(
                new LambdaQueryWrapper<ServiceSlaClock>()
                        .eq(ServiceSlaClock::getServiceRequestId, reqId)
                        .eq(ServiceSlaClock::getRoundNo, 1)
        );
        assertNotNull(clockRound1);
        assertEquals("COMPLETED", clockRound1.getStatus());
        assertNotNull(clockRound1.getResolvedAt());
    }

    @Test
    @DisplayName("許可されていない状態遷移とstale versionは409で拒否され、状態イベントを追加しないこと")
    void testStatusTransitionMatrixAndCas() {
        ServiceRequest created = serviceRequestService.createRequest(ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("SYSTEM")
                .priority("P2")
                .subject("状態競合テスト")
                .description("状態機械とCASの検証")
                .build(), 100L, false, null);

        long initialEvents = stateEventMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.ServiceStateEvent>()
                .eq(com.ses.entity.ServiceStateEvent::getServiceRequestId, created.getId()));

        BusinessException invalid = assertThrows(BusinessException.class, () ->
                serviceRequestService.changeStatus(created.getId(),
                        ServiceRequestStatusChangeRequest.builder().toStatus("RESOLVED").version(0).build(),
                        100L, "INTERNAL_USER", "管理者"));
        assertEquals(409, invalid.getCode());
        assertEquals(initialEvents, stateEventMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.ServiceStateEvent>()
                .eq(com.ses.entity.ServiceStateEvent::getServiceRequestId, created.getId())));

        serviceRequestService.changeStatus(created.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").version(0).build(),
                100L, "INTERNAL_USER", "管理者");
        long afterWinnerEvents = stateEventMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.ServiceStateEvent>()
                .eq(com.ses.entity.ServiceStateEvent::getServiceRequestId, created.getId()));

        BusinessException stale = assertThrows(BusinessException.class, () ->
                serviceRequestService.changeStatus(created.getId(),
                        ServiceRequestStatusChangeRequest.builder().toStatus("WAITING_CUSTOMER").version(0).build(),
                        100L, "INTERNAL_USER", "管理者"));
        assertEquals(409, stale.getCode());
        assertEquals(afterWinnerEvents, stateEventMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.ServiceStateEvent>()
                .eq(com.ses.entity.ServiceStateEvent::getServiceRequestId, created.getId())));
    }

    @Test
    @DisplayName("WAITING_CUSTOMERから直接解決しても停止区間を営業分で精算すること")
    void testWaitingCustomerDirectResolveClosesPauseInterval() {
        ServiceRequest created = serviceRequestService.createRequest(ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("SYSTEM")
                .priority("P2")
                .subject("停止区間精算テスト")
                .description("解決遷移時のSLA停止精算")
                .build(), 100L, false, null);

        serviceRequestService.changeStatus(created.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").build(),
                100L, "INTERNAL_USER", "管理者");
        serviceRequestService.changeStatus(created.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("WAITING_CUSTOMER").build(),
                100L, "INTERNAL_USER", "管理者");
        ServiceSlaClock paused = slaClockMapper.selectOne(new LambdaQueryWrapper<ServiceSlaClock>()
                .eq(ServiceSlaClock::getServiceRequestId, created.getId())
                .eq(ServiceSlaClock::getRoundNo, 1));
        assertEquals("PAUSED", paused.getStatus());
        assertNotNull(paused.getLastPausedAt());

        serviceRequestService.changeStatus(created.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("RESOLVED").build(),
                100L, "INTERNAL_USER", "管理者");

        ServiceSlaClock completed = slaClockMapper.selectById(paused.getId());
        assertEquals("COMPLETED", completed.getStatus());
        assertNull(completed.getLastPausedAt());
        assertNotNull(completed.getTotalPauseMinutes());
    }

    @Test
    @DisplayName("内部メモ（INTERNAL）がポータルDTOから完全に除外されること")
    void testCommentVisibilitySeparation() {
        ServiceRequestCreateRequest req = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("QUALITY")
                .priority("P2")
                .subject("納品物の品質について")
                .description("成果物のフォーマット確認")
                .build();
        ServiceRequest created = serviceRequestService.createRequest(req, 100L, false, null);
        Long reqId = created.getId();

        // 内部メモ投稿
        serviceRequestService.addComment(reqId,
                ServiceCommentCreateRequest.builder().commentText("社内共有: 次回請求で割引を検討").visibility("INTERNAL").build(),
                100L, "INTERNAL_USER", "営業マネージャー", false);

        // ポータル公開コメント投稿
        serviceRequestService.addComment(reqId,
                ServiceCommentCreateRequest.builder().commentText("ご指摘ありがとうございます。修正版を準備中です。").visibility("PORTAL_VISIBLE").build(),
                100L, "INTERNAL_USER", "営業担当", false);

        // 内部詳細では両方のコメントが取得できる
        ServiceRequestDto internalDto = serviceRequestService.getInternalDetail(reqId);
        assertEquals(2, internalDto.getComments().size());

        // ポータル詳細では PORTAL_VISIBLE のみ取得でき、INTERNALメモは含まれない
        PortalServiceRequestDto portalDto = serviceRequestService.getPortalDetail(reqId, testCustomer.getId());
        assertEquals(1, portalDto.getComments().size());
        assertEquals("ご指摘ありがとうございます。修正版を準備中です。", portalDto.getComments().get(0).getCommentText());
    }

    @Test
    @DisplayName("顧客ポータルからの返信時に WAITING_CUSTOMER が自動的に IN_PROGRESS に復帰すること")
    void testPortalReplyResumesWaitingCustomer() {
        ServiceRequestCreateRequest req = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("BILLING")
                .priority("P2")
                .subject("請求書再発行依頼")
                .description("先月分の請求書PDFを再送してほしい")
                .build();
        ServiceRequest created = serviceRequestService.createRequest(req, 100L, false, null);
        Long reqId = created.getId();

        // WAITING_CUSTOMER に変更
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").build(),
                100L, "INTERNAL_USER", "営業担当");
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("WAITING_CUSTOMER").reason("送付先確認中").build(),
                100L, "INTERNAL_USER", "営業担当");

        assertEquals("WAITING_CUSTOMER", serviceRequestService.getInternalDetail(reqId).getStatus());

        // ポータル利用者からの返信コメント
        serviceRequestService.addComment(reqId,
                ServiceCommentCreateRequest.builder().commentText("送付先アドレスは billing@example.com です。").build(),
                200L, "PORTAL_USER", "顧客担当者B", true);

        // 自動的に IN_PROGRESS へ復帰していること
        ServiceRequestDto updated = serviceRequestService.getInternalDetail(reqId);
        assertEquals("IN_PROGRESS", updated.getStatus());
        assertEquals("RUNNING", updated.getCurrentSlaClock().getStatus());
    }

    @Test
    @DisplayName("CSAT評価回答が解決後1回のみ可能で、二重回答が409拒否されること")
    void testCsatSubmissionAndDuplicateGuard() {
        ServiceRequestCreateRequest req = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("OTHER")
                .priority("P3")
                .subject("お問い合わせ")
                .description("仕様についての質問")
                .build();
        ServiceRequest created = serviceRequestService.createRequest(req, 100L, false, null);
        Long reqId = created.getId();

        // 未解決状態でのCSATは拒否 (400)
        PortalCsatCreateRequest csatReq = PortalCsatCreateRequest.builder()
                .score(5)
                .feedbackComment("大変満足です")
                .build();
        assertThrows(BusinessException.class, () ->
                serviceRequestService.submitCsat(reqId, csatReq, testCustomer.getId(), 200L));

        // 解決状態へ遷移
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").build(),
                100L, "INTERNAL_USER", "営業担当");
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("RESOLVED").build(),
                100L, "INTERNAL_USER", "営業担当");

        // 1回目のCSAT回答は成功
        serviceRequestService.submitCsat(reqId, csatReq, testCustomer.getId(), 200L);

        CustomerCsat csat = csatMapper.selectOne(
                new LambdaQueryWrapper<CustomerCsat>().eq(CustomerCsat::getServiceRequestId, reqId)
        );
        assertNotNull(csat);
        assertEquals(5, csat.getScore());
        assertEquals("大変満足です", csat.getFeedbackComment());

        // 2回目のCSAT回答は二重回答として 409 拒否されること
        BusinessException ex = assertThrows(BusinessException.class, () ->
                serviceRequestService.submitCsat(reqId, csatReq, testCustomer.getId(), 200L));
        assertEquals(409, ex.getCode());
    }

    @Test
    @DisplayName("他社顧客のリクエスト詳細アクセスが404拒否されること (IDOR防止)")
    void testOtherCustomerAccessDenied() {
        ServiceRequestCreateRequest req = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("CONTRACT")
                .priority("P1")
                .subject("顧客Aのリクエスト")
                .description("機密内容")
                .build();
        ServiceRequest created = serviceRequestService.createRequest(req, 100L, false, null);
        Long reqId = created.getId();

        Long otherCustomerId = testCustomer.getId() + 999L;

        // 他社顧客IDでポータル詳細を取得しようとすると 404 拒否されること
        assertThrows(BusinessException.class, () ->
                serviceRequestService.getPortalDetail(reqId, otherCustomerId));
    }
}
