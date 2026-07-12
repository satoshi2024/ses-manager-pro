package com.ses.service.impl;

import com.ses.entity.SystemConfig;
import com.ses.mapper.SystemConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
        seed(new SystemConfig("company.name", "Old", "会社名"));
        when(mapper.selectById("company.name")).thenReturn(new SystemConfig("company.name", "Old", "会社名"));

        assertEquals("Old", service.getString("company.name", ""));

        service.put("company.name", "New", "会社名");

        assertEquals("New", service.getString("company.name", ""), "putでキャッシュが更新される");
        verify(mapper).updateById(any(SystemConfig.class));
    }

    @Test
    void put_新規キーはinsertされる() {
        seed();
        when(mapper.selectById("new.key")).thenReturn(null);

        service.put("new.key", "v", "d");

        verify(mapper).insert(any(SystemConfig.class));
        assertEquals("v", service.getString("new.key", "def"));
    }
}
