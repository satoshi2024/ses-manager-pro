CREATE TABLE sf_w2b_checkpoint_log (
  journal_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  subject_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  checkpoint_epoch BIGINT NOT NULL,
  event_revision BIGINT NOT NULL,
  event_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  checkpoint_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  key_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  signature_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (journal_id, tenant_id, subject_ref, checkpoint_epoch),
  UNIQUE KEY uq_sf_w2b_checkpoint_revision (journal_id, tenant_id, subject_ref, event_revision)
) ENGINE=InnoDB;

CREATE TABLE sf_w2b_latest_checkpoint (
  journal_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  subject_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  checkpoint_epoch BIGINT NOT NULL,
  event_revision BIGINT NOT NULL,
  event_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  checkpoint_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (journal_id, tenant_id, subject_ref)
) ENGINE=InnoDB;
