-- ============================================================
-- SES Manager Pro - 見積書発行（quotation-management / P4）
-- ファイル: V29__quotation.sql
-- 説明: 見積テーブル t_quotation、契約への生成元見積参照、見積メニューを追加する。
-- ============================================================

CREATE TABLE t_quotation (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  quotation_no          VARCHAR(30) NOT NULL UNIQUE COMMENT '見積番号(Q-YYYYMM-NNNN)',
  customer_id           BIGINT NOT NULL,
  project_id            BIGINT NULL,
  engineer_id           BIGINT NULL,
  proposal_id           BIGINT NULL COMMENT '任意紐付け(参照のみ)',
  title                 VARCHAR(200) NOT NULL COMMENT '件名',
  unit_price            DECIMAL(10,0) NOT NULL COMMENT '単価(円/月)',
  settlement_hours_min  DECIMAL(5,1) NULL,
  settlement_hours_max  DECIMAL(5,1) NULL,
  valid_until           DATE NULL COMMENT '有効期限',
  status                ENUM('下書き','提出済','受注','失注') NOT NULL DEFAULT '下書き',
  remarks               VARCHAR(500),
  created_by            BIGINT,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT DEFAULT 0
) COMMENT='見積';

ALTER TABLE t_contract ADD COLUMN quotation_id BIGINT NULL COMMENT '生成元見積(見積受注からのドラフトのみ)';

-- 見積管理メニュー（管理者・営業・マネージャー）
INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('quotation', '見積管理', '/quotation', '/api/quotations', 50);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'quotation'
UNION ALL SELECT '営業', id FROM m_menu WHERE menu_key = 'quotation'
UNION ALL SELECT 'マネージャー', id FROM m_menu WHERE menu_key = 'quotation';
