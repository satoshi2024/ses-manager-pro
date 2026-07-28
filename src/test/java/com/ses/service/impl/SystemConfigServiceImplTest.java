package com.ses.service.impl;

import com.ses.entity.SystemConfig;
import com.ses.mapper.SystemConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * システム設定サービスの単体テスト（P8 Task8）。
 * 型付きアクセサのデフォルト値・数値変換・put後のキャッシュ更新を検証する。
 */
class SystemConfigServiceImplTest {

    private SystemConfigMapper mapper;
    private SystemConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(SystemConfigMapper.class);
        service = new SystemConfigServiceImpl(mapper);
    }

    private void seed(SystemConfig... configs) {
        when(mapper.selectList(isNull())).thenReturn(new ArrayList<>(List.of(configs)));
    }

    @Test
    void getInt_存在すれば数値を返す() {
        seed(new SystemConfig("notice.contract-end-days", "45", "desc"));
        assertEquals(45, service.getInt("notice.contract-end-days", 30));
    }

    @Test
    void getInt_未定義はデフォルトを返す() {
        seed();
        assertEquals(30, service.getInt("notice.contract-end-days", 30));
    }

    @Test
    void getInt_数値でない値はデフォルトを返す() {
        seed(new SystemConfig("bad", "abc", "desc"));
        assertEquals(7, service.getInt("bad", 7));
    }

    @Test
    void getDecimal_小数を返す() {
        seed(new SystemConfig("billing.tax-rate", "0.08", "desc"));
        assertEquals(new BigDecimal("0.08"), service.getDecimal("billing.tax-rate", new BigDecimal("0.10")));
    }

    @Test
    void put_後はキャッシュから新しい値を返す() {
        seed(new SystemConfig("company_name", "Old", "会社名"));
        when(mapper.selectById("company_name")).thenReturn(new SystemConfig("company_name", "Old", "会社名"));

        assertEquals("Old", service.getString("company_name", ""));

        service.put("company_name", "New", "会社名");

        assertEquals("New", service.getString("company_name", ""), "putでキャッシュが更新される");
        verify(mapper).updateById(any(SystemConfig.class));
    }

    @Test
    void put_新規キーはinsertされる() {
        seed();
        when(mapper.selectById("notification.webhook-url")).thenReturn(null);

        service.put("notification.webhook-url", "v", "d");

        verify(mapper).insert(any(SystemConfig.class));
        assertEquals("v", service.getString("notification.webhook-url", "def"));
    }

    @Test
    void put_rollback時は更新前のキャッシュ値を復元する() {
        seed(new SystemConfig("company_name", "Old", "会社名"));
        when(mapper.selectById("company_name")).thenReturn(new SystemConfig("company_name", "Old", "会社名"));
        assertEquals("Old", service.getString("company_name", ""));

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.put("company_name", "New", "会社名");
            assertEquals("Old", service.getString("company_name", ""), "commit前は旧値を読む");
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertEquals("Old", service.getString("company_name", ""), "rollback後も旧キャッシュを維持する");
    }
}
