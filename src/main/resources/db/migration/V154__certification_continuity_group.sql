-- NF-03: 資格renew履歴のcontinuity groupを複合scopeで固定する。
-- 同一legacy groupが複数のtenant/engineer/certification tupleを持つ場合は、
-- 子行の付替えをせず明示的にFlywayを失敗させ、履歴の静かな欠落を防ぐ。
DROP PROCEDURE IF EXISTS __ses_nf03_check_continuity_collision;
DELIMITER $$
CREATE PROCEDURE __ses_nf03_check_continuity_collision()
BEGIN
  IF EXISTS (
      SELECT continuity_group_id
      FROM t_engineer_certification
      GROUP BY continuity_group_id
      HAVING COUNT(DISTINCT tenant_id, engineer_id, certification_id) > 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'NF-03 continuity_group_id collision: migration aborted';
  END IF;
END$$
DELIMITER ;
CALL __ses_nf03_check_continuity_collision();
DROP PROCEDURE IF EXISTS __ses_nf03_check_continuity_collision;

CREATE TABLE IF NOT EXISTS t_certification_continuity_group (
    tenant_id           VARCHAR(100) NOT NULL,
    engineer_id         BIGINT NOT NULL,
    certification_id    BIGINT NOT NULL,
    continuity_group_id BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, engineer_id, certification_id, continuity_group_id),
    UNIQUE KEY uk_cert_continuity_group_id (continuity_group_id),
    CONSTRAINT fk_cert_continuity_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id),
    CONSTRAINT fk_cert_continuity_master FOREIGN KEY (certification_id) REFERENCES m_certification(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='資格renew continuity group';

-- collision検知後なので、DISTINCT backfillは歴史を捨てず全tupleを登録する。
INSERT INTO t_certification_continuity_group (tenant_id, engineer_id, certification_id, continuity_group_id)
SELECT DISTINCT tenant_id, engineer_id, certification_id, continuity_group_id
FROM t_engineer_certification;

-- orphanがあればこの時点でFK作成を止める（IGNORE/NULL補正は行わない）。
ALTER TABLE t_engineer_certification
    ADD CONSTRAINT fk_eng_cert_continuity_group
    FOREIGN KEY (tenant_id, engineer_id, certification_id, continuity_group_id)
    REFERENCES t_certification_continuity_group (tenant_id, engineer_id, certification_id, continuity_group_id);

ALTER TABLE t_engineer_certification
    ADD CONSTRAINT chk_eng_cert_current_holder
    CHECK ((current_flag = 1 AND current_holder_key = continuity_group_id AND current_holder_key IS NOT NULL)
        OR (current_flag = 0 AND current_holder_key IS NULL));

SELECT 1;
