-- ============================================================
-- V191: NF07 W-5 権限（W3-14）。production-bridge-phased-plan.md §6.3。
--
-- baseline + deny に従う。privacy.* は privacy-admin（管理者専用 group）だけが許可し、
-- それ以外の全 group（全体 baseline '*' を持つ role-admin を含む）では deny する。
-- privacy の判定では '*' を許可の根拠に数えない（AuthorizationServiceImpl）ため、privacy-admin の
-- 許可根拠は明示の 'privacy.*' 行だけである。prod 起動時は ProductionSecurityConfigurationValidator が
-- 「'*' を持つ group（privacy-admin 以外）は全て privacy.* の deny を持つ」ことを検査する（本 migration と同じ commit）。
--
-- group 作成・action 編集の API は無いため、group は migration でしか増えない。今後 group を追加する
-- migration・運用作業は privacy.* の deny 行も入れること（漏れは上記の起動時検査が検出する）。
-- 本 migration は利用者を privacy-admin へ割り当てない（割当は PermissionGroupManagementServiceImpl の
-- 監査・通知付きの経路だけで行う）。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 管理者専用 group。role-admin を持つ全 tenant に作る（新規導入では 'default' のみ）。
-- ------------------------------------------------------------
INSERT INTO m_permission_group (tenant_id, group_key, group_name, description, enabled, created_at, updated_at, deleted_flag)
SELECT g.tenant_id, 'privacy-admin', 'プライバシー管理（管理者専用）',
       'NF07 privacy.*。管理者roleのユーザーにだけ、role-adminの代わりに割り当てる', 1, NOW(), NOW(), 0
FROM m_permission_group g
WHERE g.group_key = 'role-admin'
  AND NOT EXISTS (SELECT 1 FROM m_permission_group e WHERE e.tenant_id = g.tenant_id AND e.group_key = 'privacy-admin');

-- ------------------------------------------------------------
-- 2. privacy-admin は role-admin と同じ baseline（'*'）に privacy.* を明示で加える。
--    既に同じ key の行があれば許可・有効へ揃える（拒否行や論理削除行が残っていても許可根拠を確定させる）。
-- ------------------------------------------------------------
UPDATE t_permission_group_action a
JOIN m_permission_group g ON g.id = a.group_id AND g.tenant_id = a.tenant_id
SET a.deny_flag = 0, a.deleted_flag = 0, a.updated_at = NOW()
WHERE g.group_key = 'privacy-admin'
  AND a.action_key IN ('*', 'privacy.*');

INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, created_at, updated_at, deleted_flag)
SELECT g.tenant_id, g.id, a.action_key, 0, NOW(), NOW(), 0
FROM m_permission_group g
JOIN (SELECT '*' AS action_key UNION ALL SELECT 'privacy.*') a
WHERE g.group_key = 'privacy-admin'
  AND NOT EXISTS (SELECT 1 FROM t_permission_group_action x
                  WHERE x.tenant_id = g.tenant_id AND x.group_id = g.id AND x.action_key = a.action_key);

-- ------------------------------------------------------------
-- 3. privacy-admin 以外の全 group で privacy.* を deny（V110 と同型。tenant・enabled を問わない）。
-- ------------------------------------------------------------
UPDATE t_permission_group_action a
JOIN m_permission_group g ON g.id = a.group_id
SET a.deny_flag = 1, a.deleted_flag = 0, a.updated_at = NOW()
WHERE a.action_key = 'privacy.*'
  AND g.group_key <> 'privacy-admin';

INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, created_at, updated_at, deleted_flag)
SELECT g.tenant_id, g.id, 'privacy.*', 1, NOW(), NOW(), 0
FROM m_permission_group g
WHERE g.group_key <> 'privacy-admin'
  AND NOT EXISTS (SELECT 1 FROM t_permission_group_action x
                  WHERE x.tenant_id = g.tenant_id AND x.group_id = g.id AND x.action_key = 'privacy.*');

-- ------------------------------------------------------------
-- 4. menu を機能ごとに2つ（Feature の menu key と一致させる）。到達可否は action 層と SecurityConfig が決める。
--    sort_order は既存の 97 と重複するので未使用値 260 / 261。画面・API は後続 batch（A1H・A2a）が追加する。
-- ------------------------------------------------------------
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'privacy-retention', 'プライバシー保全', '/privacy/holds', '/api/privacy/holds', 260
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'privacy-retention');

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'privacy-dsar', '本人請求（DSAR）', '/privacy/dsar', '/api/privacy/dsar', 261
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'privacy-dsar');

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', m.id
FROM m_menu m
WHERE m.menu_key IN ('privacy-retention', 'privacy-dsar')
  AND NOT EXISTS (SELECT 1 FROM t_role_menu r WHERE r.role = '管理者' AND r.menu_id = m.id);
