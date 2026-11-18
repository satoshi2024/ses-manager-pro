-- ============================================================
-- V193: NF-06 取込センター画面の menu と import.metadata.manage の明示許可（W3-12）。
--
-- 1. menu 'import-center'（Feature.NF06 の menu key と一致させる）。管理者 role だけへ付与する。
--    path_prefix は画面 '/import-center'。api_prefix は '/api/import-center/sources' にする
--    （'/api/import-center' 自体は公開routeでないため ActionPermissionResolver が null を返し、
--    MenuPermissionFilter が管理者を含む全roleを403にする。MigrationScriptIntegrityTest が固定）。
--    sort_order は未使用値 262（V191 の 260 / 261 の次）。
-- 2. 取込 metadata の service 層（ImportMetadataAuthorizationService）は import.metadata.manage の
--    明示許可行を要求し、'*' を許可根拠に数えない。管理者専用 group（role-admin・privacy-admin）にだけ
--    明示許可を入れる（W3-05 review P2-3(a)。privacy-admin は role-admin の代わりに割り当てる管理者 group のため）。
-- 3. それ以外の全 group では deny する（baseline + deny。V191 の privacy.* と同型。tenant・enabled を問わない）。
--    action 層は管理者 role 以外を group で評価し、service 層は管理者 role を要求するため、deny は多重防御である。
--
-- 本 migration は利用者を group へ割り当てない。NF-06 の公開は機能スイッチ（FEATURE_NF06_ENABLED、既定 OFF）と
-- admission 承認設定（a1a-ingress-operations.md §10）で別に制御する。group 作成・action 編集の API は無いため、
-- 今後 group を追加する migration・運用作業は、管理者専用でなければ import.metadata.manage の deny 行も入れること。
-- ============================================================

-- ------------------------------------------------------------
-- 1. menu と role 付与（管理者のみ）
-- ------------------------------------------------------------
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'import-center', '取込センター', '/import-center', '/api/import-center/sources', 262
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'import-center');

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', m.id
FROM m_menu m
WHERE m.menu_key = 'import-center'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu r WHERE r.role = '管理者' AND r.menu_id = m.id);

-- ------------------------------------------------------------
-- 2. 管理者専用 group へ明示許可。既に同じ key の行があれば許可・有効へ揃える。
-- ------------------------------------------------------------
UPDATE t_permission_group_action a
JOIN m_permission_group g ON g.id = a.group_id AND g.tenant_id = a.tenant_id
SET a.deny_flag = 0, a.deleted_flag = 0, a.updated_at = NOW()
WHERE g.group_key IN ('role-admin', 'privacy-admin')
  AND a.action_key = 'import.metadata.manage';

INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, created_at, updated_at, deleted_flag)
SELECT g.tenant_id, g.id, 'import.metadata.manage', 0, NOW(), NOW(), 0
FROM m_permission_group g
WHERE g.group_key IN ('role-admin', 'privacy-admin')
  AND NOT EXISTS (SELECT 1 FROM t_permission_group_action x
                  WHERE x.tenant_id = g.tenant_id AND x.group_id = g.id
                    AND x.action_key = 'import.metadata.manage');

-- ------------------------------------------------------------
-- 3. 管理者専用 group 以外の全 group で deny
-- ------------------------------------------------------------
UPDATE t_permission_group_action a
JOIN m_permission_group g ON g.id = a.group_id
SET a.deny_flag = 1, a.deleted_flag = 0, a.updated_at = NOW()
WHERE a.action_key = 'import.metadata.manage'
  AND g.group_key NOT IN ('role-admin', 'privacy-admin');

INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, created_at, updated_at, deleted_flag)
SELECT g.tenant_id, g.id, 'import.metadata.manage', 1, NOW(), NOW(), 0
FROM m_permission_group g
WHERE g.group_key NOT IN ('role-admin', 'privacy-admin')
  AND NOT EXISTS (SELECT 1 FROM t_permission_group_action x
                  WHERE x.tenant_id = g.tenant_id AND x.group_id = g.id
                    AND x.action_key = 'import.metadata.manage');
