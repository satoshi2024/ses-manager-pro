DROP TABLE IF EXISTS t_pwa_client_mutation CASCADE;
CREATE TABLE t_pwa_client_mutation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  client_request_id VARCHAR(80) NOT NULL,
  user_id BIGINT NOT NULL,
  user_scope_hash CHAR(64) NOT NULL,
  operation VARCHAR(160),
  screen VARCHAR(40) NOT NULL,
  work_month CHAR(7),
  payload_hash CHAR(64) NOT NULL,
  base_version INT,
  status VARCHAR(20) NOT NULL,
  response_json LONGTEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMP,
  CONSTRAINT uk_pwa_client_mutation_user_request UNIQUE (user_id, client_request_id)
);
