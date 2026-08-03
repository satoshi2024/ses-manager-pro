-- ============================================================
-- V78: 承認ワークフロー Round 2 修正
-- S07 approval-workflow-internal-control。適用済みV75/V76/V77は変更しない。
-- ============================================================

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
