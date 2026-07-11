package com.ses.service.impl;

import com.ses.entity.Engineer;
import com.ses.service.EngineerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 削除機能が「本物か」を実際にH2上で検証する結合テスト。
 * コントローラーが呼ぶ engineerService.removeById(id) を直接叩き、
 * 論理削除（ソフトデリート）が実際に効いていることを確認する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class EngineerDeleteIntegrationTest {

    @Autowired
    private EngineerService engineerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void removeById_はソフトデリートを実行し_行は残るが取得できなくなる() {
        // 1. 登録（DELETE APIと同じ save 経路）
        Engineer e = new Engineer();
        e.setFullName("削除検証太郎");
        e.setEmploymentType("正社員");
        e.setStatus("Bench");
        assertTrue(engineerService.save(e), "保存が成功すること");

        Long id = e.getId();
        assertNotNull(id, "自動採番されたIDが取得できること");

        // 2. 削除前は取得できる
        assertNotNull(engineerService.getById(id), "削除前は getById で取得できること");

        // 3. 削除（DELETE /api/engineers/{id} が呼ぶ removeById と同一）
        assertTrue(engineerService.removeById(id), "removeById が true を返すこと");

        // 4. 削除後はアプリからは一切見えない（論理削除フィルタが効く）
        assertNull(engineerService.getById(id), "削除後は getById で取得できないこと");
        Long visibleCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_engineer WHERE id = ? AND deleted_flag = 0",
                Long.class, id);
        assertEquals(0L, visibleCount, "有効データとしては存在しないこと");

        // 5. 物理的には行が残り deleted_flag = 1（＝ソフトデリートである証拠）
        Long rawCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_engineer WHERE id = ?", Long.class, id);
        assertEquals(1L, rawCount, "DB上は行が物理的に残っていること");

        Integer flag = jdbcTemplate.queryForObject(
                "SELECT deleted_flag FROM t_engineer WHERE id = ?", Integer.class, id);
        assertEquals(1, flag, "deleted_flag が 1 に更新されていること（ソフトデリート）");
    }
}
