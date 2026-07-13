package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.entity.AuditLog;
import com.ses.mapper.AuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 監査ログサービスの単体テスト（P8フォローアップ・提案11）。
 * 記録の成功、記録失敗時に呼び出し元へ例外を伝播しないことを検証する。
 */
class AuditLogServiceImplTest {

    private AuditLogMapper auditLogMapper;
    private AuditLogServiceImpl service;

    @BeforeEach
    void setUp() {
        auditLogMapper = Mockito.mock(AuditLogMapper.class);
        service = new AuditLogServiceImpl(auditLogMapper);
    }

    @Test
    void record_正常時はinsertが呼ばれる() {
        service.record("admin", "POST", "/api/engineers", 200);
        verify(auditLogMapper, times(1)).insert(any(AuditLog.class));
    }

    @Test
    void record_DB書き込み失敗しても例外を伝播しない() {
        when(auditLogMapper.insert(any(AuditLog.class))).thenThrow(new RuntimeException("DB down"));

        assertDoesNotThrow(() -> service.record("admin", "POST", "/api/engineers", 500));
    }

    @Test
    void page_マッパーのselectPageへ委譲する() {
        when(auditLogMapper.selectPage(any(), any())).thenReturn(new Page<>());

        service.page(1, 10, "admin", "POST");

        verify(auditLogMapper, times(1)).selectPage(any(), any());
    }
}
