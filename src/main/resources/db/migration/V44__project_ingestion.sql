CREATE TABLE t_project_ingestion (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_type         VARCHAR(10) NOT NULL COMMENT 'PASTE / EML',
  original_file_name  VARCHAR(255) NULL COMMENT 'emlの場合の元名',
  stored_file_name    VARCHAR(120) NULL COMMENT 'emlの保存名',
  raw_text            LONGTEXT NULL COMMENT '貼付/抽出した本文',
  status              VARCHAR(20) NOT NULL DEFAULT '取込待ち',
  parsed_json         LONGTEXT NULL,
  ai_provider         VARCHAR(30) NULL,
  ai_model            VARCHAR(60) NULL,
  error_message       VARCHAR(500) NULL,
  converted_project_id BIGINT NULL,
  review_note         VARCHAR(500) NULL,
  created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag        TINYINT NOT NULL DEFAULT 0,
  created_by          BIGINT NULL,
  INDEX idx_pi_status (status),
  INDEX idx_pi_converted (converted_project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='案件メール取込ジョブ';

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('project-ingestion', '案件メール取込', '/project-ingestion', '/api/project-ingestions', 69);

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key='project-ingestion'
UNION ALL SELECT '営業', id FROM m_menu WHERE menu_key='project-ingestion'
UNION ALL SELECT 'マネージャー', id FROM m_menu WHERE menu_key='project-ingestion';
