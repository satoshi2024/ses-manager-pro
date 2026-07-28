package com.ses.mapper;

import com.ses.dto.notification.NotificationDto;
import com.ses.entity.Notification;
import com.ses.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通知の組織スコープ境界。
 *
 * <p>組織条件は「宛先が特定されていない全体通知」にだけ効かせる。宛先指定通知へ発行時点の組織を
 * 重ねると、本人が異動した瞬間に自分宛の通知が一覧から消え既読にもできなくなる
 * （{@code recipient_user_id} で既に本人限定になっているので、組織条件は可視性を狭める効果しかない）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
class NotificationOrganizationScopeTest {

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Test
    void 宛先指定通知は異動後も本人に見えて既読にできる() {
        SysUser user = insertUser("notify-transferred");

        Notification targeted = new Notification();
        targeted.setType("WORK_RECORD");
        targeted.setTitle("勤怠が差し戻されました");
        targeted.setDedupeKey("targeted#u" + user.getId());
        targeted.setRecipientUserId(user.getId());
        targeted.setOrganizationId(100L);
        notificationMapper.insert(targeted);

        // 異動後は所属組織が 200 になり、発行時点の組織 100 はscopeに含まれない。
        List<Long> afterTransfer = List.of(200L);

        List<NotificationDto> page = notificationMapper.selectPageForUserScoped(
                user.getId(), null, null, 10, 0, false, afterTransfer);
        assertEquals(1, page.size());
        assertEquals(targeted.getId(), page.get(0).getId());

        assertEquals(1, notificationMapper.countPageForUserScoped(user.getId(), null, null, false, afterTransfer));
        assertEquals(1, notificationMapper.countUnreadScoped(user.getId(), false, afterTransfer));
        assertEquals(1, notificationMapper.countVisibleScoped(targeted.getId(), user.getId(), false, afterTransfer));
    }

    @Test
    void 全体通知は組織scopeで絞られる() {
        SysUser user = insertUser("notify-broadcast");

        Notification broadcast = new Notification();
        broadcast.setType("SYSTEM");
        broadcast.setTitle("他組織向けの全体通知");
        broadcast.setDedupeKey("broadcast#org100");
        broadcast.setRecipientUserId(null);
        broadcast.setOrganizationId(100L);
        notificationMapper.insert(broadcast);

        assertTrue(notificationMapper.selectPageForUserScoped(
                user.getId(), null, null, 10, 0, false, List.of(200L)).isEmpty());
        assertEquals(1, notificationMapper.selectPageForUserScoped(
                user.getId(), null, null, 10, 0, false, List.of(100L)).size());
        // 管理者相当(fullAccess)は組織条件を受けない。
        assertEquals(1, notificationMapper.selectPageForUserScoped(
                user.getId(), null, null, 10, 0, true, List.of()).size());
    }

    @Test
    void NULL組織の業務通知は全組織へ配信せずSYSTEMだけを共通表示する() {
        SysUser user = insertUser("notify-null-business");

        Notification business = new Notification();
        business.setType("INVOICE_OVERDUE");
        business.setTitle("請求期限超過");
        business.setDedupeKey("null-business");
        business.setOrganizationId(null);
        notificationMapper.insert(business);

        Notification system = new Notification();
        system.setType("SYSTEM");
        system.setTitle("メンテナンス");
        system.setDedupeKey("null-system");
        system.setOrganizationId(null);
        notificationMapper.insert(system);

        List<NotificationDto> page = notificationMapper.selectPageForUserScoped(
                user.getId(), null, null, 10, 0, false, List.of(200L));
        assertEquals(1, page.size());
        assertEquals(system.getId(), page.get(0).getId());
        assertEquals(1, notificationMapper.countVisibleScoped(
                system.getId(), user.getId(), false, List.of(200L)));
        assertEquals(0, notificationMapper.countVisibleScoped(
                business.getId(), user.getId(), false, List.of(200L)));
    }

    private SysUser insertUser(String username) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword("pass");
        user.setRealName("通知テスト");
        user.setRole("管理者");
        user.setStatus(1);
        sysUserMapper.insert(user);
        return user;
    }
}
