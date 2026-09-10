-- NF05: 後発の入力・取込テーブルにも法人境界を追加する。
-- 既存行は推測で埋めない。V158の監査とreadinessがNULL/不整合を検知し、公開境界を閉じる。
SET @nf05_sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_lead' AND column_name = 'legal_entity_id') = 0,
  'ALTER TABLE t_lead ADD COLUMN legal_entity_id BIGINT NULL', 'SELECT 1'));
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_opportunity' AND column_name = 'legal_entity_id') = 0,
  'ALTER TABLE t_opportunity ADD COLUMN legal_entity_id BIGINT NULL', 'SELECT 1'));
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_bp_availability' AND column_name = 'legal_entity_id') = 0,
  'ALTER TABLE t_bp_availability ADD COLUMN legal_entity_id BIGINT NULL', 'SELECT 1'));
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_bp_availability_ingestion' AND column_name = 'legal_entity_id') = 0,
  'ALTER TABLE t_bp_availability_ingestion ADD COLUMN legal_entity_id BIGINT NULL', 'SELECT 1'));
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_resume_ingestion' AND column_name = 'legal_entity_id') = 0,
  'ALTER TABLE t_resume_ingestion ADD COLUMN legal_entity_id BIGINT NULL', 'SELECT 1'));
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_project_ingestion' AND column_name = 'legal_entity_id') = 0,
  'ALTER TABLE t_project_ingestion ADD COLUMN legal_entity_id BIGINT NULL', 'SELECT 1'));
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;

SET @nf05_sql = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_lead' AND index_name = 'idx_lead_legal_entity') = 0,
  'CREATE INDEX idx_lead_legal_entity ON t_lead(legal_entity_id, deleted_flag, id)', 'SELECT 1'));
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_opportunity' AND index_name = 'idx_opportunity_legal_entity') = 0,
  'CREATE INDEX idx_opportunity_legal_entity ON t_opportunity(legal_entity_id, deleted_flag, id)', 'SELECT 1'));
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_bp_availability' AND index_name = 'idx_bp_availability_legal_entity') = 0,
  'CREATE INDEX idx_bp_availability_legal_entity ON t_bp_availability(legal_entity_id, deleted_flag, id)', 'SELECT 1'));
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_bp_availability_ingestion' AND index_name = 'idx_bp_availability_ingestion_legal_entity') = 0,
  'CREATE INDEX idx_bp_availability_ingestion_legal_entity ON t_bp_availability_ingestion(legal_entity_id, deleted_flag, id)', 'SELECT 1'));
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_resume_ingestion' AND index_name = 'idx_resume_ingestion_legal_entity') = 0,
  'CREATE INDEX idx_resume_ingestion_legal_entity ON t_resume_ingestion(legal_entity_id, deleted_flag, id)', 'SELECT 1'));
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_project_ingestion' AND index_name = 'idx_project_ingestion_legal_entity') = 0,
  'CREATE INDEX idx_project_ingestion_legal_entity ON t_project_ingestion(legal_entity_id, deleted_flag, id)', 'SELECT 1'));
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
