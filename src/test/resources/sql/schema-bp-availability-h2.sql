DROP TABLE IF EXISTS t_bp_availability;
CREATE TABLE t_bp_availability (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  initial_name       VARCHAR(50),
  bp_company         VARCHAR(120),
  skills_json        LONGTEXT,
  unit_price         BIGINT,
  available_from     DATE,
  experience_years   INT,
  status             VARCHAR(20) NOT NULL DEFAULT '提案可能',
  promoted_engineer_id BIGINT,
  remarks            VARCHAR(500),
  created_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag       TINYINT NOT NULL DEFAULT 0,
  created_by         BIGINT
);

DROP TABLE IF EXISTS t_bp_availability_ingestion;
CREATE TABLE t_bp_availability_ingestion (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  original_file_name    VARCHAR(255),
  stored_file_name      VARCHAR(120),
  file_ext              VARCHAR(10)  NOT NULL,
  status                VARCHAR(20)  NOT NULL DEFAULT '取込待ち',
  extracted_text        LONGTEXT,
  parsed_json           LONGTEXT,
  ai_provider           VARCHAR(30),
  ai_model              VARCHAR(60),
  error_message         VARCHAR(500),
  converted_availability_id BIGINT,
  review_note           VARCHAR(500),
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  created_by            BIGINT
);
