package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.dto.engineer.EngineerListDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 要員一覧の「ログインアカウント紐付け」絞り込みをH2上で検証する。
 *
 * <p>要員セルフサービス勤怠は紐付けが無いと要員側で常に403になるが、管理者には
 * 未紐付けの要員を探す手段が無かった。その絞り込みが実SQLで正しく効くことを固定する。
 *
 * <p>特に未紐付け側は {@code NOT IN (副問い合わせ)} であり、副問い合わせが1行も返さない
 * 場合に全件が返ること（＝空集合に対する NOT IN が TRUE）を明示的に確認する。ここを
 * 取り違えると「紐付けが1件も無い環境で未紐付け検索が0件になる」という、最も困る場面で
 * 黙って壊れる。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/sql/engineer-schema-h2.sql")
@Transactional
class EngineerAccountLinkFilterIntegrationTest {

    @Autowired
    private EngineerApiController controller;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long linkedEngineerId;
    private Long unlinkedEngineerId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM t_engineer_account_link");
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, deleted_flag) "
                + "VALUES ('紐付け済太郎', '正社員', 'Bench', 0)");
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, deleted_flag) "
                + "VALUES ('未紐付け花子', '正社員', 'Bench', 0)");
        linkedEngineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = '紐付け済太郎'", Long.class);
        unlinkedEngineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = '未紐付け花子'", Long.class);
    }

    private List<EngineerListDto> search(Boolean accountLinked) {
        ApiResult<Page<EngineerListDto>> result =
                controller.page(1, 100, null, null, null, null, null, null, accountLinked);
        return result.getData().getRecords();
    }

    private void link(Long engineerId, Long sysUserId) {
        jdbcTemplate.update("INSERT INTO t_engineer_account_link (engineer_id, sys_user_id) VALUES (?, ?)",
                engineerId, sysUserId);
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void 紐付けが1件も無いとき未紐付け検索は全件を返す() {
        List<Long> ids = search(false).stream().map(e -> e.getId()).toList();

        assertTrue(ids.contains(linkedEngineerId), "紐付けが無いので両方とも未紐付け扱いになること");
        assertTrue(ids.contains(unlinkedEngineerId));
        assertTrue(search(true).isEmpty(), "紐付け済み検索は0件になること");
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void 紐付け済みと未紐付けを分けて絞り込める() {
        link(linkedEngineerId, 9001L);

        List<Long> linkedIds = search(true).stream().map(e -> e.getId()).toList();
        assertEquals(List.of(linkedEngineerId), linkedIds);

        List<Long> unlinkedIds = search(false).stream().map(e -> e.getId()).toList();
        assertTrue(unlinkedIds.contains(unlinkedEngineerId));
        assertFalse(unlinkedIds.contains(linkedEngineerId));
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void 絞り込み未指定なら両方返り各行に紐付け有無が付く() {
        link(linkedEngineerId, 9001L);

        List<EngineerListDto> all = search(null);
        assertTrue(all.size() >= 2);
        for (EngineerListDto dto : all) {
            if (dto.getId().equals(linkedEngineerId)) {
                assertTrue(dto.getAccountLinked(), "紐付け済みの行は true");
            } else if (dto.getId().equals(unlinkedEngineerId)) {
                assertFalse(dto.getAccountLinked(), "未紐付けの行は false");
            }
        }
    }
}
