-- ==========================================================
-- V74: CRM(S08) の action permission 付与と、S05/S06 の付与漏れ補完
-- spec: crm-contact-opportunity (S08 / R08 Round 2 CRM-R2-P1-01)
--
-- 【V73のコメントの訂正】
-- V73 §7 のコメントは「ActionPermissionResolver が URI から機械的に導出するため
-- (`/api/crm/leads` → `crm.view`)、baseline+deny 方式のまま追加のseedは不要」と書いているが、
-- これは **2点とも誤り**である。V73は適用済みでchecksumを壊せないため、訂正を本スクリプトへ残す。
--
--   1. `ActionPermissionResolver` は機械導出ではなく **RESOURCE_NAMES への明示登録制**で、
--      未登録rootでは `resolve()` が null を返す。null になると `MenuPermissionFilter` が
--      `/api/**` を deny() し、page も matchedMenu.api_prefix から再解決して null なら deny() する。
--      この deny は **管理者bypassより前**にあるため、管理者すら403になる。
--      → `Map.entry("crm", "crm")` を同一変更で追加した。
--
--   2. 権限modelは V66_1 以降 **baseline(`*`) ではなく既知resource wildcardの列挙**である
--      （V66_1が非管理者groupから全局 `*` を削除し、`dashboard.*` 等の列挙へ置換した）。
--      したがって新しいresource rootを足したら、そのgroupへ `<resource>.*` をseedしなければ
--      group割当済みの非管理者は拒否される。V67の `document.*` だけがこれを守っていた。
--
-- 【S05/S06の付与漏れ（本specの発見）】
-- 上記2の帰結として、V68/V69が足した `search` / `task` / `saved-view` / `batch-operation`、
-- V70が足した `bp-company` は `RESOURCE_NAMES` に登録済みだが `t_permission_group_action`
-- へのseedが無く、**group割当済みの営業/HR/マネージャーは出荷済み機能で403**になる。
-- 同じ1行機構で塞げるため本スクリプトで併せて補完する。付与先は各specのmenu付与に合わせる。
--
--   - crm.*            : role-sales / role-manager        （V73のmenu付与＝管理者/マネージャー/営業。design §6.2でHR・要員は不可視）
--   - search.* task.* saved-view.* batch-operation.*
--                      : role-sales / role-hr / role-manager（V69のmenu付与＝全5role。要員はSecurityConfigの
--                        anyRequestで/my/**以外へ到達できないためgroup付与しない）
--   - bp-company.*     : role-sales / role-manager        （V70のmenu付与＝管理者/営業/マネージャー）
--
-- role-admin は `*` を持つため個別seed不要。
-- ==========================================================

-- CRM (S08): 営業・マネージャーのみ
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (SELECT 'crm.*' AS action_key) a
WHERE g.tenant_id = 'default'
  AND g.enabled = 1
  AND g.group_key IN ('role-sales', 'role-manager');

-- 生産性向上機能 (S05 / V68・V69): 営業・HR・マネージャー
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (
    SELECT 'search.*' AS action_key UNION ALL
    SELECT 'task.*' UNION ALL
    SELECT 'saved-view.*' UNION ALL
    SELECT 'batch-operation.*'
) a
WHERE g.tenant_id = 'default'
  AND g.enabled = 1
  AND g.group_key IN ('role-sales', 'role-hr', 'role-manager');

-- BP会社マスタ (S06 / V70): 営業・マネージャー
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (SELECT 'bp-company.*' AS action_key) a
WHERE g.tenant_id = 'default'
  AND g.enabled = 1
  AND g.group_key IN ('role-sales', 'role-manager');
