package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.dto.portal.PortalCsatCreateRequest;
import com.ses.dto.portal.PortalServiceRequestDto;
import com.ses.dto.servicedesk.ServiceCommentCreateRequest;
import com.ses.dto.servicedesk.ServiceRequestCreateRequest;
import com.ses.dto.servicedesk.ServiceRequestDto;
import com.ses.dto.servicedesk.ServiceRequestStatusChangeRequest;
import com.ses.entity.Customer;
import com.ses.entity.ServiceRequest;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.ServiceRequestMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 公開開始時刻が未設定（本番の既定）のとき、portalの全取得経路が社内登録の問い合わせを出さず、
 * 内部サービスデスクの挙動は変わらないことを実DB（H2）・実serviceで確認する。
 *
 * <p>test profileは既存portalテストのため公開開始時刻を2000年に設定しているので、このクラスだけ空に上書きする
 * （専用のcontextになる）。分離は {@code @Transactional} のrollbackで行い、自分が登録した行だけを参照する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = PortalServiceRequestVisibilityPolicy.VISIBLE_SINCE_PROPERTY + "=")
class PortalServiceRequestVisibilityIntegrationTest {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @Autowired
    private ServiceRequestService serviceRequestService;
    @Autowired
    private ServiceRequestAttachmentService attachmentService;
    @Autowired
    private ServiceRequestMapper serviceRequestMapper;
    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private PortalServiceRequestVisibilityPolicy policy;

    private Customer customer;
    private ServiceRequest internalRequest;
    private ServiceRequest portalRequest;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
        customer = new Customer();
        customer.setTenantId("default");
        customer.setCompanyName("公開範囲テスト顧客-" + UUID.randomUUID().toString().substring(0, 8));
        customerMapper.insert(customer);

