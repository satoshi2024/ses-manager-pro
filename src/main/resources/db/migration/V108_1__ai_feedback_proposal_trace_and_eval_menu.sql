-- ============================================================
-- SES Manager Pro - AI feedback learning follow-up (T112 / T114)
-- ファイル: V108_1__ai_feedback_proposal_trace_and_eval_menu.sql
-- 説明: 提案へ AI trace を保存し、評価ダッシュボード用メニューを追加する。
-- V108 は変更しない。後続は V108_1。欠番埋めはしない。
-- ============================================================

ALTER TABLE t_proposal
    ADD COLUMN ai_trace_id VARCHAR(36) NULL COMMENT 'AI推薦 run.trace_id' AFTER closed_at,
    ADD COLUMN ai_item_id BIGINT NULL COMMENT 'AI推薦 item.id' AFTER ai_trace_id;

CREATE INDEX idx_proposal_ai_item ON t_proposal (ai_item_id);
CREATE INDEX idx_proposal_ai_trace ON t_proposal (ai_trace_id);

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('ai-evaluation', 'AI評価', '/ai/evaluation', '/api/ai/evaluations', 73);

INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'マネージャー' UNION ALL SELECT '営業') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'ai-evaluation'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu tr WHERE tr.role = r.role AND tr.menu_id = m.id);
