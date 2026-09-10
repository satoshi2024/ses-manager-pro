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
import com.ses.entity.ServiceRequestSequence;
import com.ses.entity.ServiceSlaClock;
import com.ses.entity.ServiceSlaPolicy;
import com.ses.entity.SysUser;
import com.ses.mapper.CustomerCsatMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.ServiceCommentMapper;
import com.ses.mapper.ServiceRequestMapper;
import com.ses.mapper.ServiceRequestSequenceMapper;
import com.ses.mapper.ServiceSlaClockMapper;
import com.ses.mapper.ServiceSlaPolicyMapper;
import com.ses.mapper.ServiceStateEventMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    @Autowired
    private ServiceRequestSequenceMapper sequenceMapper;

    @Autowired
    private ServiceSlaPolicyMapper slaPolicyMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    private Customer testCustomer;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
        testCustomer = new Customer();
        testCustomer.setTenantId("default");
        testCustomer.setCompanyName("株式会社テスト顧客CS");
        customerMapper.insert(testCustomer);
    }

    @AfterEach
    void clearTenantContext() {
        AccountingTenantContextHolder.clear();
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
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").reason("担当エンジニア調査開始").version(0).build(),
                100L, "INTERNAL_USER", "管理者");

        ServiceRequestDto d1 = serviceRequestService.getInternalDetail(reqId);
        assertEquals("IN_PROGRESS", d1.getStatus());
        assertNotNull(d1.getFirstResponseAt(), "初回応答日時が記録されていること");
        assertNotNull(d1.getCurrentSlaClock().getFirstRespondedAt());

        // 2. IN_PROGRESS -> WAITING_CUSTOMER (一時停止)
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("WAITING_CUSTOMER").reason("再現ログの提供待ち").version(1).build(),
                100L, "INTERNAL_USER", "管理者");

        ServiceRequestDto d2 = serviceRequestService.getInternalDetail(reqId);
        assertEquals("WAITING_CUSTOMER", d2.getStatus());
        assertEquals("PAUSED", d2.getCurrentSlaClock().getStatus());
        assertNotNull(d2.getCurrentSlaClock().getLastPausedAt());

        // 3. WAITING_CUSTOMER -> IN_PROGRESS (再開・SLA期限延長)
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").reason("顧客からログ受領").version(2).build(),
                100L, "INTERNAL_USER", "管理者");

        ServiceRequestDto d3 = serviceRequestService.getInternalDetail(reqId);
        assertEquals("IN_PROGRESS", d3.getStatus());
        assertEquals("RUNNING", d3.getCurrentSlaClock().getStatus());

        // 4. IN_PROGRESS -> RESOLVED (解決・SLA解決時計停止)
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("RESOLVED").reason("インデックス追加により負荷解消").version(3).build(),
                100L, "INTERNAL_USER", "管理者");

        ServiceRequestDto d4 = serviceRequestService.getInternalDetail(reqId);
        assertEquals("RESOLVED", d4.getStatus());
        assertEquals("COMPLETED", d4.getCurrentSlaClock().getStatus());
        assertNotNull(d4.getResolvedAt());
        assertNotNull(d4.getCurrentSlaClock().getResolvedAt());

        // 5. RESOLVED -> CLOSED (終了)
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("CLOSED").reason("顧客確認完了").version(4).build(),
                100L, "INTERNAL_USER", "管理者");

        ServiceRequestDto d5 = serviceRequestService.getInternalDetail(reqId);
        assertEquals("CLOSED", d5.getStatus());
        assertNotNull(d5.getClosedAt());

        // 6. CLOSED -> REOPENED (再オープン・新SLAラウンド作成)
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("REOPENED").reason("同一事象の再発").version(5).build(),
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
    @DisplayName("status変更のexpectedVersion省略はDB読込で補完せず400となり、副作用を残さないこと")
    void missingExpectedVersionIsRejectedBeforeStateChange() {
        ServiceRequest created = serviceRequestService.createRequest(ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("SYSTEM")
                .priority("P2")
                .subject("version必須テスト")
                .description("省略されたversionを補完しない")
                .build(), 100L, false, null);
        long initialEvents = stateEventMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.ServiceStateEvent>()
                .eq(com.ses.entity.ServiceStateEvent::getServiceRequestId, created.getId()));

        BusinessException missing = assertThrows(BusinessException.class, () ->
                serviceRequestService.changeStatus(created.getId(),
                        ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").build(),
                        100L, "INTERNAL_USER", "管理者"));

        assertEquals(400, missing.getCode());
        assertEquals("RECEIVED", serviceRequestService.getInternalDetail(created.getId()).getStatus());
        assertEquals(initialEvents, stateEventMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.ServiceStateEvent>()
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
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").version(0).build(),
                100L, "INTERNAL_USER", "管理者");
        serviceRequestService.changeStatus(created.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("WAITING_CUSTOMER").version(1).build(),
                100L, "INTERNAL_USER", "管理者");
        ServiceSlaClock paused = slaClockMapper.selectOne(new LambdaQueryWrapper<ServiceSlaClock>()
                .eq(ServiceSlaClock::getServiceRequestId, created.getId())
                .eq(ServiceSlaClock::getRoundNo, 1));
        assertEquals("PAUSED", paused.getStatus());
        assertNotNull(paused.getLastPausedAt());

        serviceRequestService.changeStatus(created.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("RESOLVED").version(2).build(),
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
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").version(0).build(),
                100L, "INTERNAL_USER", "営業担当");
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("WAITING_CUSTOMER").reason("送付先確認中").version(1).build(),
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
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").version(0).build(),
                100L, "INTERNAL_USER", "営業担当");
        serviceRequestService.changeStatus(reqId,
                ServiceRequestStatusChangeRequest.builder().toStatus("RESOLVED").version(1).build(),
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

    @Test
    @DisplayName("無効な流入チャネル（INVALID_CHANNEL）での起票が400拒否されること")
    void testCreateRequest_invalidChannel() {
        ServiceRequestCreateRequest req = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("CONTRACT")
                .priority("P1")
                .channel("INVALID_CHANNEL")
                .subject("チャネル不正テスト")
                .description("無効なチャネル指定")
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                serviceRequestService.createRequest(req, 100L, false, null));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("無効なチャネルです"));
    }

    @Test
    @DisplayName("ポータル起票時はチャネル指定が無視されてPORTALが強制され、担当者IDがnullに初期化されること")
    void testCreateRequest_portalChannelForced() {
        ServiceRequestCreateRequest req = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("SYSTEM")
                .priority("P2")
                .channel("PHONE")
                .ownerUserId(999L)
                .subject("ポータル経由の起票")
                .description("ポータルチャネル強制テスト")
                .build();

        ServiceRequest created = serviceRequestService.createRequest(req, 200L, true, null);
        assertNotNull(created.getId());
        assertEquals("PORTAL", created.getChannel(), "ポータル経由起票ではchannelがPORTALに強制されること");
        assertNull(created.getOwnerUserId(), "ポータル経由起票ではownerUserIdがnullに強制されること");
    }

    @Test
    @DisplayName("有効なSLAポリシーが存在しない場合（INACTIVE）に起票が400で拒否され、レコードがロールバックされること")
    void testCreateRequest_missingActivePolicyRollback() {
        ServiceSlaPolicy p0Policy = slaPolicyMapper.selectOne(
                new LambdaQueryWrapper<ServiceSlaPolicy>().eq(ServiceSlaPolicy::getPriority, "P0")
        );
        assertNotNull(p0Policy);
        p0Policy.setStatus("INACTIVE");
        slaPolicyMapper.updateById(p0Policy);

        ServiceRequestCreateRequest req = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("SYSTEM")
                .priority("P0")
                .subject("SLAポリシー未定義テスト")
                .description("フェイルクローズテスト")
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                serviceRequestService.createRequest(req, 100L, false, null));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("有効なSLAポリシー"));

        Long countReq = serviceRequestMapper.selectCount(
                new LambdaQueryWrapper<ServiceRequest>().eq(ServiceRequest::getSubject, "SLAポリシー未定義テスト")
        );
        assertEquals(0L, countReq, "リクエスト作成トランザクションがロールバックされレコードが残らないこと");
    }

    @Test
    @DisplayName("月次リクエスト採番が9999件上限で正常発行され、上限超過時に400拒否されること")
    void testCreateRequest_sequence9999BoundaryAndOverflow() {
        String currentMonth = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        sequenceMapper.insertInitialIfAbsent(currentMonth);
        sequenceMapper.updateCurrentVal(currentMonth, 9998);

        ServiceRequestCreateRequest req1 = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("SYSTEM")
                .priority("P2")
                .subject("9999件目テスト")
                .description("境界値テスト")
                .build();

        ServiceRequest created1 = serviceRequestService.createRequest(req1, 100L, false, null);
        assertEquals("REQ-" + currentMonth + "-9999", created1.getRequestNo());

        // 次の起票で9999件超過エラー(400)が発生すること
        ServiceRequestCreateRequest req2 = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("SYSTEM")
                .priority("P2")
                .subject("10000件目超過テスト")
                .description("上限超過テスト")
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                serviceRequestService.createRequest(req2, 100L, false, null));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("月間リクエスト採番上限（9999件）を超過しました"));
    }

    @Test
    @DisplayName("担当者指定の検証（存在性・有効性・社内対象ロール管理者/営業/マネージャーのみ許可）")
    void testCreateRequest_ownerUserValidation() {
        // 1. 存在しないユーザーID
        ServiceRequestCreateRequest reqNonexistent = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("SYSTEM")
                .priority("P2")
                .ownerUserId(888888L)
                .subject("担当者検証-存在しない")
                .description("テスト")
                .build();
        BusinessException ex1 = assertThrows(BusinessException.class, () ->
                serviceRequestService.createRequest(reqNonexistent, 100L, false, null));
        assertEquals(400, ex1.getCode());
        assertTrue(ex1.getMessage().contains("指定された担当ユーザーが存在しません"));

        // 2. 無効化されたユーザー (status = 0)
        SysUser inactiveUser = SysUser.builder()
                .username("inactive_sales")
                .password("pass123")
                .realName("無効営業")
                .role("営業")
                .status(0)
                .build();
        sysUserMapper.insert(inactiveUser);

        ServiceRequestCreateRequest reqInactive = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("SYSTEM")
                .priority("P2")
                .ownerUserId(inactiveUser.getId())
                .subject("担当者検証-無効ユーザー")
                .description("テスト")
                .build();
        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                serviceRequestService.createRequest(reqInactive, 100L, false, null));
        assertEquals(400, ex2.getCode());
        assertTrue(ex2.getMessage().contains("指定された担当ユーザーは無効です"));

        // 3. 対象外ロール (HR)
        SysUser hrUser = SysUser.builder()
                .username("hr_user")
                .password("pass123")
                .realName("人事担当")
                .role("HR")
                .status(1)
                .build();
        sysUserMapper.insert(hrUser);

        ServiceRequestCreateRequest reqHr = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("SYSTEM")
                .priority("P2")
                .ownerUserId(hrUser.getId())
                .subject("担当者検証-対象外ロール")
                .description("テスト")
                .build();
        BusinessException ex3 = assertThrows(BusinessException.class, () ->
                serviceRequestService.createRequest(reqHr, 100L, false, null));
        assertEquals(400, ex3.getCode());
        assertTrue(ex3.getMessage().contains("指定されたユーザーは内部担当者として設定できません"));

        // 4. 有効な営業担当者 (営業) -> 成功
        SysUser salesUser = SysUser.builder()
                .username("valid_sales")
                .password("pass123")
                .realName("有効営業")
                .role("営業")
                .status(1)
                .build();
        sysUserMapper.insert(salesUser);

        ServiceRequestCreateRequest reqValid = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("SYSTEM")
                .priority("P2")
                .ownerUserId(salesUser.getId())
                .subject("担当者検証-有効営業")
                .description("テスト")
                .build();
        ServiceRequest created = serviceRequestService.createRequest(reqValid, 100L, false, null);
        assertEquals(salesUser.getId(), created.getOwnerUserId());
    }
}
