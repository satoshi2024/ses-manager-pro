-- t_project_ingestion H2用スキーマ
DROP TABLE IF EXISTS t_project_ingestion;
CREATE TABLE t_project_ingestion (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_type         VARCHAR(10) NOT NULL,
  original_file_name  VARCHAR(255),
  stored_file_name    VARCHAR(120),
  raw_text            LONGTEXT,
  status              VARCHAR(20) NOT NULL DEFAULT '取込待ち',
  parsed_json         LONGTEXT,
  ai_provider         VARCHAR(30),
  ai_model            VARCHAR(60),
  error_message       VARCHAR(500),
  converted_project_id BIGINT,
  review_note         VARCHAR(500),
  created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag        TINYINT NOT NULL DEFAULT 0,
  created_by          BIGINT
);

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('project-ingestion', '案件メール取込', '/project-ingestion', '/api/project-ingestions', 69);

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key='project-ingestion'
UNION ALL SELECT '営業', id FROM m_menu WHERE menu_key='project-ingestion'
UNION ALL SELECT 'マネージャー', id FROM m_menu WHERE menu_key='project-ingestion';
