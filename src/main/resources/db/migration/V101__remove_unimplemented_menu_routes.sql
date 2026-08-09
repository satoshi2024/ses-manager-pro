-- V101: 未実装ページのメニュー登録を撤去する
-- 横断検索 / タスク / スキルタグ / 保存ビュー / 一括操作 はバックエンドAPIのみ実装済みで、
-- 対応するページ画面が未実装のまま m_menu / t_role_menu に登録されていた。
-- MenuPermissionFilter がメニュー権限を強制するため、メニューに残したままでは
-- 直接URLで404/403に当たり、権限データと実装が乖離する。
-- APIアクセスは t_permission_group_action（V66_1 / V74 で search.* task.* skill-tag.*
-- saved-view.* batch-operation.* を付与済み）が引き続き許可するため、
-- 既存機能（横断検索・ToDo・スキル選択）には影響しない。
-- ページUIを実装した際に、改めて m_menu / t_role_menu へ登録すること。

DELETE FROM t_role_menu
WHERE menu_id IN (
    SELECT id FROM m_menu
    WHERE menu_key IN ('search', 'tasks', 'skill-tag', 'saved-views', 'batch-operations')
);

DELETE FROM m_menu
WHERE menu_key IN ('search', 'tasks', 'skill-tag', 'saved-views', 'batch-operations');