        // 初回リリース中に社内で代理起票した問い合わせ（channelにPORTALを指定しても社内起票のまま）
        internalRequest = serviceRequestService.createRequest(request("社内で代理起票", "PORTAL"), 100L, false, null);
        // portalから起票された問い合わせ
        portalRequest = serviceRequestService.createRequest(request("portalから起票", null), 100L, true, 200L);
    }

    @AfterEach
    void clearTenantContext() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 公開開始時刻が未設定ならcontextのpolicyはportal起票のみになる() {
        assertThat(policy.visibleSince()).isEmpty();
        assertThat(PortalServiceRequestVisibilityPolicy.isPortalOriginated(portalRequest)).isTrue();
        assertThat(PortalServiceRequestVisibilityPolicy.isPortalOriginated(internalRequest)).isFalse();
    }

    @Test
    void portalの一覧は社内登録の問い合わせを出さず件数もportal起票だけを数える() {
        Page<PortalServiceRequestDto> page = serviceRequestService.searchPortalRequests(1, 50, null, null,
                customer.getId());

        assertThat(page.getRecords()).extracting(PortalServiceRequestDto::getId).containsExactly(portalRequest.getId());
        assertThat(page.getTotal()).isEqualTo(1);
        // keyword・status条件と組み合わせても社内登録分は出ない（ORで公開範囲を打ち消さない）。
        assertThat(serviceRequestService.searchPortalRequests(1, 50, "社内", null, customer.getId()).getRecords())
                .isEmpty();
        assertThat(serviceRequestService.searchPortalRequests(1, 50, null, "RECEIVED", customer.getId())
                .getRecords()).extracting(PortalServiceRequestDto::getId).containsExactly(portalRequest.getId());
    }

    @Test
    void portalの詳細と返信は社内登録の問い合わせを他社と同じ404にする() {
        assertThat(serviceRequestService.getPortalDetail(portalRequest.getId(), customer.getId()).getId())
                .isEqualTo(portalRequest.getId());
        assertNotFound(() -> serviceRequestService.getPortalDetail(internalRequest.getId(), customer.getId()));

        ServiceCommentCreateRequest reply = ServiceCommentCreateRequest.builder().commentText("顧客からの返信").build();
        assertNotFound(() -> serviceRequestService.addComment(internalRequest.getId(), reply, 200L, "PORTAL_USER",
                "顧客担当者", true));
        assertThat(serviceRequestService.addComment(portalRequest.getId(), reply, 200L, "PORTAL_USER",
                "顧客担当者", true).getVisibility()).isEqualTo("PORTAL_VISIBLE");
    }

    @Test
    void portalのCSATは解決済みでも社内登録の問い合わせには回答できない() {
        resolve(internalRequest);
        resolve(portalRequest);
        PortalCsatCreateRequest csat = PortalCsatCreateRequest.builder().score(5).feedbackComment("満足").build();

        assertNotFound(() -> serviceRequestService.submitCsat(internalRequest.getId(), csat, customer.getId(), 200L));
        serviceRequestService.submitCsat(portalRequest.getId(), csat, customer.getId(), 200L);
    }

    @Test
    void portalの添付uploadは社内登録の問い合わせを文書登録前に404にする() {
        assertNotFound(() -> attachmentService.uploadPortal(internalRequest.getId(), null,
                new MockMultipartFile("file", "reply.pdf", "application/pdf", "PDF".getBytes()),
                customer.getId(), 200L));
    }

    @Test
    void 内部サービスデスクは社内登録とportal起票の両方を従来どおり扱える() {
        Page<ServiceRequestDto> internalList = serviceRequestService.searchInternalRequests(1, 50, null, null, null,
                null, customer.getId());
        assertThat(internalList.getRecords()).extracting(ServiceRequestDto::getId)
                .containsExactlyInAnyOrder(internalRequest.getId(), portalRequest.getId());

        assertThat(serviceRequestService.getInternalDetail(internalRequest.getId()).getId())
                .isEqualTo(internalRequest.getId());
        assertThat(serviceRequestService.addComment(internalRequest.getId(),
                ServiceCommentCreateRequest.builder().commentText("社内メモ").visibility("INTERNAL").build(),
                100L, "INTERNAL_USER", "担当", false).getVisibility()).isEqualTo("INTERNAL");
        resolve(internalRequest);
        assertThat(serviceRequestService.getInternalDetail(internalRequest.getId()).getStatus()).isEqualTo("RESOLVED");
    }

    @Test
    void 公開開始時刻を設定したpolicyはその時刻以降に作成された社内登録分だけを一覧条件に加える() {
        // 同じ顧客に、公開開始前と公開開始後に作成された社内登録の問い合わせを用意する。
        ServiceRequest before = internalRequest;
        ServiceRequest after = serviceRequestService.createRequest(request("公開開始後に社内登録", null), 100L, false, null);
        setCreatedAt(before.getId(), LocalDateTime.of(2027, 3, 31, 23, 59, 59));
        setCreatedAt(after.getId(), LocalDateTime.of(2027, 4, 1, 0, 0));
        setCreatedAt(portalRequest.getId(), LocalDateTime.of(2026, 12, 1, 9, 0));
        PortalServiceRequestVisibilityPolicy since =
                new PortalServiceRequestVisibilityPolicy(OffsetDateTime.parse("2027-04-01T00:00:00+09:00"));

        LambdaQueryWrapper<ServiceRequest> wrapper = new LambdaQueryWrapper<ServiceRequest>()
                .eq(ServiceRequest::getTenantId, "default")
                .eq(ServiceRequest::getCustomerId, customer.getId());
        since.restrict(wrapper, TOKYO);
        List<Long> visibleIds = serviceRequestMapper.selectList(wrapper).stream().map(ServiceRequest::getId).toList();

        assertThat(visibleIds).containsExactlyInAnyOrder(after.getId(), portalRequest.getId());
        // 一覧条件と単票判定が一致する（詳細・返信・CSAT・添付も同じ判定を使う）。
        for (ServiceRequest row : serviceRequestMapper.selectList(new LambdaQueryWrapper<ServiceRequest>()
                .eq(ServiceRequest::getCustomerId, customer.getId()))) {
            assertThat(since.isVisible(row, TOKYO)).as("request %s", row.getId())
                    .isEqualTo(visibleIds.contains(row.getId()));
        }
    }

    private ServiceRequestCreateRequest request(String subject, String channel) {
        return ServiceRequestCreateRequest.builder()
                .customerId(customer.getId())
                .category("OTHER")
                .priority("P3")
                .channel(channel)
                .subject(subject)
                .description(subject + "の内容")
                .build();
    }

    private void resolve(ServiceRequest request) {
        int version = serviceRequestMapper.selectById(request.getId()).getVersion();
        serviceRequestService.changeStatus(request.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").version(version).build(),
                100L, "INTERNAL_USER", "担当");
        serviceRequestService.changeStatus(request.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("RESOLVED").reason("回答済み")
                        .version(version + 1).build(),
                100L, "INTERNAL_USER", "担当");
    }

    private void setCreatedAt(Long id, LocalDateTime createdAt) {
        serviceRequestMapper.update(null, new LambdaUpdateWrapper<ServiceRequest>()
                .eq(ServiceRequest::getId, id)
                .set(ServiceRequest::getCreatedAt, createdAt));
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(404));
    }
}
