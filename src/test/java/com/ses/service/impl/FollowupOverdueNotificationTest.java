package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.BaseIntegrationTest;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerFollowup;
import com.ses.entity.EngineerSales;
import com.ses.entity.Notification;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerFollowupMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.NotificationMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.NotificationGenerateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FR-11: 次回フォロー予定日(next_date)超過の通知生成と冪等性(dedupe)の検証
 */
@Sql("/sql/engineer-schema-h2.sql")
class FollowupOverdueNotificationTest extends BaseIntegrationTest {

    @Autowired
    private NotificationGenerateService notificationGenerateService;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private EngineerFollowupMapper engineerFollowupMapper;

    @Autowired
    private EngineerSalesMapper engineerSalesMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private NotificationMapper notificationMapper;

    private Long engineerId;
    private Long salesUserId;

    @BeforeEach
    void setUp() {
        SysUser sales = new SysUser();
        sales.setUsername("sales_followup");
        sales.setPassword("pass");
        sales.setRealName("営業 花子");
        sales.setRole("営業");
        sales.setStatus(1);
        sysUserMapper.insert(sales);
        salesUserId = sales.getId();

        Engineer e = Engineer.builder().fullName("要員 超過太郎").status("Bench").build();
        engineerMapper.insert(e);
        engineerId = e.getId();

        EngineerSales assignment = EngineerSales.builder()
                .engineerId(engineerId)
                .salesUserId(salesUserId)
                .primaryFlag(1)
                .assignedAt(LocalDate.now().minusMonths(6))
                .build();
        engineerSalesMapper.insert(assignment);

        EngineerFollowup followup = EngineerFollowup.builder()
                .engineerId(engineerId)
                .followupType("1on1")
                .followupDate(LocalDate.now().minusDays(60))
                .nextDate(LocalDate.now().minusDays(10)) // 期日超過
                .build();
        engineerFollowupMapper.insert(followup);
    }

    @Test
    void 期日超過フォローは担当営業へ一度だけ通知される() {
        notificationGenerateService.generateAll();
        notificationGenerateService.generateAll(); // 再実行してもdedupeで増えないこと

        List<Notification> notifications = notificationMapper.selectList(
                new QueryWrapper<Notification>()
                        .eq("type", "FOLLOWUP_OVERDUE")
                        .eq("recipient_user_id", salesUserId));

        assertEquals(1, notifications.size());
        assertEquals("engineer", notifications.get(0).getMenuKey());
    }
}
