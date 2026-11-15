-- ============================================================
-- NF07 Journal インスタンスの中核表（NF07-A1J、production-bridge-phased-plan.md §3.3）
--
-- 適用先は application DB ではなく独立した Journal インスタンス（schema privacy_journal）。
-- location は db/privacy-journal、履歴表は flyway_schema_history_privacy_journal で、
-- application の db/migration とは版番号も履歴も独立している（journal-witness-grant-design.md §2.5）。
-- 適用は専用 Flyway job（mvn -Pprivacy-journal-migrate、ops/flyway/flyway-privacy-journal.conf）だけが行い、
-- application は migration を実行しない。
--
-- 表の形は合成 schema（src/test/resources/privacy-journal/schema-test.sql）の sf_journal_* を
-- 本番名 pj_* へ移したもの。列・型・制約・collation（identity 列は binary NO PAD の utf8mb4_0900_bin、
-- hash 列は ascii_bin）は合成 schema と同一で、差分は表名と制約名だけ。意味を変える場合は Plan Review を要する。
-- 同一性は PrivacyJournalMigrationIntegrityTest が合成 schema との比較で固定する。
--
-- 書込権限は表単位で分ける（journal-witness-grant-design.md §3.2）: appender は pj_head に SELECT/INSERT/UPDATE、
-- 他の4表に SELECT/INSERT だけ（UPDATE・DELETE を持たない＝追記専用）。reader は SELECT だけ。
-- 業務データ・個人情報は持たない（subject_ref は不透明な参照で、氏名等を入れない）。
-- ============================================================

CREATE TABLE pj_head (
  tenant_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  subject_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  revision BIGINT NOT NULL,
  event_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  held BOOLEAN NOT NULL,
  lease_generation BIGINT NOT NULL,
  lease_owner VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
  lease_until TIMESTAMP(6) NULL,
  PRIMARY KEY (tenant_id, subject_ref)
) ENGINE=InnoDB;

CREATE TABLE pj_event (
  tenant_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  subject_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  revision BIGINT NOT NULL,
  command_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  case_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
  action_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  worker_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
  expected_lease_generation BIGINT NOT NULL,
  lease_until TIMESTAMP(6) NULL,
  previous_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (tenant_id, subject_ref, revision),
  UNIQUE KEY uq_pj_event_command (tenant_id, subject_ref, command_id),
  CONSTRAINT fk_pj_event_head FOREIGN KEY (tenant_id, subject_ref)
    REFERENCES pj_head (tenant_id, subject_ref)
) ENGINE=InnoDB;

CREATE TABLE pj_command (
  command_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  subject_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL,
  event_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (tenant_id, subject_ref, command_id),
  CONSTRAINT fk_pj_command_event FOREIGN KEY (tenant_id, subject_ref, revision)
    REFERENCES pj_event (tenant_id, subject_ref, revision)
) ENGINE=InnoDB;

CREATE TABLE pj_action (
  action_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  subject_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  case_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  authorization_revision BIGINT NOT NULL,
  PRIMARY KEY (tenant_id, subject_ref, action_id),
  CONSTRAINT fk_pj_action_event FOREIGN KEY (tenant_id, subject_ref, authorization_revision)
    REFERENCES pj_event (tenant_id, subject_ref, revision)
) ENGINE=InnoDB;

CREATE TABLE pj_lease (
  tenant_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  subject_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  generation BIGINT NOT NULL,
  worker_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  lease_until TIMESTAMP(6) NOT NULL,
  event_revision BIGINT NOT NULL,
  PRIMARY KEY (tenant_id, subject_ref, generation),
  CONSTRAINT fk_pj_lease_event FOREIGN KEY (tenant_id, subject_ref, event_revision)
    REFERENCES pj_event (tenant_id, subject_ref, revision)
) ENGINE=InnoDB;
