CREATE TABLE sf_journal_head (
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

CREATE TABLE sf_journal_event (
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
  UNIQUE KEY uq_sf_event_command (tenant_id, subject_ref, command_id),
  CONSTRAINT fk_sf_event_head FOREIGN KEY (tenant_id, subject_ref)
    REFERENCES sf_journal_head (tenant_id, subject_ref)
) ENGINE=InnoDB;

CREATE TABLE sf_journal_command (
  command_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  subject_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL,
  event_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (tenant_id, subject_ref, command_id),
  CONSTRAINT fk_sf_command_event FOREIGN KEY (tenant_id, subject_ref, revision)
    REFERENCES sf_journal_event (tenant_id, subject_ref, revision)
) ENGINE=InnoDB;

CREATE TABLE sf_journal_action (
  action_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  subject_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  case_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  authorization_revision BIGINT NOT NULL,
  PRIMARY KEY (tenant_id, subject_ref, action_id),
  CONSTRAINT fk_sf_action_event FOREIGN KEY (tenant_id, subject_ref, authorization_revision)
    REFERENCES sf_journal_event (tenant_id, subject_ref, revision)
) ENGINE=InnoDB;

CREATE TABLE sf_journal_lease (
  tenant_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  subject_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  generation BIGINT NOT NULL,
  worker_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  lease_until TIMESTAMP(6) NOT NULL,
  event_revision BIGINT NOT NULL,
  PRIMARY KEY (tenant_id, subject_ref, generation),
  CONSTRAINT fk_sf_lease_event FOREIGN KEY (tenant_id, subject_ref, event_revision)
    REFERENCES sf_journal_event (tenant_id, subject_ref, revision)
) ENGINE=InnoDB;
