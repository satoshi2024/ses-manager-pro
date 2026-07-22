package com.ses.mapper;

import com.ses.entity.Notification;
import com.ses.entity.NotificationRead;
import com.ses.entity.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * markAllReadForUser の一括既読化SQLをH2上で検証する（P8フォローアップ）。
 * 未読は既読化され、既に既読済みの行は重複挿入されずUNIQUE制約にも違反しないこと、
 * 他ユーザーの既読状態には影響しないことを確認する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class NotificationMapperIntegrationTest {

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private NotificationReadMapper notificationReadMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @BeforeEach
    void setUp() {
        ensureAdminUser(1L, "admin_test1");
        ensureAdminUser(2L, "admin_test2");
    }

    private void ensureAdminUser(Long id, String username) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            user = new SysUser();
            user.setId(id);
            user.setUsername(username);
            user.setPassword("password");
            user.setRealName("Test Admin");
            user.setRole("管理者");
            user.setStatus(1);
            sysUserMapper.insert(user);
        } else {
            user.setRole("管理者");
            sysUserMapper.updateById(user);
        }
    }

    private Long insertNotification(String dedupeKey) {
        Notification n = new Notification();
        n.setType("CONTRACT_END");
        n.setMenuKey("contract");
        n.setTitle("title");
        n.setMessage("message");
        n.setDedupeKey(dedupeKey);
        n.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(n);
        return n.getId();
    }

    @Test
    void markAllReadForUser_未読を一括既読化し既読済みは重複挿入しない() {
        Long n1 = insertNotification("k1");
        Long n2 = insertNotification("k2");

        // n1は事前に既読化済み（この状態でも重複INSERTでエラーにならないこと）
        NotificationRead pre = new NotificationRead();
        pre.setNotificationId(n1);
        pre.setUserId(1L);
        pre.setReadAt(LocalDateTime.now());
        notificationReadMapper.insert(pre);

        assertEquals(1L, notificationMapper.countUnread(1L));

        int inserted = notificationMapper.markAllReadForUser(1L);

        assertEquals(1, inserted, "未読だったn2のみが新規に既読化される");
        assertEquals(0L, notificationMapper.countUnread(1L));
    }

    @Test
    void markAllReadForUser_他ユーザーの未読件数には影響しない() {
        insertNotification("k3");

        notificationMapper.markAllReadForUser(1L);

        assertEquals(1L, notificationMapper.countUnread(2L), "ユーザー2はまだ未読のまま");
    }
}
