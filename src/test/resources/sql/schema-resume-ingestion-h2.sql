-- t_resume_ingestion H2用スキーマ
DROP TABLE IF EXISTS t_resume_ingestion;
CREATE TABLE t_resume_ingestion (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  original_file_name    VARCHAR(255) NOT NULL,
  stored_file_name      VARCHAR(120) NOT NULL,
  file_ext              VARCHAR(10)  NOT NULL,
  status                VARCHAR(20)  NOT NULL DEFAULT '取込待ち',
  extracted_text        LONGTEXT,
  parsed_json           LONGTEXT,
  ai_provider           VARCHAR(30),
  ai_model              VARCHAR(60),
  error_message         VARCHAR(500),
  converted_engineer_id BIGINT,
  candidate_id          BIGINT,
  review_note           VARCHAR(500),
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  created_by            BIGINT
);

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('resume-ingestion', 'スキルシート取込', '/resume-ingestion', '/api/resume-ingestions', 68);

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'resume-ingestion'
UNION ALL SELECT 'HR', id FROM m_menu WHERE menu_key = 'resume-ingestion';
