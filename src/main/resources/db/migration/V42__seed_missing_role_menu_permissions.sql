-- V42: スキルタグメニュー登録および管理者ロール権限補完
INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('skill-tag', 'スキルタグ管理', '/skill-tag', '/api/skill-tags', 120);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key IN ('contract-document', 'skill-tag');

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT 'マネージャー', id FROM m_menu WHERE menu_key IN ('skill-tag');

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT 'HR', id FROM m_menu WHERE menu_key IN ('skill-tag');

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT '営業', id FROM m_menu WHERE menu_key IN ('skill-tag');
