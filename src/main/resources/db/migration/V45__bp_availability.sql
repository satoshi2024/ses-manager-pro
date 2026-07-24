-- ===========================================
-- V45: 要員空き状況メール取込
-- ===========================================
CREATE TABLE t_bp_availability (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  initial_name       VARCHAR(50) NULL COMMENT 'イニシャル',
  bp_company         VARCHAR(120) NULL COMMENT '所属BP',
  skills_json        LONGTEXT NULL COMMENT 'スキル配列(JSON)',
  unit_price         BIGINT NULL COMMENT '単価(円)',
  available_from     DATE NULL COMMENT '稼働開始可能日',
  experience_years   INT NULL,
  status             VARCHAR(20) NOT NULL DEFAULT '提案可能' COMMENT '提案可能/失効/要員化済',
  promoted_engineer_id BIGINT NULL COMMENT '昇格先 t_engineer.id',
  remarks            VARCHAR(500) NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  created_by BIGINT NULL,
  INDEX idx_bpa_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部要員在庫';

CREATE TABLE t_bp_availability_ingestion (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  original_file_name    VARCHAR(255) NULL COMMENT 'アップロード時の元ファイル名',
  stored_file_name      VARCHAR(120) NULL COMMENT '保存名(UUID.ext)。/api/files で参照',
  file_ext              VARCHAR(10)  NOT NULL COMMENT '拡張子(pdf/xlsx/docx/eml)',
  status                VARCHAR(20)  NOT NULL DEFAULT '取込待ち' COMMENT '取込待ち/抽出中/要確認/確定済/却下/失敗',
  extracted_text        LONGTEXT     NULL COMMENT '抽出したプレーンテキスト(PII)',
  parsed_json           LONGTEXT     NULL COMMENT 'AI構造化結果 + レビュー編集後の内容(JSON)',
  ai_provider           VARCHAR(30)  NULL COMMENT '解析に使ったプロバイダ(gemini/mock等)',
  ai_model              VARCHAR(60)  NULL COMMENT '解析に使ったモデル',
  error_message         VARCHAR(500) NULL COMMENT '失敗理由(サニタイズ済み)',
  converted_availability_id BIGINT   NULL COMMENT '確定で生成した t_bp_availability.id',
  review_note           VARCHAR(500) NULL COMMENT 'レビュー担当メモ',
  created_at            DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT      NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  created_by            BIGINT       NULL COMMENT '取込実行ユーザー',
  INDEX idx_bpai_status (status),
  INDEX idx_bpai_converted (converted_availability_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='要員空き状況メール取込ジョブ';

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('bp-availability', '外部要員在庫', '/bp-availability', '/api/bp-availabilities', 70);

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'bp-availability'
UNION ALL SELECT '営業', id FROM m_menu WHERE menu_key = 'bp-availability'
UNION ALL SELECT 'HR', id FROM m_menu WHERE menu_key = 'bp-availability'
UNION ALL SELECT 'マネージャー', id FROM m_menu WHERE menu_key = 'bp-availability';

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('bp-availability-ingestion', '要員メール取込', '/bp-availability-ingestion', '/api/bp-availability-ingestions', 71);

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'bp-availability-ingestion'
UNION ALL SELECT '営業', id FROM m_menu WHERE menu_key = 'bp-availability-ingestion'
UNION ALL SELECT 'HR', id FROM m_menu WHERE menu_key = 'bp-availability-ingestion'
UNION ALL SELECT 'マネージャー', id FROM m_menu WHERE menu_key = 'bp-availability-ingestion';
