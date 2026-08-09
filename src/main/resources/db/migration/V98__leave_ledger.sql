-- ============================================================
-- V98: 休暇残数台帳（S11 / T071 / 発注者V98割当）
-- G6決定により休暇残数のsystem of recordは本システム。
-- V91実在とV92〜V97（旧S12〜S17予約）の繰上げV99〜V104に続く、
-- 発注者割当の未使用version V98として適用する。前の欠番は埋めない。
-- ============================================================
-- t_leave_ledger: 付与（GRANT）/消化（CONSUME）の追記型台帳。
-- 残数 = ΣGRANT − ΣCONSUME（要員×休暇種別、申請日時点）。
-- 消化行は休暇承認時にアプリケーション層でINSERTし、却下/取下/取消で戻す。
-- 重複・負数・不正種別はDB制約とアプリケーション層の両方でfail-closedにする。
CREATE TABLE IF NOT EXISTS t_leave_ledger (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id BIGINT NOT NULL COMMENT '要員ID',
  legal_entity_id BIGINT COMMENT '法人ID（scope snapshot）',
  leave_type VARCHAR(30) NOT NULL COMMENT '有給/半休/時間休/代休/欠勤/特別休暇',
  ledger_type VARCHAR(20) NOT NULL COMMENT 'GRANT（付与）/CONSUME（消化）',
  amount_minutes INT NOT NULL COMMENT '分単位の付与/消化量（正の整数）',
  entry_date DATE NOT NULL COMMENT '付与/消化の発生日',
  leave_request_id BIGINT COMMENT '消化の由来となる休暇申請ID（GRANTはNULL）',
  source VARCHAR(20) NOT NULL DEFAULT 'manual' COMMENT 'manual/system/import',
  source_external_id VARCHAR(200) COMMENT '外部sourceの冪等ID',
  remarks VARCHAR(500) COMMENT '備考',
  version INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  UNIQUE KEY uk_leave_ledger_source (source, source_external_id),
  INDEX idx_leave_ledger_engineer_type (engineer_id, leave_type, entry_date),
  INDEX idx_leave_ledger_request (leave_request_id),
  CONSTRAINT chk_leave_ledger_type CHECK (ledger_type IN ('GRANT', 'CONSUME')),
  CONSTRAINT chk_leave_ledger_amount CHECK (amount_minutes > 0),
  CONSTRAINT chk_leave_ledger_source CHECK (
    (source IN ('manual', 'system') AND source_external_id IS NULL)
    OR (source = 'import' AND source_external_id IS NOT NULL)
  ),
  CONSTRAINT fk_leave_ledger_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_leave_ledger_request FOREIGN KEY (leave_request_id) REFERENCES t_leave_request(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='休暇残数付与/消化台帳（G6: 本システムが正）';

-- 休暇運用パラメータ（INSERT IGNOREで既存の管理者変更を上書きしない）。
-- leave.balance.source: G6決定によりinternalが既定。externalへ切替可。未設定は判定不能finding。
-- leave.balance.types: 残数チェック対象種別（欠勤は無給のため対象外）。
-- leave.sales-notification.types: 客先報告が必要な休暇種別（R2.3の営業通知分岐）。
INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('leave.balance.source', 'internal', '休暇残数の正（internal=本システム台帳/G6既定、external=外部人事システム参照のみ）'),
  ('leave.balance.types', '有給,半休,時間休,代休,特別休暇', '残数チェック対象の休暇種別（欠勤は無給のため対象外）'),
  ('leave.sales-notification.types', '有給,特別休暇', '客先報告が必要な休暇種別（承認時に担当営業へ通知）');

-- 休暇メニュー（本人申請/管理）。api_prefixはActionPermissionResolverへ'leave' rootを登録する。
-- 営業は勤怠・休暇のscopeを持たず、客先報告が必要な休暇の通知だけを受ける（design §5.3）ため
-- leaveManagementは管理者/HR/マネージャーのみへ付与し、'leave.*'権限seedも営業groupへ入れない。
INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) VALUES
  ('myLeave', '休暇申請', '/my/leave', '/api/my/leave', 60),
  ('leaveManagement', '休暇管理', '/leave', '/api/leave', 61);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT '要員') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'myLeave';

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'HR' UNION ALL SELECT 'マネージャー') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'leaveManagement';

-- 休暇resourceの権限seed（baseline+deny方式のため、group割当済み非管理者はseedが無いと403になる）。
-- 営業はdesign §5.3により対象外。
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (SELECT 'leave.*' AS action_key) a
WHERE g.tenant_id = 'default'
  AND g.enabled = 1
  AND g.group_key IN ('role-hr', 'role-manager');
