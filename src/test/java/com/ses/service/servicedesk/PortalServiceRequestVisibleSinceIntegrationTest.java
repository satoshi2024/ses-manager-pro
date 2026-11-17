package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.portal.PortalServiceRequestDto;
import com.ses.dto.servicedesk.ServiceCommentCreateRequest;
import com.ses.dto.servicedesk.ServiceRequestCreateRequest;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 設定済み公開開始時刻を実serviceの一覧・単票・返信に適用する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = PortalServiceRequestVisibilityPolicy.VISIBLE_SINCE_PROPERTY
        + "=2027-03-31T15:00:00Z")
class PortalServiceRequestVisibleSinceIntegrationTest {
    private static final LocalDateTime SINCE = LocalDateTime.of(2027, 4, 1, 0, 0);
    @Autowired private ServiceRequestService service;
    @Autowired private ServiceRequestMapper requests;
    @Autowired private CustomerMapper customers;
    private Customer customer;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
        customer = customer();
    }

    @AfterEach
    void clearContext() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 内部起票は公開開始の直前を隠し同時刻と直後を表示しportal起票は過去も表示する() {
        ServiceRequest before = request(customer, false, SINCE.minusSeconds(1));
        ServiceRequest boundary = request(customer, false, SINCE);
        ServiceRequest after = request(customer, false, SINCE.plusSeconds(1));
        ServiceRequest portal = request(customer, true, SINCE.minusYears(1));

        var page = service.searchPortalRequests(1, 50, null, null, customer.getId());
        assertThat(page.getTotal()).isEqualTo(3);
        assertThat(page.getRecords()).extracting(PortalServiceRequestDto::getId)
                .containsExactlyInAnyOrder(boundary.getId(), after.getId(), portal.getId());
        assertNotFound(() -> service.getPortalDetail(before.getId(), customer.getId()));
        for (ServiceRequest visible : new ServiceRequest[]{boundary, after, portal}) {
            assertThat(service.getPortalDetail(visible.getId(), customer.getId()).getId()).isEqualTo(visible.getId());
        }
        ServiceCommentCreateRequest comment = ServiceCommentCreateRequest.builder().commentText("公開後の返信").build();
        assertNotFound(() -> service.addComment(before.getId(), comment, 200L, "PORTAL_USER", "担当", true));
        assertThat(service.addComment(boundary.getId(), comment, 200L, "PORTAL_USER", "担当", true).getVisibility())
                .isEqualTo("PORTAL_VISIBLE");
    }

    @Test
    void 公開条件のORは顧客とtenant境界を緩めず他社や他tenantの単票を404にする() {
        ServiceRequest own = request(customer, false, SINCE);
        Customer other = customer();
        ServiceRequest otherInternal = request(other, false, SINCE.plusDays(1));
        ServiceRequest otherPortal = request(other, true, SINCE.minusDays(1));
        ServiceRequest foreign = request(customer, true, SINCE.plusDays(1));
        requests.update(null, new LambdaUpdateWrapper<ServiceRequest>().eq(ServiceRequest::getId, foreign.getId())
                .set(ServiceRequest::getTenantId, "portal-boundary-" + UUID.randomUUID()));

        var page = service.searchPortalRequests(1, 50, null, null, customer.getId());
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getRecords()).extracting(PortalServiceRequestDto::getId).containsExactly(own.getId());
        for (ServiceRequest hidden : new ServiceRequest[]{otherInternal, otherPortal, foreign}) {
            assertNotFound(() -> service.getPortalDetail(hidden.getId(), customer.getId()));
        }
        assertThat(service.searchPortalRequests(1, 50, null, null, other.getId()).getRecords())
                .extracting(PortalServiceRequestDto::getId)
                .containsExactlyInAnyOrder(otherInternal.getId(), otherPortal.getId());
    }

    @Test
    void サービス境界はクライアント指定の組織日暦を403で拒否する() {
        ServiceDeskExecutionContext context = new ServiceDeskExecutionContext("default", java.time.ZoneId.of("Asia/Tokyo"),
                java.time.Instant.parse("2026-08-31T00:00:00Z"), 7L, null, 100L, "INTERNAL_USER", "検証担当", "TEST");
        assertThatThrownBy(() -> service.bindCalendarScope(context, customer.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(403));
        ServiceDeskExecutionContext accepted = service.bindCalendarScope(new ServiceDeskExecutionContext("default",
                context.zoneId(), context.occurredAt(), null, null, 100L, "INTERNAL_USER", "検証担当", "TEST"), customer.getId(), null);
        assertThat(accepted.organizationId()).isNull();
    }

    /** W2 r2 P3-5: 内部の一覧・単票は、portalの一覧と同じ判定で「顧客公開対象／社内限定」を示す。 */
    @Test
    void 内部の一覧と単票はportalと同じ判定で顧客公開対象かを示す() {
        ServiceRequest before = request(customer, false, SINCE.minusSeconds(1));
        ServiceRequest boundary = request(customer, false, SINCE);
        ServiceRequest portal = request(customer, true, SINCE.minusYears(1));

        var internal = service.searchInternalRequests(1, 50, null, null, null, null, customer.getId());
        assertThat(internal.getRecords())
                .extracting(com.ses.dto.servicedesk.ServiceRequestDto::getId,
                        com.ses.dto.servicedesk.ServiceRequestDto::getPortalVisible)
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple(before.getId(), false),
                        org.assertj.core.api.Assertions.tuple(boundary.getId(), true),
                        org.assertj.core.api.Assertions.tuple(portal.getId(), true));
        assertThat(service.getInternalDetail(before.getId()).getPortalVisible()).isFalse();
        assertThat(service.getInternalDetail(boundary.getId()).getPortalVisible()).isTrue();
        assertThat(service.getInternalDetail(portal.getId()).getPortalVisible()).isTrue();
    }

    @Test
    void 公開開始前の社内起票は解決済みでもCSAT不可で開始後は回答できる() {
        ServiceRequest before = request(customer, false, SINCE.minusSeconds(1));
        ServiceRequest after = request(customer, false, SINCE);
        for (ServiceRequest row : new ServiceRequest[]{before, after}) {
            service.changeStatus(row.getId(), com.ses.dto.servicedesk.ServiceRequestStatusChangeRequest.builder()
                    .toStatus("IN_PROGRESS").version(0).build(), 100L, "INTERNAL_USER", "検証担当");
            service.changeStatus(row.getId(), com.ses.dto.servicedesk.ServiceRequestStatusChangeRequest.builder()
                    .toStatus("RESOLVED").version(1).reason("回答済み").build(), 100L, "INTERNAL_USER", "検証担当");
        }
        var answer = com.ses.dto.portal.PortalCsatCreateRequest.builder().score(5).feedbackComment("確認済み").build();
        assertNotFound(() -> service.submitCsat(before.getId(), answer, customer.getId(), 200L));
        service.submitCsat(after.getId(), answer, customer.getId(), 200L);
        assertThat(service.getPortalDetail(after.getId(), customer.getId()).getCsatScore()).isEqualTo(5);
    }

    private Customer customer() {
        Customer row = new Customer();
        row.setTenantId("default");
        row.setCompanyName("公開境界テスト-" + UUID.randomUUID());
        customers.insert(row);
        return row;
    }

    private ServiceRequest request(Customer owner, boolean portal, LocalDateTime createdAt) {
        ServiceRequest row = service.createRequest(ServiceRequestCreateRequest.builder()
                .customerId(owner.getId()).category("OTHER").priority("P3")
                .subject("公開境界").description("公開範囲の確認").build(), 100L, portal, portal ? 200L : null);
        requests.update(null, new LambdaUpdateWrapper<ServiceRequest>().eq(ServiceRequest::getId, row.getId())
                .set(ServiceRequest::getCreatedAt, createdAt));
        return row;
    }

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(404));
    }
}
