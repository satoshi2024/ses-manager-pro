-- 削除機能検証用の最小 t_engineer スキーマ（H2 / MySQLモード互換）
-- 本番の 001_create_tables.sql は ENUM / ENGINE=InnoDB / インライン INDEX など
-- H2 が解釈できない構文を含むため、検証に必要な列だけを持つ簡易版を用意する。
DROP TABLE IF EXISTS t_engineer;
CREATE TABLE t_engineer (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  full_name           VARCHAR(100),
  full_name_kana      VARCHAR(100),
  initial_name        VARCHAR(10),
  gender              VARCHAR(10),
  birth_date          DATE,
  nationality         VARCHAR(50),
  nearest_station     VARCHAR(100),
  employment_type     VARCHAR(20),
  status              VARCHAR(20),
  expected_unit_price DECIMAL(10,0),
  available_date      DATE,
  experience_years    INT,
  japanese_level      VARCHAR(20),
  resume_summary      TEXT,
  photo_url           VARCHAR(500),
  remarks             TEXT,
  created_by          BIGINT,
  created_at          DATETIME,
  updated_at          DATETIME,
  deleted_flag        TINYINT DEFAULT 0
);
