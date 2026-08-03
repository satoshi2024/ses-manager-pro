-- ============================================================
-- V78: 承認ワークフロー Round 3 修正
-- S07 approval-workflow-internal-control。適用済みV75/V76/V77は変更しない。
-- ============================================================

-- ============================================================
-- (A') 既存申請のslot意味論を先に検査する。
-- MySQLのDDLは暗黙commitするため、ALTER/CREATE/backfillより前にCALLする。
-- 旧snapshotから現在routeを参照してslotを再構成せず、危険な申請はfail-closedにする。
-- ============================================================
DELIMITER $$

DROP PROCEDURE IF EXISTS __ses_check_v78_legacy_approval $$

CREATE PROCEDURE __ses_check_v78_legacy_approval()
BEGIN
    DECLARE bad_id BIGINT DEFAULT NULL;
    DECLARE bad_no VARCHAR(30) DEFAULT NULL;
    DECLARE bad_status VARCHAR(20) DEFAULT NULL;
    DECLARE bad_step INT DEFAULT NULL;
    DECLARE message_text VARCHAR(128);

    -- JSON不正、NULL/空、steps欠落・空は終端状態を含めて停止する。
    SELECT r.id, r.request_no, r.status, r.current_step
      INTO bad_id, bad_no, bad_status, bad_step
    FROM t_approval_request r
    WHERE r.deleted_flag = 0
      AND (
          r.route_snapshot_json IS NULL
          OR TRIM(CAST(r.route_snapshot_json AS CHAR)) = ''
          OR JSON_VALID(r.route_snapshot_json) = 0
          OR COALESCE(JSON_TYPE(JSON_EXTRACT(r.route_snapshot_json, '$.steps')), '') <> 'ARRAY'
          OR COALESCE(JSON_LENGTH(JSON_EXTRACT(r.route_snapshot_json, '$.steps')), 0) = 0
      )
    ORDER BY r.id
    LIMIT 1;

    -- 旧形式で、現在step以降に候補2名以上のstepが残る場合だけ停止する。
    -- JSON_TABLEでstepを展開し、旧snapshotのslots境界をroute定義から再構成しない。
    IF bad_id IS NULL THEN
        SELECT r.id, r.request_no, r.status, r.current_step
          INTO bad_id, bad_no, bad_status, bad_step
        FROM t_approval_request r
        WHERE r.deleted_flag = 0
          AND r.status IN ('draft', 'requested', 'in_review', 'returned', 'conflict')
          AND EXISTS (
              SELECT 1
              FROM JSON_TABLE(
                  r.route_snapshot_json,
                  '$.steps[*]' COLUMNS (
                      step_no INT PATH '$.stepNo',
                      step_index FOR ORDINALITY
                  )
              ) AS jt
              WHERE jt.step_no >= COALESCE(r.current_step, 0)
                AND COALESCE(JSON_LENGTH(JSON_EXTRACT(
                        r.route_snapshot_json,
                        CONCAT('$.steps[', (jt.step_index - 1), '].approverUserIds')
                    )), 0) >= 2
                AND (
                    COALESCE(JSON_TYPE(JSON_EXTRACT(
                        r.route_snapshot_json,
                        CONCAT('$.steps[', (jt.step_index - 1), '].slots')
                    )), '') <> 'ARRAY'
                    OR COALESCE(JSON_LENGTH(JSON_EXTRACT(
                        r.route_snapshot_json,
                        CONCAT('$.steps[', (jt.step_index - 1), '].slots')
                    )), 0) = 0
                )
          )
        ORDER BY r.id
        LIMIT 1;
    END IF;

    IF bad_id IS NOT NULL THEN
        SET message_text = CONCAT(
            'V78 id=', bad_id,
            ' no=', LEFT(COALESCE(bad_no, '-'), 12),
            ' status=', LEFT(COALESCE(bad_status, '-'), 12),
            ' step=', COALESCE(bad_step, 0),
            '; SQL: SELECT ... WHERE id=', bad_id,
            '; 取下げ/完了後flyway repair→再実行'
        );
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = message_text;
    END IF;
END $$

DELIMITER ;

CALL __ses_check_v78_legacy_approval();
DROP PROCEDURE IF EXISTS __ses_check_v78_legacy_approval;

-- (A) 再申請ラウンドをrequest/actionへ追加する。
ALTER TABLE t_approval_request
  ADD COLUMN round_no INT NOT NULL DEFAULT 1 AFTER current_step;

ALTER TABLE t_approval_action
  ADD COLUMN round_no INT NOT NULL DEFAULT 1 AFTER request_id;

-- (B) actionをround単位で一意化する。slot_indexは監査・表示用で、UNIQUEには含めない。
ALTER TABLE t_approval_action
  ADD COLUMN slot_index INT NOT NULL DEFAULT 0 AFTER step_no;

ALTER TABLE t_approval_action
  DROP KEY uk_approval_action_slot;

ALTER TABLE t_approval_action
  ADD UNIQUE KEY uk_approval_action_slot
    (request_id, round_no, step_no, approver_slot_user_id);

-- (C) 承認一覧のSQL境界。participantは申請時点のsnapshot候補をround単位で保持する。
CREATE TABLE t_approval_participant (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_id       BIGINT NOT NULL,
  user_id          BIGINT NOT NULL,
  participant_role VARCHAR(20) NOT NULL COMMENT 'applicant/approver',
  round_no         INT NOT NULL DEFAULT 1 COMMENT 'resubmitで更新されるラウンド番号',
  UNIQUE KEY uk_participant (request_id, round_no, user_id, participant_role),
  INDEX idx_participant_user (user_id, participant_role),
  CONSTRAINT fk_participant_request FOREIGN KEY (request_id)
    REFERENCES t_approval_request(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='承認参加者';

-- 既存申請の可視性を復元する。INSERT IGNOREで再実行・既存行を冪等に扱う。
INSERT IGNORE INTO t_approval_participant
    (request_id, user_id, participant_role, round_no)
SELECT r.id, r.applicant_id, 'applicant', COALESCE(r.round_no, 1)
FROM t_approval_request r
WHERE r.deleted_flag = 0
  AND r.applicant_id IS NOT NULL;

INSERT IGNORE INTO t_approval_participant
    (request_id, user_id, participant_role, round_no)
SELECT r.id, jt.user_id, 'approver', COALESCE(r.round_no, 1)
FROM t_approval_request r
JOIN JSON_TABLE(
    r.route_snapshot_json,
    '$.steps[*]' COLUMNS (
        NESTED PATH '$.approverUserIds[*]' COLUMNS (
            user_id BIGINT PATH '$'
        )
    )
) AS jt
WHERE r.deleted_flag = 0
  AND jt.user_id IS NOT NULL;

-- (C-2) 代理対象種別の正規化表。子行0件は全種別対象を意味する。
CREATE TABLE t_approval_delegation_type (
  delegation_id BIGINT NOT NULL,
  request_type  VARCHAR(50) NOT NULL,
  PRIMARY KEY (delegation_id, request_type),
  CONSTRAINT fk_delegation_type FOREIGN KEY (delegation_id)
    REFERENCES t_approval_delegation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='代理対象種別';

-- V75のJSON列は移行互換のため保持し、NULLまたは空配列は子行を作らない。
INSERT INTO t_approval_delegation_type (delegation_id, request_type)
SELECT DISTINCT d.id, jt.request_type
FROM t_approval_delegation d
JOIN JSON_TABLE(
  d.request_types_json,
  '$[*]' COLUMNS (request_type VARCHAR(50) PATH '$')
) AS jt
WHERE d.request_types_json IS NOT NULL
  AND jt.request_type IS NOT NULL
  AND jt.request_type <> '';

-- (D) 承認対象4業務の楽観ロックversion。
ALTER TABLE t_quotation
  ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '楽観ロックバージョン';

ALTER TABLE t_contract
  ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '楽観ロックバージョン';

ALTER TABLE t_invoice
  ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '楽観ロックバージョン';

ALTER TABLE t_bp_payment
  ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '楽観ロックバージョン';

-- 承認画面の口座fieldは専用actionで判定する。BP会社マスタのbp-company.*は変更しない。
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, 'bp-company.bank-account.view', 1
FROM m_permission_group g
WHERE g.tenant_id = 'default'
  AND g.enabled = 1
  AND g.group_key IN ('role-sales', 'role-manager');
