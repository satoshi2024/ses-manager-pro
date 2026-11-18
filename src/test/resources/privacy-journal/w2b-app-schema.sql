CREATE TABLE sf_w2b_projection (
  tenant_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  subject_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  last_applied_revision BIGINT NOT NULL,
  event_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  held BOOLEAN NOT NULL,
  last_action_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
  last_action_state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (tenant_id, subject_ref)
) ENGINE=InnoDB;

-- D1試験専用の永続隔離fence。application migrationでは作成しない。
CREATE TABLE sf_w2b_partition_fence (
  tenant_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  subject_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  generation BIGINT NOT NULL,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  PRIMARY KEY (tenant_id, subject_ref),
  CONSTRAINT chk_sf_w2b_fence_generation CHECK (generation >= 0),
  CONSTRAINT chk_sf_w2b_fence_state CHECK (state IN ('ACTIVE', 'QUARANTINED')),
  CONSTRAINT chk_sf_w2b_fence_owner CHECK ((state = 'ACTIVE' AND owner_token IS NULL)
      OR (state = 'QUARANTINED' AND owner_token IS NOT NULL))
) ENGINE=InnoDB;
