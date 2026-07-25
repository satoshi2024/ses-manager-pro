package com.ses.service.impl;

import com.ses.common.util.PageUtils;
import com.ses.mapper.NotificationMapper;
import com.ses.mapper.NotificationReadMapper;
import com.ses.service.notification.WebhookNotifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知一覧のページサイズ正規化。
 *
 * <p>{@code selectPageForUser} は LIMIT/OFFSET を自前で組み立てるため、MyBatis-Plus の
 * {@code PaginationInnerInterceptor.setMaxLimit(1000)} による保護が効かない。
 * 正規化が無いと {@code ?size=999999999} で通知テーブル全件をメモリへ載せられてしまう
 * （/api/notifications は要員を含む全ロールが到達できる）。
 */
@ExtendWith(MockitoExtension.class)
class NotificationPagingTest {

    @Mock private NotificationMapper notificationMapper;
    @Mock private NotificationReadMapper notificationReadMapper;
    @Mock private WebhookNotifier webhookNotifier;
    @Mock private MessageSource messageSource;

    @InjectMocks private NotificationServiceImpl service;

    @Test
    void 巨大なsizeは上限へ丸められる() {
        when(notificationMapper.selectPageForUser(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(notificationMapper.countPageForUser(any(), any(), any())).thenReturn(0L);

        service.pageForUser(1L, 1L, 999_999_999L, null, null);

        verify(notificationMapper).selectPageForUser(
                eq(1L), eq(null), eq(null), eq((int) PageUtils.MAX_PAGE_SIZE), eq(0));
    }

    @Test
    void size0以下は既定サイズになりオフセットは負にならない() {
        when(notificationMapper.selectPageForUser(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(notificationMapper.countPageForUser(any(), any(), any())).thenReturn(0L);

        service.pageForUser(1L, -5L, -1L, null, null);

        verify(notificationMapper).selectPageForUser(
                eq(1L), eq(null), eq(null), eq((int) PageUtils.DEFAULT_PAGE_SIZE), eq(0));
    }

    @Test
    void 正常なページ指定はそのまま渡る() {
        when(notificationMapper.selectPageForUser(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(notificationMapper.countPageForUser(any(), any(), any())).thenReturn(0L);

        var page = service.pageForUser(1L, 3L, 20L, null, null);

        verify(notificationMapper).selectPageForUser(eq(1L), eq(null), eq(null), eq(20), eq(40));
        assertEquals(3L, page.getCurrent());
        assertEquals(20L, page.getSize());
        assertTrue(page.getRecords().isEmpty());
    }
}
