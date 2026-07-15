-- ============================================================
-- SES Manager Pro - 要員担当営業・営業成績/インセンティブ管理
-- 説明: 要員↔営業の担当関連テーブル、契約への成約担当帰属カラム、
--       インセンティブ既定規則の設定キー、営業成績メニューを追加
-- 仕様: .kiro/specs/engineer-sales-commission/
-- ============================================================

-- 要員担当営業（履歴は released_at で表現。NULL=現任）
CREATE TABLE t_engineer_sales (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  engineer_id   BIGINT NOT NULL COMMENT '要員ID',
  sales_user_id BIGINT NOT NULL COMMENT '担当営業ユーザーID',
  primary_flag  TINYINT NOT NULL DEFAULT 0 COMMENT '主担当フラグ(1:主担当)',
  assigned_at   DATE NOT NULL COMMENT '担当開始日',
  released_at   DATE NULL COMMENT '担当解除日(NULL=現任)',
  remarks       VARCHAR(500) COMMENT '備考',
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag  TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  INDEX idx_engsales_engineer (engineer_id),
  INDEX idx_engsales_sales_user (sales_user_id),
  CONSTRAINT fk_engsales_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id) ON DELETE RESTRICT,
  CONSTRAINT fk_engsales_sales_user FOREIGN KEY (sales_user_id) REFERENCES sys_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='要員担当営業';

-- 契約: 成約担当営業とインセンティブ個別上書き
ALTER TABLE t_contract
  ADD COLUMN sales_user_id BIGINT NULL COMMENT '成約担当営業ID' AFTER customer_id,
  ADD COLUMN commission_base_type VARCHAR(10) NULL COMMENT 'インセンティブ基準上書き(粗利/売上、NULL=既定)' AFTER remarks,
  ADD COLUMN commission_rate DECIMAL(5,2) NULL COMMENT 'インセンティブ率上書き(%、NULL=既定)' AFTER commission_base_type;

ALTER TABLE t_contract
  ADD INDEX idx_contract_sales_user (sales_user_id),
  ADD CONSTRAINT fk_contract_sales_user FOREIGN KEY (sales_user_id) REFERENCES sys_user(id) ON DELETE RESTRICT;

-- インセンティブ既定規則（管理者が /system-config から変更可能）
INSERT INTO m_system_config (config_key, config_value, description) VALUES
  ('commission.base-type', '粗利', 'インセンティブ計算基準（粗利 または 売上）'),
  ('commission.rate',      '5.0',  'インセンティブ既定率（%）')
AS new(config_key, config_value, description)
ON DUPLICATE KEY UPDATE description = new.description;

-- 営業成績メニュー（管理者・営業・マネージャー）
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('sales-performance', '営業成績', '/sales-performance', '/api/sales-performance', 66)
AS new(menu_key, menu_name, path_prefix, api_prefix, sort_order)
ON DUPLICATE KEY UPDATE menu_name = new.menu_name;

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'sales-performance'
UNION ALL SELECT '営業', id FROM m_menu WHERE menu_key = 'sales-performance'
UNION ALL SELECT 'マネージャー', id FROM m_menu WHERE menu_key = 'sales-performance';
