package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.config.feature.Feature;
import com.ses.dto.portal.PortalServiceRequestDto;
import com.ses.dto.servicedesk.ServiceRequestCreateRequest;
import com.ses.entity.Customer;
import com.ses.entity.ServiceRequest;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.service.FeatureEnablementActor;
import com.ses.featureenablement.state.FeatureEnablementOverlay;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.mapper.CustomerMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-B03（feature-enablement-console design §8.4、FE-AC-010-01）: DB の源の context では、portal の一覧は承認で変える設定
 * （{@code t_feature_enablement_setting}）の公開範囲を判定のたびに読む。設定の行が無ければ portal 起票だけ（本番の既定）、公開範囲を
 * 承認すれば次の一覧から社内登録の問い合わせも出し、未設定へ戻せば次の一覧から出さない（再起動しない）。
 * {@link PortalServiceRequestVisibilityIntegrationTest}（property の源）の DB の源の変種。行は試験の transaction の中で入れ、rollback で
 * 消す。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
class PortalServiceRequestVisibilityDatabaseSourceTest {

    private static final String KEY = FeatureEnablementCatalog.SETTING_NF02_VISIBLE_SINCE;

    @Autowired
    private ServiceRequestService serviceRequestService;
    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private PortalServiceRequestVisibilityPolicy policy;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private FeatureEnablementSnapshotHolder holder;
    @Autowired
    private FeatureEnablementOverlay overlay;

    private Customer customer;
    private ServiceRequest internalRequest;
    private ServiceRequest portalRequest;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
        customer = new Customer();
        customer.setTenantId("default");
        customer.setCompanyName("公開範囲DB源テスト顧客-" + UUID.randomUUID().toString().substring(0, 8));
        customerMapper.insert(customer);
        internalRequest = serviceRequestService.createRequest(request("社内で代理起票", "PORTAL"), 100L, false, null);
        portalRequest = serviceRequestService.createRequest(request("portalから起票", null), 100L, true, 200L);
    }

    @AfterEach
    void clearTenantContext() {
        AccountingTenantContextHolder.clear();
    }

    @AfterTransaction
    void restoreSharedState() {
        for (Feature feature : Feature.values()) {
            overlay.open(feature, FeatureEnablementOverlay.AUTO_CLOSE_PENDING);
        }
        holder.reload();
    }

    @Test
    void 承認で変える公開範囲は次の一覧から効き未設定へ戻すとportal起票だけになる() {
        holder.reload();
        assertThat(policy.describe()).contains("判定のたびに読む");
        assertThat(portalIds()).as("設定の行が無ければ portal 起票だけ").containsExactly(portalRequest.getId());

        FeatureEnablementH2Fixture fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
        FeatureEnablementActor requester = fixture.admin();
        FeatureEnablementActor approver = fixture.admin();
        long requestId = approvedRequest("2000-01-01T00:00:00+09:00", requester, approver);
        jdbc.update("INSERT INTO t_feature_enablement_setting (tenant_id, setting_key, setting_value, version, request_id, "
                        + "requested_by, approved_by, approved_at) VALUES ('default', ?, ?, 0, ?, ?, ?, TIMESTAMP '2026-11-20 10:00:00')",
                KEY, "2000-01-01T00:00:00+09:00", requestId, requester.userId(), approver.userId());
        holder.reload();
        assertThat(portalIds()).as("承認した公開範囲の後に作成された社内登録も出す")
                .containsExactlyInAnyOrder(portalRequest.getId(), internalRequest.getId());
        assertThat(serviceRequestService.getPortalDetail(internalRequest.getId(), customer.getId()).getId())
                .isEqualTo(internalRequest.getId());

        long revertId = approvedRequest(null, requester, approver);
        jdbc.update("UPDATE t_feature_enablement_setting SET setting_value = NULL, version = 1, request_id = ? "
                + "WHERE tenant_id = 'default' AND setting_key = ?", revertId, KEY);
        holder.reload();
        assertThat(portalIds()).as("未設定へ戻した次の一覧から社内登録を出さない").containsExactly(portalRequest.getId());
    }

    private java.util.List<Long> portalIds() {
        Page<PortalServiceRequestDto> page = serviceRequestService.searchPortalRequests(1, 50, null, null, customer.getId());
        return page.getRecords().stream().map(PortalServiceRequestDto::getId).toList();
    }

    private long approvedRequest(String value, FeatureEnablementActor requester, FeatureEnablementActor approver) {
        String fingerprint = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO t_feature_enablement_request (tenant_id, request_type, target_key, proposed_value, status, "
                        + "condition_fingerprint, condition_summary, requested_by, requested_at, expires_at, decided_by, "
                        + "decided_at, version) VALUES ('default', 'SETTING_CHANGE', ?, ?, 'APPROVED', ?, 'fixture', ?, "
                        + "TIMESTAMP '2026-11-20 09:00:00', TIMESTAMP '2026-11-27 09:00:00', ?, TIMESTAMP '2026-11-20 10:00:00', 1)",
                KEY, value, (fingerprint + fingerprint).substring(0, 64), requester.userId(), approver.userId());
        return jdbc.queryForObject("SELECT MAX(id) FROM t_feature_enablement_request WHERE tenant_id = 'default' "
                + "AND target_key = ?", Long.class, KEY);
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
}
