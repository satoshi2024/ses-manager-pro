-- t_resume_ingestion H2用スキーマ
DROP TABLE IF EXISTS t_resume_ingestion;
CREATE TABLE t_resume_ingestion (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100),
  original_file_name    VARCHAR(255),
  stored_file_name      VARCHAR(120),
  file_ext              VARCHAR(10),
  status                VARCHAR(20)  NOT NULL DEFAULT '取込待ち',
  extracted_text        LONGTEXT,
  parsed_json           LONGTEXT,
  ai_provider           VARCHAR(30),
  ai_model              VARCHAR(60),
  error_message         VARCHAR(500),
  converted_engineer_id BIGINT,
  candidate_id          BIGINT,
  version               INT NOT NULL DEFAULT 0,
  review_note           VARCHAR(500),
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  created_by            BIGINT
);
CREATE INDEX IF NOT EXISTS idx_resume_ingestion_tenant_status_file
    ON t_resume_ingestion (tenant_id, status, stored_file_name, deleted_flag);

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('resume-ingestion', 'スキルシート取込', '/resume-ingestion', '/api/resume-ingestions', 68);

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'resume-ingestion'
UNION ALL SELECT 'HR', id FROM m_menu WHERE menu_key = 'resume-ingestion';
