package com.ses.service.security.epoch;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.EngineerAccountingHistory;
import com.ses.entity.EngineerSales;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerAccountingHistoryMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.EngineerSalesService;
import com.ses.service.EngineerService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.LegalEntityContextService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * U8（NF01細粒度epoch計画 §4.2・§7.4 Demo (1)(4)、W3-16a 要員・link・営業）: 実Spring beanとH2で、要員・紐付け・担当営業・
 * 会計履歴のwriterが同じtransactionで engineer epoch（{@code t_engineer.engineer_scope_epoch}）を進める条件を確かめる。
 *
 * <ul>
 *   <li>変化の無い更新（主担当のままの主担当変更・現任の無い一括解除・名前だけの更新）では進めない。変化があれば1だけ進め、
 *       別の要員は変えない（細粒度）。同じtransactionの入れ子・繰返しでは1回だけ進める（E-4）。</li>
 *   <li>anchorのtenantは要求のtenant contextではなく、lockした要員行の値を使う。</li>
 *   <li>要員削除は連携ユーザーの status列だけを0にし（他列は不変、§11 F-5）、actorとengineerを各1回進める。</li>
 *   <li>上限では409 {@code error.authorization.epochUnavailable} で止め、業務更新ごとrollback-onlyにする（E-6）。</li>
 * </ul>
 *
 * <p>クラス全体を {@code @Transactional} にし、試験の行・epochの変化はすべてrollbackする。fenceは同じtransactionの
 * 同じanchorを1回しか進めないため、「進めない」確認は同じanchorを進める前に行う。epochは差分で比べる。
 * 法人contextは要員の業務規則の前提で、本試験の対象ではないためmockにする。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EngineerScopeEpochWriterIntegrationTest {

    private static final String TENANT = "default";
    private static final long LEGAL_ENTITY_ID = 1L;
    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired private EngineerSalesService engineerSalesService;
    @Autowired private EngineerAccountLinkService engineerAccountLinkService;
    @Autowired private EngineerService engineerService;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private EngineerSalesMapper engineerSalesMapper;
    @Autowired private EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Autowired private EngineerAccountingHistoryMapper engineerAccountingHistoryMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private LegalEntityContextService legalEntityContextService;

    @BeforeEach
    void setUp() {
        reset(legalEntityContextService);
        when(legalEntityContextService.requireCurrentDate()).thenReturn(LocalDate.now());
        when(legalEntityContextService.requireCurrentLegalEntityId()).thenReturn(LEGAL_ENTITY_ID);
        AccountingTenantContextHolder.setTenantId(TENANT);
    }

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 営業割当は対象の要員だけを1進め同じtransactionの二回目の割当では重ねて進めない() {
        long target = insertEngineer(TENANT, null);
        long other = insertEngineer(TENANT, null);
        long sales1 = insertUser("営業", 1);
        long sales2 = insertUser("営業", 1);
        long targetBefore = engineerEpoch(target);
        long otherBefore = engineerEpoch(other);

        engineerSalesService.assign(target, sales1, true, null);
        assertEquals(targetBefore + 1, engineerEpoch(target));
        assertEquals(otherBefore, engineerEpoch(other), "別の要員は変えない（細粒度）");

        engineerSalesService.assign(target, sales2, false, null);
        assertEquals(targetBefore + 1, engineerEpoch(target), "同じtransactionでは2回目以降を進めない（E-4）");
        assertEquals(2, engineerSalesService.listActive(target).size());
    }

    @Test
    void 主担当のままの主担当変更と現任の無い一括解除では要員のepochを進めない() {
        long withPrimary = insertEngineer(TENANT, null);
        long withoutSales = insertEngineer(TENANT, null);
        long sales = insertUser("営業", 1);
        long assignmentId = insertSales(withPrimary, sales, 1);
        long primaryBefore = engineerEpoch(withPrimary);
        long emptyBefore = engineerEpoch(withoutSales);

        engineerSalesService.setPrimary(withPrimary, assignmentId);
        engineerSalesService.releaseAllByEngineerId(withoutSales, LocalDate.now());

        assertEquals(primaryBefore, engineerEpoch(withPrimary), "割当行が変わらなければ進めない");
        assertEquals(emptyBefore, engineerEpoch(withoutSales), "現任の割当が無ければ進めない");
    }

    @Test
    void 営業解除と主担当の変更はそれぞれ要員のepochを1進める() {
        long released = insertEngineer(TENANT, null);
        long switched = insertEngineer(TENANT, null);
        long sales1 = insertUser("営業", 1);
        long sales2 = insertUser("営業", 1);
        long onlyAssignment = insertSales(released, sales1, 1);
        long formerPrimary = insertSales(switched, sales1, 1);
        long secondary = insertSales(switched, sales2, 0);
        long releasedBefore = engineerEpoch(released);
        long switchedBefore = engineerEpoch(switched);

        engineerSalesService.release(released, onlyAssignment);
        engineerSalesService.setPrimary(switched, secondary);

        assertEquals(releasedBefore + 1, engineerEpoch(released));
        assertNotNull(engineerSalesMapper.selectById(onlyAssignment).getReleasedAt(), "解除日を残す（履歴保全）");
        assertEquals(switchedBefore + 1, engineerEpoch(switched));
        assertEquals(0, engineerSalesMapper.selectById(formerPrimary).getPrimaryFlag(), "旧主担当は降格する");
        assertEquals(1, engineerSalesMapper.selectById(secondary).getPrimaryFlag());
    }

    @Test
    void 要員のtenantは要求のtenantではなくDBの行の値で照合する() {
        // 担当営業のAPIはtenantで要員を絞らない。anchorを要求のtenantで作ると増分が0件になり409になる。
        long otherTenantEngineer = insertEngineer("tenant-w316a", null);
        long sales = insertUser("営業", 1);
        long before = engineerEpoch(otherTenantEngineer);

        engineerSalesService.assign(otherTenantEngineer, sales, true, null);

        assertEquals(before + 1, engineerEpoch(otherTenantEngineer));
    }

    @Test
    void 紐付けと解除はそれぞれ要員のepochを1進め無効化された利用者には紐付けない() {
        long linkedEngineer = insertEngineer(TENANT, null);
        long unlinkedEngineer = insertEngineer(TENANT, null);
        long refusedEngineer = insertEngineer(TENANT, null);
        long user = insertUser("要員", 1);
        long previouslyLinked = insertUser("要員", 1);
        long disabled = insertUser("要員", 0);
        isolateLinks(linkedEngineer, unlinkedEngineer, refusedEngineer, user, previouslyLinked, disabled);
        insertLink(unlinkedEngineer, previouslyLinked);
        long linkedBefore = engineerEpoch(linkedEngineer);
        long unlinkedBefore = engineerEpoch(unlinkedEngineer);
        long refusedBefore = engineerEpoch(refusedEngineer);

        engineerAccountLinkService.link(linkedEngineer, user, null);
        engineerAccountLinkService.unlinkByEngineerId(unlinkedEngineer);

        assertEquals(linkedBefore + 1, engineerEpoch(linkedEngineer));
        assertEquals(unlinkedBefore + 1, engineerEpoch(unlinkedEngineer));
        assertEquals(0, linkCount(unlinkedEngineer));

        // lock下で読み直した利用者が無効なら紐付けない（W3-15 review P3-8）。例外はtransactionをrollback-onlyにするため最後に行う。
        BusinessException refused = assertThrows(BusinessException.class,
                () -> engineerAccountLinkService.link(refusedEngineer, disabled, null));
        assertEquals("error.engineerAccount.userDisabled", refused.getMessageKey());
        assertEquals(refusedBefore, engineerEpoch(refusedEngineer));
        assertEquals(0, linkCount(refusedEngineer));
    }

    @Test
    void 要員の会計履歴が変わる更新だけが要員のepochを1進め名前だけの更新では進めない() {
        long engineerId = insertEngineer(TENANT, new BigDecimal("500000"));
        engineerAccountingHistoryMapper.insert(EngineerAccountingHistory.builder()
                .engineerId(engineerId).expectedUnitPrice(new BigDecimal("500000"))
                .validFrom(LocalDate.now()).validTo(null).build());
        long before = engineerEpoch(engineerId);

        Engineer renamed = Engineer.builder().fullName("改名後の要員").build();
        renamed.setId(engineerId);
        renamed.setVersion(version(engineerId));
        engineerService.updateWithStatusGuard(renamed);
        assertEquals(before, engineerEpoch(engineerId), "述語外の列（氏名）だけの更新では進めない");

        Engineer repriced = Engineer.builder().expectedUnitPrice(new BigDecimal("600000")).build();
        repriced.setId(engineerId);
        repriced.setVersion(version(engineerId));
        engineerService.updateWithStatusGuard(repriced);
        assertEquals(before + 1, engineerEpoch(engineerId), "会計履歴の行が変われば1だけ進める");
        assertEquals(0, new BigDecimal("600000").compareTo(
                engineerAccountingHistoryMapper.selectCurrent(engineerId).getExpectedUnitPrice()));
    }

    @Test
    void 要員削除は連携ユーザーのstatusだけを0にしactorとengineerを各1回だけ進める() {
        long engineerId = insertEngineer(TENANT, null);
        long linkedUser = insertUser("要員", 1);
        long sales = insertUser("営業", 1);
        isolateLinks(engineerId, engineerId, engineerId, linkedUser, linkedUser, linkedUser);
        insertLink(engineerId, linkedUser);
        long assignmentId = insertSales(engineerId, sales, 1);
        SysUser userBefore = sysUserMapper.selectById(linkedUser);
        long engineerBefore = engineerEpoch(engineerId);
        long actorBefore = actorEpoch(linkedUser);

        assertTrue(engineerService.removeById(engineerId, version(engineerId)));

        assertEquals(1, jdbc.queryForObject("SELECT deleted_flag FROM t_engineer WHERE id = ?", Integer.class, engineerId));
        assertEquals(engineerBefore + 1, engineerEpoch(engineerId),
                "論理削除・営業解除・紐付け解除の入れ子でも engineer epoch は1回だけ進める");
        assertEquals(actorBefore + 1, actorEpoch(linkedUser), "連携ユーザーの無効化でactor epochを1進める");
        assertNotNull(engineerSalesMapper.selectById(assignmentId).getReleasedAt());
        assertEquals(0, linkCount(engineerId), "要員削除の後段でも紐付けを解除する");
        SysUser userAfter = sysUserMapper.selectById(linkedUser);
        assertEquals(0, userAfter.getStatus());
        assertEquals(userBefore.getRole(), userAfter.getRole(), "status以外の列は書き戻さない（F-5）");
        assertEquals(userBefore.getEmail(), userAfter.getEmail());
        assertEquals(userBefore.getRealName(), userAfter.getRealName());
        assertEquals(userBefore.getUsername(), userAfter.getUsername());
    }

    @Test
    void engineer_epochが上限なら409で止め業務更新をrollback_onlyにする() {
        long engineerId = insertEngineer(TENANT, null);
        long sales = insertUser("営業", 1);
        jdbc.update("UPDATE t_engineer SET engineer_scope_epoch = ? WHERE id = ?", Long.MAX_VALUE, engineerId);

        BusinessException error = assertThrows(BusinessException.class,
                () -> engineerSalesService.assign(engineerId, sales, true, null));

        assertEquals(409, error.getCode());
        assertEquals(AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY, error.getMessageKey());
        assertEquals(Long.MAX_VALUE, engineerEpoch(engineerId), "上限の値は変えない");
        Boolean rollbackOnly = new TransactionTemplate(transactionManager).execute(status -> status.isRollbackOnly());
        assertEquals(Boolean.TRUE, rollbackOnly, "割当のinsertは業務更新ごとrollbackされる");
    }

    // ===== helper =====

    private long insertEngineer(String tenantId, BigDecimal expectedUnitPrice) {
        Engineer engineer = Engineer.builder().tenantId(tenantId).legalEntityId(LEGAL_ENTITY_ID)
                .fullName("W3-16a要員" + SEQUENCE.incrementAndGet()).status("Bench").employmentType("正社員")
                .expectedUnitPrice(expectedUnitPrice).build();
        engineerMapper.insert(engineer);
        return engineer.getId();
    }

    private long insertUser(String role, int status) {
        long seq = SEQUENCE.incrementAndGet();
        SysUser user = SysUser.builder().tenantId(TENANT).username("w316a_" + seq + "_" + UUID.randomUUID()
                        .toString().substring(0, 6)).password("pass").realName("W3-16a試験" + seq)
                .email("w316a-" + seq + "@example.com").role(role).status(status).build();
        sysUserMapper.insert(user);
        return user.getId();
    }

    private long insertSales(long engineerId, long salesUserId, int primaryFlag) {
        EngineerSales row = EngineerSales.builder().engineerId(engineerId).salesUserId(salesUserId)
                .primaryFlag(primaryFlag).assignedAt(LocalDate.now()).build();
        engineerSalesMapper.insert(row);
        return row.getId();
    }

    private void insertLink(long engineerId, long userId) {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setTenantId(TENANT);
        link.setEngineerId(engineerId);
        link.setSysUserId(userId);
        engineerAccountLinkMapper.insert(link);
    }

    /** 共有H2の採番が他の試験の物理削除後に再利用されても、本試験の紐付けだけを独立させる（rollbackで戻る）。 */
    private void isolateLinks(long engineer1, long engineer2, long engineer3, long user1, long user2, long user3) {
        jdbc.update("DELETE FROM t_engineer_account_link WHERE engineer_id IN (?, ?, ?) OR sys_user_id IN (?, ?, ?)",
                engineer1, engineer2, engineer3, user1, user2, user3);
    }

    private int linkCount(long engineerId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM t_engineer_account_link WHERE engineer_id = ?",
                Integer.class, engineerId);
        return count == null ? 0 : count;
    }

    private int version(long engineerId) {
        Integer version = jdbc.queryForObject("SELECT version FROM t_engineer WHERE id = ?", Integer.class, engineerId);
        return version == null ? 0 : version;
    }

    private long engineerEpoch(long engineerId) {
        return jdbc.queryForObject("SELECT engineer_scope_epoch FROM t_engineer WHERE id = ?", Long.class, engineerId);
    }

    private long actorEpoch(long userId) {
        return jdbc.queryForObject("SELECT actor_authz_epoch FROM sys_user WHERE id = ?", Long.class, userId);
    }
}
