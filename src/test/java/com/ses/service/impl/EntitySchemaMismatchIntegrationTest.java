package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.entity.AiLog;
import com.ses.entity.EmailTemplate;
import com.ses.entity.Proposal;
import com.ses.entity.SkillTag;
import com.ses.mapper.AiLogMapper;
import com.ses.service.EmailTemplateService;
import com.ses.service.ProposalService;
import com.ses.service.SkillTagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BaseEntity が前提とする共通カラム（created_at / updated_at / deleted_flag）を
 * 持たないテーブルに対して、CRUD が実際に成功することを H2 上で検証する結合テスト。
 *
 * これらの操作は、エンティティ側で存在しない共通カラムを無効化していないと
 * 「Unknown column」系の SQL エラーになる（＝本番 MySQL で機能が壊れる）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/sql/schema-mismatch-h2.sql")
class EntitySchemaMismatchIntegrationTest {

    @Autowired
    private ProposalService proposalService;

    @Autowired
    private SkillTagService skillTagService;

    @Autowired
    private EmailTemplateService emailTemplateService;

    @Autowired
    private AiLogMapper aiLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void proposal_save_は_created_at列が無くても成功する() {
        Proposal p = new Proposal();
        p.setEngineerId(1L);
        p.setProjectId(1L);
        p.setStatus("書類選考中");
        p.setProposedUnitPrice(BigDecimal.valueOf(80));

        assertTrue(proposalService.save(p), "提案の保存が成功すること");
        assertNotNull(p.getId(), "自動採番されたIDが取得できること");
        assertNotNull(proposalService.getById(p.getId()), "保存した提案が取得できること");
    }

    @Test
    void aiLog_selectList_は_deleted_flag列が無くても例外にならない() {
        jdbcTemplate.update(
                "INSERT INTO t_ai_log (request_type, response_text, created_at) VALUES (?, ?, ?)",
                "マッチング", "ok", LocalDateTime.now());

        List<AiLog> logs = aiLogMapper.selectList(new QueryWrapper<>());
        assertEquals(1, logs.size(), "AIログが取得できること（論理削除フィルタで壊れない）");
        assertNotNull(logs.get(0).getCreatedAt(), "created_at がマッピングされること");
    }

    @Test
    void skillTag_save_と_list_は_共通カラムが無くても成功する() {
        SkillTag tag = new SkillTag();
        tag.setSkillName("Java");
        tag.setCategory("言語");

        assertTrue(skillTagService.save(tag), "スキルタグの保存が成功すること");
        assertNotNull(tag.getId());
        assertEquals(1, skillTagService.list().size(), "一覧に反映されること");
    }

    @Test
    void emailTemplate_save_list_removeById_は_deleted_flag列が無くても成功する() {
        EmailTemplate tpl = new EmailTemplate();
        tpl.setTemplateName("提案テンプレ");
        tpl.setSubjectTemplate("件名");
        tpl.setBodyTemplate("本文");
        tpl.setTemplateType("提案");

        assertTrue(emailTemplateService.save(tpl), "テンプレートの保存が成功すること");
        assertNotNull(tpl.getId());
        assertEquals(1, emailTemplateService.list().size(), "一覧に反映されること");

        assertTrue(emailTemplateService.removeById(tpl.getId()), "削除が成功すること");
        assertTrue(emailTemplateService.list().isEmpty(), "削除後は一覧が空になること");
    }
}
