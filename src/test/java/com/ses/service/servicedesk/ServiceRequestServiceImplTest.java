package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.dto.portal.PortalCsatCreateRequest;
import com.ses.dto.portal.PortalServiceRequestDto;
import com.ses.dto.servicedesk.ServiceCommentCreateRequest;
import com.ses.dto.servicedesk.ServiceCommentDto;
import com.ses.dto.servicedesk.ServiceRequestCreateRequest;
import com.ses.dto.servicedesk.ServiceRequestDto;
import com.ses.dto.servicedesk.ServiceRequestStatusChangeRequest;
import com.ses.entity.Customer;
import com.ses.entity.CustomerCsat;
import com.ses.entity.ServiceComment;
import com.ses.entity.ServiceRequest;
import com.ses.entity.ServiceSlaClock;
import com.ses.entity.ServiceStateEvent;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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

        ServiceRequest created = serviceRequestService.createRequest(req, 1L, false, null);

        assertNotNull(created.getId());
        assertTrue(created.getRequestNo().startsWith("REQ-"));
        assertEquals("RECEIVED", created.getStatus());
        assertEquals(0, created.getReopenCount());

        // SLA Clock が round_no=1 で初期化されていること
        ServiceSlaClock clock = slaClockMapper.selectOne(
                new LambdaQueryWrapper<ServiceSlaClock>()
                        .eq(ServiceSlaClock::getServiceRequestId, created.getId())
                        .eq(ServiceSlaClock::getRoundNo, 1)
        );
        assertNotNull(clock);
        assertNotNull(clock.getResponseDeadline());
        assertNotNull(clock.getResolveDeadline());
        assertEquals("RUNNING", clock.getStatus());

        // 監査イベントが記録されていること
        List<ServiceStateEvent> events = stateEventMapper.selectList(
                new LambdaQueryWrapper<ServiceStateEvent>()
                        .eq(ServiceStateEvent::getServiceRequestId, created.getId())
        );
        assertEquals(1, events.size());
        assertEquals("RECEIVED", events.get(0).getToStatus());
    }

    @Test
    @DisplayName("ステータス遷移のフルサイクル（RECEIVED -> IN_PROGRESS -> WAITING_CUSTOMER -> IN_PROGRESS -> RESOLVED -> CLOSED -> REOPENED -> IN_PROGRESS）")
    void testStatusTransitionFullCycle() {
        ServiceRequestCreateRequest req = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("SYSTEM")
                .priority("P0")
                .subject("システム障害の報告")
                .description("ログインができない")
                .build();
        ServiceRequest created = serviceRequestService.createRequest(req, 1L, false, null);
        Long reqId = created.getId();

        // 1. RECEIVED -> IN_PROGRESS (着手・初回応答記録)
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").reason("対応着手").build(),
                1L, "INTERNAL_USER", "管理者");

        ServiceRequestDto d1 = serviceRequestService.getInternalDetail(reqId);
        assertEquals("IN_PROGRESS", d1.getStatus());
        assertNotNull(d1.getFirstResponseAt(), "初回応答日時が記録されること");
        assertNotNull(d1.getCurrentSlaClock().getFirstRespondedAt());

        // 2. IN_PROGRESS -> WAITING_CUSTOMER (顧客待ち・SLA一時停止)
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("WAITING_CUSTOMER").reason("追加情報ヒアリング").build(),
                1L, "INTERNAL_USER", "管理者");

        ServiceRequestDto d2 = serviceRequestService.getInternalDetail(reqId);
        assertEquals("WAITING_CUSTOMER", d2.getStatus());
        assertEquals("PAUSED", d2.getCurrentSlaClock().getStatus());
        assertNotNull(d2.getCurrentSlaClock().getLastPausedAt());

        // 3. WAITING_CUSTOMER -> IN_PROGRESS (顧客返信による再開・SLA時計再開と期限延長)
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").reason("顧客返信受信").build(),
                1L, "INTERNAL_USER", "管理者");

        ServiceRequestDto d3 = serviceRequestService.getInternalDetail(reqId);
        assertEquals("IN_PROGRESS", d3.getStatus());
        assertEquals("RUNNING", d3.getCurrentSlaClock().getStatus());
        assertNull(d3.getCurrentSlaClock().getLastPausedAt());

        // 4. IN_PROGRESS -> RESOLVED (解決・SLA完了)
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("RESOLVED").reason("原因特定し修正完了").build(),
                1L, "INTERNAL_USER", "管理者");

        ServiceRequestDto d4 = serviceRequestService.getInternalDetail(reqId);
        assertEquals("RESOLVED", d4.getStatus());
        assertNotNull(d4.getResolvedAt());
        assertEquals("COMPLETED", d4.getCurrentSlaClock().getStatus());

        // 5. RESOLVED -> CLOSED (終了)
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("CLOSED").reason("顧客確認完了クローズ").build(),
                1L, "INTERNAL_USER", "管理者");

        ServiceRequestDto d5 = serviceRequestService.getInternalDetail(reqId);
        assertEquals("CLOSED", d5.getStatus());
        assertNotNull(d5.getClosedAt());

        // 6. CLOSED -> REOPENED (再オープン・新SLAラウンド作成)
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("REOPENED").reason("同一事象の再発").build(),
                1L, "INTERNAL_USER", "管理者");

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
    @DisplayName("内部メモ（INTERNAL）がポータルDTOから完全に除外されること")
    void testCommentVisibilitySeparation() {
        ServiceRequestCreateRequest req = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("QUALITY")
                .priority("P2")
                .subject("納品物の品質について")
                .description("成果物のフォーマット確認")
                .build();
        ServiceRequest created = serviceRequestService.createRequest(req, 1L, false, null);
        Long reqId = created.getId();

        // 内部メモ投稿
        serviceRequestService.addComment(reqId,
                ServiceCommentCreateRequest.builder().commentText("社内共有: 次回請求で割引を検討").visibility("INTERNAL").build(),
                1L, "INTERNAL_USER", "営業マネージャー", false);

        // ポータル公開コメント投稿
        serviceRequestService.addComment(reqId,
                ServiceCommentCreateRequest.builder().commentText("ご指摘ありがとうございます。修正版を準備中です。").visibility("PORTAL_VISIBLE").build(),
                1L, "INTERNAL_USER", "営業担当", false);

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
        ServiceRequest created = serviceRequestService.createRequest(req, 1L, false, null);
        Long reqId = created.getId();

        // WAITING_CUSTOMER に変更
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").build(),
                1L, "INTERNAL_USER", "営業担当");
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("WAITING_CUSTOMER").reason("送付先確認中").build(),
                1L, "INTERNAL_USER", "営業担当");

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
        ServiceRequest created = serviceRequestService.createRequest(req, 1L, false, null);
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
                1L, "INTERNAL_USER", "営業担当");
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("RESOLVED").build(),
                1L, "INTERNAL_USER", "営業担当");

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
        ServiceRequest created = serviceRequestService.createRequest(req, 1L, false, null);
        Long reqId = created.getId();

        Long otherCustomerId = testCustomer.getId() + 999L;

        // 他社顧客IDでポータル詳細を取得しようとすると 404 拒否されること
        assertThrows(BusinessException.class, () ->
                serviceRequestService.getPortalDetail(reqId, otherCustomerId));
    }
}
