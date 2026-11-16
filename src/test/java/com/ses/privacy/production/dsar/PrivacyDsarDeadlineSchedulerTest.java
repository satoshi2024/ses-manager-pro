package com.ses.privacy.production.dsar;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.config.feature.FeatureSwitches;
import com.ses.entity.privacy.PrivacyRequest;
import com.ses.entity.privacy.PrivacyRequestSubjectLink;
import com.ses.mapper.PermissionGroupActionMapper;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.mapper.privacy.PrivacyRequestMapper;
import com.ses.mapper.privacy.PrivacyRequestSubjectLinkMapper;
import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.privacy.production.PrivacyFeatureProperties;
import com.ses.service.NotificationService;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.impl.AuthorizationServiceImpl;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NF07-A2a: DSAR の期限通知 scheduler（production-bridge-phased-plan.md §3.5.1）。
 *
 * <p>宛先の解決は SQL と実 {@code AuthorizationServiceImpl}（専用 tenant を設定 tenant にした instance）で確かめる:
 * privacy-admin group の所属者のうち {@code privacy.dsar.view} を持つ者だけに届き、管理者 role の全員には届かない。
 * H2 上の専用 tenant に group・user・case を作り、class の transaction で rollback する（他の tenant の行は読まない）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PrivacyDsarDeadlineSchedulerTest {

    private static final String TENANT = "nf07-a2a-dsar-notice";
    private static final String OTHER_TENANT = "nf07-a2a-dsar-notice-other";
    /** JST 2026-11-16 08:30。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T23:30:00Z"), ZoneId.of("Asia/Tokyo"));
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    private static final long BASE_ID = 1_516_100L;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PrivacyRequestMapper requestMapper;
    @Autowired
    private PrivacyRequestSubjectLinkMapper linkMapper;
    @Autowired
    private UserPermissionGroupMapper userPermissionGroupMapper;
    @Autowired
    private PermissionGroupActionMapper permissionGroupActionMapper;
    @Autowired
    private PermissionGroupMapper permissionGroupMapper;

    private long nextId;
    private NotificationService notifications;
    private long privacyAdmin;
    private long roleAdminOnly;
    private long bothGroups;
    private long salesInPrivacyAdmin;
    private long disabledPrivacyAdmin;

    @BeforeEach
    void setUp() {
        nextId = BASE_ID;
        notifications = mock(NotificationService.class);
        long privacyAdminGroup = group(TENANT, "privacy-admin");
        action(TENANT, privacyAdminGroup, "*", 0);
        action(TENANT, privacyAdminGroup, "privacy.*", 0);
        long roleAdminGroup = group(TENANT, "role-admin");
        action(TENANT, roleAdminGroup, "*", 0);
        action(TENANT, roleAdminGroup, "privacy.*", 1);

        privacyAdmin = user(TENANT, "nf07a2a-privacy-admin", "管理者", 1);
        member(TENANT, privacyAdmin, privacyAdminGroup);
        roleAdminOnly = user(TENANT, "nf07a2a-role-admin", "管理者", 1);
        member(TENANT, roleAdminOnly, roleAdminGroup);
        bothGroups = user(TENANT, "nf07a2a-both", "管理者", 1);
        member(TENANT, bothGroups, privacyAdminGroup);
        member(TENANT, bothGroups, roleAdminGroup);
        salesInPrivacyAdmin = user(TENANT, "nf07a2a-sales", "営業", 1);
        member(TENANT, salesInPrivacyAdmin, privacyAdminGroup);
        disabledPrivacyAdmin = user(TENANT, "nf07a2a-disabled", "管理者", 0);
        member(TENANT, disabledPrivacyAdmin, privacyAdminGroup);
    }

    @Test
    void 機能スイッチOFFなら何も読まず通知しない() {
        PrivacyRequestMapper mapper = mock(PrivacyRequestMapper.class);
        PrivacyDsarRecipientResolver resolver = mock(PrivacyDsarRecipientResolver.class);
        PrivacyDsarDeadlineScheduler scheduler = new PrivacyDsarDeadlineScheduler(mapper, resolver, notifications,
                decisions(), oidc(TENANT), CLOCK, switches(false));

        scheduler.notifyDaily();

        verifyNoInteractions(mapper, resolver, notifications);
    }

    @Test
    void 期限の接近と超過はprivacy_adminの所属者でviewを持つ者だけに届き管理者全員には届かない() {
        PrivacyRequest soon = request(TENANT, "DSAR-20261110-SOON0001", "IDENTITY_VERIFIED", TODAY.plusDays(3));
        PrivacyRequest overdue = request(TENANT, "DSAR-20261001-LATE0001", "REQUESTED", TODAY.minusDays(2));
        request(TENANT, "DSAR-20261116-FAR00001", "REQUESTED", TODAY.plusDays(30));
        request(TENANT, "DSAR-20261116-NODUE001", "REQUESTED", null);
        request(TENANT, "DSAR-20261001-DONE0001", "CANCELLED", TODAY.minusDays(5));
        request(TENANT, "DSAR-20261001-BLKD0001", "BLOCKED", TODAY.minusDays(5));
        request(OTHER_TENANT, "DSAR-20261001-OTHR0001", "REQUESTED", TODAY.minusDays(1));
        link(soon, "ENGINEER:101");

        // 定期実行の入口（void）は機能スイッチ ON なら dispatch する。件数は dispatch の戻り値で確かめる
        scheduler(TENANT, switches(true)).notifyDaily();

        ArgumentCaptor<Long> users = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> titles = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> links = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(notifications, times(2)).publishToUser(users.capture(), any(), titles.capture(), messages.capture(),
                links.capture(), keys.capture(), any());
        verify(notifications, times(2)).publishToUser(anyLong(), org.mockito.ArgumentMatchers.eq("PRIVACY_DSAR_DUE"),
                anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq("privacy-dsar"));
        assertThat(users.getAllValues()).containsOnly(privacyAdmin)
                .doesNotContain(roleAdminOnly, bothGroups, salesInPrivacyAdmin, disabledPrivacyAdmin);

        // 期限日の早い順（超過 → 接近）
        assertThat(messages.getAllValues()).containsExactly(
                "受付番号 DSAR-20261001-LATE0001 の期限は 2026-11-14 です。",
                "受付番号 DSAR-20261110-SOON0001 の期限は 2026-11-19 です。");
        assertThat(titles.getAllValues()).containsExactly("本人請求の期限を過ぎています", "本人請求の期限が近づいています");
        assertThat(links.getAllValues()).containsExactly("/privacy/dsar/" + overdue.getId(), "/privacy/dsar/" + soon.getId());
        assertThat(keys.getAllValues()).containsExactly(
                "PRIVACY_DSAR_DUE:" + TENANT + ":" + overdue.getId() + ":2026-11-14:OVERDUE",
                "PRIVACY_DSAR_DUE:" + TENANT + ":" + soon.getId() + ":2026-11-19:DUE_SOON");
        for (String text : List.of(String.join("|", messages.getAllValues()), String.join("|", titles.getAllValues()),
                String.join("|", keys.getAllValues()))) {
            assertThat(text).as("subject 参照・ticket 参照を入れない").doesNotContain("ENGINEER").doesNotContain("T-DSAR-");
        }
        assertThat(scheduler(TENANT, switches(true)).dispatch(TODAY)).as("送信を試みた件数（宛先ごと）").isEqualTo(2);
    }

    @Test
    void 期限の早い通知済みのcaseが多くても後の頁の期限接近caseを落とさない() {
        for (int i = 0; i < PrivacyDsarDeadlineScheduler.PAGE_SIZE; i++) {
            request(TENANT, String.format("DSAR-20260901-OLD%05d", i), "REQUESTED", TODAY.minusDays(30));
        }
        PrivacyRequest soon = request(TENANT, "DSAR-20261110-PAGE0002", "REQUESTED", TODAY.plusDays(2));

        int attempted = scheduler(TENANT, switches(true)).dispatch(TODAY);

        assertThat(attempted).isEqualTo(PrivacyDsarDeadlineScheduler.PAGE_SIZE + 1);
        verify(notifications).publishToUser(eqLong(privacyAdmin), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq("受付番号 DSAR-20261110-PAGE0002 の期限は 2026-11-18 です。"),
                org.mockito.ArgumentMatchers.eq("/privacy/dsar/" + soon.getId()), anyString(), anyString());
    }

    /** delta review D-2: 頁数の上限で読み切れなければ、件数だけの WARN を出して黙って切り捨てない。 */
    @Test
    void 頁数の上限で読み切れなければ件数だけのWARNを出す() {
        PrivacyRequestMapper mapper = mock(PrivacyRequestMapper.class);
        List<PrivacyRequest> fullPage = new ArrayList<>();
        for (int i = 0; i < PrivacyDsarDeadlineScheduler.PAGE_SIZE; i++) {
            PrivacyRequest row = new PrivacyRequest();
            row.setId(i + 1L);
            row.setRequestNo("DSAR-20261116-FULL" + i);
            row.setStatus("REQUESTED");
            row.setDueDate(TODAY.plusDays(1));
            fullPage.add(row);
        }
        when(mapper.selectPage(org.mockito.ArgumentMatchers.<Page<PrivacyRequest>>any(), any())).thenAnswer(call -> {
            Page<PrivacyRequest> page = new Page<>();
            page.setRecords(fullPage);
            return page;
        });
        PrivacyDsarRecipientResolver resolver = mock(PrivacyDsarRecipientResolver.class);
        when(resolver.resolve(TENANT)).thenReturn(List.of());
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(PrivacyDsarDeadlineScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new PrivacyDsarDeadlineScheduler(mapper, resolver, notifications, decisions(), oidc(TENANT), CLOCK,
                    switches(true)).dispatch(TODAY);
        } finally {
            logger.detachAppender(appender);
        }

        verify(mapper, times(PrivacyDsarDeadlineScheduler.MAX_PAGES)).selectPage(any(), any());
        assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains(PrivacyDsarDeadlineScheduler.LOG_CODE_TRUNCATED)
                        && message.contains("read=" + PrivacyDsarDeadlineScheduler.PAGE_SIZE
                        * PrivacyDsarDeadlineScheduler.MAX_PAGES))
                .noneMatch(message -> message.contains("DSAR-20261116-FULL"));
    }

    private static Long eqLong(long value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    @Test
    void 宛先がいなければ通知せず権限を確認できない所属者は宛先に含めない() {
        request(TENANT, "DSAR-20261110-SOON0002", "SCOPED", TODAY.plusDays(1));

        @SuppressWarnings("unchecked")
        ObjectProvider<AuthorizationService> unavailable = mock(ObjectProvider.class);
        PrivacyDsarDeadlineScheduler noAuthorization = new PrivacyDsarDeadlineScheduler(requestMapper,
                new PrivacyDsarRecipientResolver(jdbcTemplate, unavailable), notifications, decisions(), oidc(TENANT),
                CLOCK, switches(true));
        assertThat(noAuthorization.dispatch(TODAY)).isZero();

        AuthorizationService failing = mock(AuthorizationService.class);
        when(failing.isAllowedForActor(any(), any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        assertThat(new PrivacyDsarRecipientResolver(jdbcTemplate, provider(failing)).resolve(TENANT)).isEmpty();

        // 所属者のいない tenant では送らない
        request(OTHER_TENANT, "DSAR-20261110-SOON0003", "REQUESTED", TODAY.plusDays(1));
        assertThat(scheduler(OTHER_TENANT, switches(true)).dispatch(TODAY)).isZero();

        verify(notifications, never()).publishToUser(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 通知日数は設定値を1から90へ丸めて使う() {
        request(TENANT, "DSAR-20261110-EDGE0001", "REQUESTED", TODAY.plusDays(10));
        PrivacyFeatureProperties features = new PrivacyFeatureProperties();
        features.getDsar().setDeadlineWarningDays(9);
        assertThat(scheduler(TENANT, switches(true), features).dispatch(TODAY)).isZero();

        features.getDsar().setDeadlineWarningDays(10);
        assertThat(scheduler(TENANT, switches(true), features).dispatch(TODAY)).isEqualTo(1);

        features.getDsar().setDeadlineWarningDays(0);
        assertThat(new PrivacyDsarDecisions(new PrivacyApprovalProperties(), features, CLOCK).deadlineWarningDays())
                .isEqualTo(1);
        features.getDsar().setDeadlineWarningDays(500);
        assertThat(new PrivacyDsarDecisions(new PrivacyApprovalProperties(), features, CLOCK).deadlineWarningDays())
                .isEqualTo(90);
    }

    @Test
    void 定期実行の入口はSchedulerLock付きでFeatureSwitchesをfieldに持つ() throws Exception {
        var method = PrivacyDsarDeadlineScheduler.class.getMethod("notifyDaily");
        // ShedLock の PROXY_METHOD は primitive を返す method を lock できない（A2a review P1-1）
        assertThat(method.getReturnType()).isEqualTo(void.class);
        assertThat(method.getAnnotation(Scheduled.class).zone()).isEqualTo("Asia/Tokyo");
        assertThat(method.getAnnotation(SchedulerLock.class).name()).isEqualTo("privacyDsarDeadlineDaily");
        assertThat(PrivacyDsarDeadlineScheduler.class.getDeclaredField("featureSwitches").getType())
                .isEqualTo(FeatureSwitches.class);
    }

    // ---------------------------------------------------------------- helpers

    private PrivacyDsarDeadlineScheduler scheduler(String tenant, FeatureSwitches switches) {
        return scheduler(tenant, switches, new PrivacyFeatureProperties());
    }

    private PrivacyDsarDeadlineScheduler scheduler(String tenant, FeatureSwitches switches,
                                                   PrivacyFeatureProperties features) {
        AuthorizationService authorization = new AuthorizationServiceImpl(userPermissionGroupMapper,
                permissionGroupActionMapper, permissionGroupMapper, oidc(tenant));
        return new PrivacyDsarDeadlineScheduler(requestMapper,
                new PrivacyDsarRecipientResolver(jdbcTemplate, provider(authorization)), notifications,
                new PrivacyDsarDecisions(new PrivacyApprovalProperties(), features, CLOCK), oidc(tenant), CLOCK, switches);
    }

    private static PrivacyDsarDecisions decisions() {
        return new PrivacyDsarDecisions(new PrivacyApprovalProperties(), new PrivacyFeatureProperties(), CLOCK);
    }

    private static OidcSecurityProperties oidc(String tenant) {
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setTenantId(tenant);
        return oidc;
    }

    private static FeatureSwitches switches(boolean dsar) {
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.toggle(Feature.NF07_DSAR).setEnabled(dsar);
        return new FeatureSwitches(properties);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AuthorizationService> provider(AuthorizationService authorization) {
        ObjectProvider<AuthorizationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(authorization);
        return provider;
    }

    private PrivacyRequest request(String tenant, String requestNo, String status, LocalDate dueDate) {
        PrivacyRequest row = new PrivacyRequest();
        row.setTenantId(tenant);
        row.setRequestNo(requestNo);
        row.setRequestType("ACCESS");
        row.setStatus(status);
        row.setChannel("EMAIL");
        row.setReceivedAt(LocalDateTime.of(2026, 10, 1, 9, 0));
        row.setDueDate(dueDate);
        row.setIntakeTicketRef("T-" + requestNo);
        row.setIdentityState("UNVERIFIED");
        row.setRowVersion(0L);
        row.setCreatedBy(privacyAdmin);
        requestMapper.insert(row);
        return row;
    }

    private void link(PrivacyRequest request, String subjectRef) {
        PrivacyRequestSubjectLink link = new PrivacyRequestSubjectLink();
        link.setTenantId(request.getTenantId());
        link.setRequestId(request.getId());
        link.setSubjectRef(subjectRef);
        link.setLinkState("SELECTED");
        linkMapper.insert(link);
    }

    private long group(String tenant, String groupKey) {
        long id = nextId++;
        jdbcTemplate.update("INSERT INTO m_permission_group (id, tenant_id, group_key, group_name, enabled, deleted_flag) "
                + "VALUES (?, ?, ?, ?, 1, 0)", id, tenant, groupKey, groupKey);
        return id;
    }

    private void action(String tenant, long groupId, String actionKey, int denyFlag) {
        jdbcTemplate.update("INSERT INTO t_permission_group_action (id, tenant_id, group_id, action_key, deny_flag, "
                + "deleted_flag) VALUES (?, ?, ?, ?, ?, 0)", nextId++, tenant, groupId, actionKey, denyFlag);
    }

    private long user(String tenant, String username, String role, int status) {
        long id = nextId++;
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password, real_name, role, status, tenant_id, deleted_flag) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 0)", id, username, "unused", username, role, status, tenant);
        return id;
    }

    private void member(String tenant, long userId, long groupId) {
        jdbcTemplate.update("INSERT INTO t_user_permission_group (id, tenant_id, user_id, group_id, assigned_by, "
                + "deleted_flag) VALUES (?, ?, ?, ?, ?, 0)", nextId++, tenant, userId, groupId, 1L);
    }
}
