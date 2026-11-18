-- ============================================================
-- V190: NF07 F1（W3-14）catalog / policy / hold projection / DSAR request の本番表。
--
-- production-bridge-phased-plan.md §3.1.5 の草案を実装する。法的な値（保持期間・起算trigger・処分方式・
-- 本人確認基準・DSAR期限・hold種類と権限者）は一切seedしない（I-1）。行は署名後のbatchだけが書く。
-- 処分 job（t_disposition_job / t_disposition_item）は署名後の B1 で別 migration とする。
--
-- 草案からの変更（実装時の確定）:
--   - 子表の複合FK（tenant_id を含む）は参照先と同じ collation でなければMySQLが拒否する（ERROR 3780）。
--     DSAR 4表と hold・policy 表は全て utf8mb4_bin に揃えた（識別子は binary 比較。合成 Journal と同じ方針）。
--   - m_pii_data_element も utf8mb4_bin とし、subject_attribution は4値の CHECK を持つ。
--     既定値 'UNKNOWN' 以外は明示した分類だけを受け付け、大小文字違いの値で分類済みと誤認させない
--     （C-2 の前提: A1H は UNKNOWN を fail-closed として扱う）。
--   - 承認 package の束縛は R2-P3-5 の案を採り、(tenant_id, approved_package_id, id) → package の
--     (tenant_id, id, request_id) への複合FKとした。他の request の package は承認対象にできない。
--   - DSAR package の stored_name だけの索引を置く（A2b が汎用 file 経路から tenant を問わず拒否するため。R2-P3-6）。
-- ============================================================

-- ------------------------------------------------------------
-- 1. PII data element catalog（構造のみ）。行は F2 の seed が入れる。
-- ------------------------------------------------------------
CREATE TABLE m_pii_data_element (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id           VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT '論理tenant識別子',
  data_element_id     VARCHAR(128) NOT NULL COMMENT '固定ID（例: AI_PROMPT_LOG）。pii-inventory.md の行と対応',
  storage_kind        VARCHAR(20)  NOT NULL COMMENT 'DB_COLUMN/FILE_OBJECT/AI_PAYLOAD/LOG_OR_BACKUP',
  storage_ref         VARCHAR(255) NOT NULL COMMENT 'table.column / provider.object-kind / payload.field',
  data_class          VARCHAR(50)  NOT NULL DEFAULT 'UNKNOWN' COMMENT 'データ分類。確定まで UNKNOWN',
  owner_state         VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN' COMMENT 'owner の確定状態。確定まで UNKNOWN',
  owner_ref           VARCHAR(100) NULL COMMENT 'owner の参照（氏名は入れない）',
  provider_id         VARCHAR(100) NULL COMMENT 'F2 の provider。NULL=未登録（検索・処分の対象外として報告する）',
  subject_attribution VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN'
                      COMMENT 'ATTRIBUTABLE/NOT_ATTRIBUTABLE/NO_PERSONAL_DATA/UNKNOWN。UNKNOWN は fail-closed（C-2）',
  third_party_flag    TINYINT      NOT NULL DEFAULT 1 COMMENT '第三者情報を含み得る（既定は含む扱い）',
  inventory_status    VARCHAR(30)  NOT NULL DEFAULT 'UNKNOWN'
                      COMMENT 'CONFIRMED_TECHNICAL/PROVISIONAL/UNKNOWN。法的承認ではない',
  inventory_ref       VARCHAR(255) NULL COMMENT 'pii-inventory.md 等の参照',
  created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag        TINYINT      NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  CONSTRAINT uk_pii_data_element UNIQUE (tenant_id, data_element_id),
  CONSTRAINT chk_pii_data_element_attribution
    CHECK (subject_attribution IN ('ATTRIBUTABLE', 'NOT_ATTRIBUTABLE', 'NO_PERSONAL_DATA', 'UNKNOWN')),
  CONSTRAINT chk_pii_data_element_storage_kind
    CHECK (storage_kind IN ('DB_COLUMN', 'FILE_OBJECT', 'AI_PAYLOAD', 'LOG_OR_BACKUP')),
  CONSTRAINT chk_pii_data_element_inventory
    CHECK (inventory_status IN ('CONFIRMED_TECHNICAL', 'PROVISIONAL', 'UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='NF07 PII data element catalog（構造のみ）';

-- ------------------------------------------------------------
-- 2. retention policy の正本候補。値は署名済み envelope の canonical bytes にだけ入る。
--    current pointer は生成列 + UNIQUE で (tenant, module, 法人, data element, action, provider) ごとに高々1件にする。
--    current_key は生成列のため application から書かない（RetentionPolicy.currentKey は insert/update NEVER）。
-- ------------------------------------------------------------
CREATE TABLE m_retention_policy (
  id                      BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id               VARCHAR(100) NOT NULL COMMENT '論理tenant識別子',
  policy_id               VARCHAR(128) NOT NULL COMMENT 'policy識別子',
  policy_revision         VARCHAR(64)  NOT NULL COMMENT 'policy revision（数値比較しない）',
  module_code             VARCHAR(50)  NOT NULL COMMENT '利用module（例: NF08_AI_SUMMARY）',
  legal_entity_id         BIGINT       NULL COMMENT 'NULL=tenant-wide。P-LE 未決のため法人単位の主張はしない',
  data_element_id         VARCHAR(128) NOT NULL COMMENT 'm_pii_data_element.data_element_id',
  target_action           VARCHAR(50)  NOT NULL COMMENT '対象操作',
  target_provider         VARCHAR(100) NOT NULL COMMENT '対象provider',
  status                  VARCHAR(20)  NOT NULL DEFAULT 'CANDIDATE' COMMENT 'CANDIDATE/CURRENT/SUPERSEDED/REVOKED',
  current_key             VARCHAR(512) GENERATED ALWAYS AS (
                            CASE WHEN status = 'CURRENT'
                                 THEN CONCAT_WS('|', tenant_id, module_code, IFNULL(legal_entity_id, 0),
                                                data_element_id, target_action, target_provider)
                                 ELSE NULL END) STORED COMMENT 'current pointer（生成列）',
  envelope_schema_version INT          NOT NULL COMMENT 'envelope schema version',
  envelope_canonical      LONGBLOB     NOT NULL COMMENT '署名対象の canonical bytes',
  envelope_signature      VARBINARY(1024) NOT NULL COMMENT '現行 kernel は Ed25519（64 byte）。D-03 で方式が決まったら長さを見直す',
  envelope_digest         CHAR(64)     NOT NULL COMMENT 'canonical bytes の SHA-256（hex）',
  authority_id            VARCHAR(100) NOT NULL COMMENT '署名authority',
  key_id                  VARCHAR(100) NOT NULL COMMENT '署名鍵',
  registered_by           BIGINT       NOT NULL COMMENT '登録者 sys_user.id',
  registered_at           DATETIME(6)  NOT NULL COMMENT '登録日時',
  activated_by            BIGINT       NULL COMMENT '有効化者 sys_user.id（登録者と別人）',
  activated_at            DATETIME(6)  NULL COMMENT '有効化日時',
  row_version             BIGINT       NOT NULL DEFAULT 0 COMMENT '楽観lock',
  CONSTRAINT uk_retention_policy_revision UNIQUE (tenant_id, policy_id, policy_revision),
  CONSTRAINT uk_retention_policy_current UNIQUE (current_key),
  CONSTRAINT chk_retention_policy_status CHECK (status IN ('CANDIDATE', 'CURRENT', 'SUPERSEDED', 'REVOKED')),
  CONSTRAINT chk_retention_policy_activation
    CHECK (status <> 'CURRENT' OR (activated_by IS NOT NULL AND activated_at IS NOT NULL AND activated_by <> registered_by)),
  CONSTRAINT chk_retention_policy_digest CHECK (CHAR_LENGTH(envelope_digest) = 64),
  INDEX idx_retention_policy_lookup (tenant_id, module_code, data_element_id, target_action, target_provider, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='NF07 retention policy（署名envelope + current pointer）';

-- ------------------------------------------------------------
-- 3. hold は独立 Journal が正本で、本表は projection。理由本文は保存せず、コード・hash・ticket 参照だけを持つ。
-- ------------------------------------------------------------
CREATE TABLE t_legal_hold (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id           VARCHAR(100) NOT NULL COMMENT '論理tenant識別子',
  hold_id             VARCHAR(64)  NOT NULL COMMENT 'Journal 上の hold 識別子',
  target_type         VARCHAR(20)  NOT NULL COMMENT 'SUBJECT/DATA_ELEMENT',
  subject_ref         VARCHAR(128) NULL COMMENT '種別付き opaque 参照（例: ENGINEER:123）',
  data_element_id     VARCHAR(128) NULL COMMENT 'm_pii_data_element.data_element_id',
  hold_kind           VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN' COMMENT 'D-05 で確定するまで UNKNOWN',
  status              VARCHAR(20)  NOT NULL COMMENT 'ACTIVE/RELEASED',
  reason_code         VARCHAR(50)  NOT NULL COMMENT '理由コード（本文は保存しない）',
  reason_hash         CHAR(64)     NOT NULL COMMENT '理由本文の SHA-256（hex）',
  ticket_ref          VARCHAR(100) NOT NULL COMMENT '外部 ticket 参照',
  requested_by        BIGINT       NOT NULL COMMENT '開始者 sys_user.id',
  requested_at        DATETIME(6)  NOT NULL COMMENT '開始日時',
  journal_revision    BIGINT       NOT NULL COMMENT 'Journal の revision',
  journal_event_hash  CHAR(64)     NOT NULL COMMENT 'Journal event の hash',
  released_at         DATETIME(6)  NULL COMMENT '解除日時（第1期は解除しない）',
  CONSTRAINT uk_legal_hold UNIQUE (tenant_id, hold_id),
  CONSTRAINT chk_legal_hold_target
    CHECK ((target_type = 'SUBJECT' AND subject_ref IS NOT NULL AND data_element_id IS NULL)
        OR (target_type = 'DATA_ELEMENT' AND data_element_id IS NOT NULL AND subject_ref IS NULL)),
  CONSTRAINT chk_legal_hold_status CHECK (status IN ('ACTIVE', 'RELEASED')),
  CONSTRAINT chk_legal_hold_release CHECK ((status = 'RELEASED') = (released_at IS NOT NULL)),
  INDEX idx_legal_hold_subject (tenant_id, subject_ref, status),
  INDEX idx_legal_hold_element (tenant_id, data_element_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='NF07 hold projection（正本は独立Journal）';

-- ------------------------------------------------------------
-- 4. hold projection の追随位置。行が1件でもあれば「hold 機構が使われた」とみなし、
--    破壊的経路は Journal との追随確認を必須にする（§3.4.4）。
--    最初の Journal append の前に行を作り、append 中の command を pending_command_id に CAS で記録する。
-- ------------------------------------------------------------
CREATE TABLE t_privacy_projection_state (
  tenant_id            VARCHAR(100) NOT NULL COMMENT '論理tenant識別子',
  journal_id           VARCHAR(64)  NOT NULL COMMENT 'Journal 識別子',
  engaged_at           DATETIME(6)  NOT NULL COMMENT 'hold 機構を初めて使った日時',
  last_applied_seq     BIGINT       NOT NULL DEFAULT 0 COMMENT 'projection へ反映済みの Journal seq',
  last_applied_hash    CHAR(64)     NULL COMMENT '反映済み event の hash',
  pending_command_id   VARCHAR(64)  NULL COMMENT 'Journal append 前に CAS で記録し、projection 完了か reconciler の判定で NULL に戻す',
  pending_since        DATETIME(6)  NULL COMMENT 'pending の記録日時',
  verified_at          DATETIME(6)  NULL COMMENT '最後に Journal head と照合した日時',
  row_version          BIGINT       NOT NULL DEFAULT 0 COMMENT '楽観lock',
  PRIMARY KEY (tenant_id, journal_id),
  CONSTRAINT chk_projection_pending CHECK ((pending_command_id IS NULL) = (pending_since IS NULL)),
  CONSTRAINT chk_projection_seq CHECK (last_applied_seq >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='hold projection の追随位置（A1H の gate が参照）';

-- ------------------------------------------------------------
-- 5. DSAR。依頼者の連絡先・本人確認書類は保存せず、外部 ticket / 証跡の参照IDだけを持つ（第1期）。
--    SoD: 承認者は準備者と別人で、準備者が未記録のまま承認できない（DB 層でも保証する。P2-4）。
--    承認の束縛: 承認4項目（承認者・日時・package・内容hash）は全て揃うか全て空。
-- ------------------------------------------------------------
CREATE TABLE t_privacy_request (
  id                       BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id                VARCHAR(100) NOT NULL COMMENT '論理tenant識別子',
  request_no               VARCHAR(40)  NOT NULL COMMENT '受付番号',
  request_type             VARCHAR(20)  NOT NULL COMMENT 'ACCESS/CORRECTION/DELETION/RESTRICTION/OBJECTION',
  status                   VARCHAR(30)  NOT NULL COMMENT 'PrivacyCaseState の部分集合',
  channel                  VARCHAR(20)  NOT NULL COMMENT '受付経路',
  received_at              DATETIME(6)  NOT NULL COMMENT '受付日時',
  due_date                 DATE         NULL COMMENT 'D-06 の期限規則で計算。規則未設定なら NULL',
  intake_ticket_ref        VARCHAR(128) NOT NULL COMMENT '受付 ticket 参照',
  identity_state           VARCHAR(20)  NOT NULL DEFAULT 'UNVERIFIED' COMMENT 'UNVERIFIED/AMBIGUOUS/VERIFIED',
  identity_evidence_ref    VARCHAR(128) NULL COMMENT '本人確認証跡の参照（書類本体は保存しない）',
  identity_rule_ref        VARCHAR(128) NULL COMMENT '適用した D-06 本人確認基準の決定ID',
  identity_verified_by     BIGINT       NULL COMMENT '本人確認者 sys_user.id',
  identity_verified_at     DATETIME(6)  NULL COMMENT '本人確認日時',
  case_owner_id            BIGINT       NULL COMMENT '担当者 sys_user.id',
  scope_snapshot_hash      CHAR(64)     NULL COMMENT '範囲 snapshot の hash',
  prepared_by              BIGINT       NULL COMMENT '準備者 sys_user.id',
  approved_by              BIGINT       NULL COMMENT '承認者 sys_user.id（準備者と別人）',
  approved_at              DATETIME(6)  NULL COMMENT '承認日時',
  approved_package_id      BIGINT       NULL COMMENT '承認した package。ダウンロード・交付記録時に照合する',
  approved_content_sha256  CHAR(64)     NULL COMMENT '承認した package の内容 hash',
  delivered_by             BIGINT       NULL COMMENT '交付記録者 sys_user.id',
  delivered_at             DATETIME(6)  NULL COMMENT '交付日時',
  delivery_method          VARCHAR(20)  NULL COMMENT '交付方法',
  delivery_evidence_ref    VARCHAR(128) NULL COMMENT '交付証跡の参照',
  reopen_of_request_id     BIGINT       NULL COMMENT '再開元の request',
  row_version              BIGINT       NOT NULL DEFAULT 0 COMMENT '楽観lock',
  created_by               BIGINT       NOT NULL COMMENT '作成者 sys_user.id',
  created_at               DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at               DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag             TINYINT      NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  CONSTRAINT uk_privacy_request_no UNIQUE (tenant_id, request_no),
  CONSTRAINT uk_privacy_request_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_privacy_request_sod
    CHECK (approved_by IS NULL OR (prepared_by IS NOT NULL AND approved_by <> prepared_by)),
  CONSTRAINT chk_privacy_request_approval_binding
    CHECK ((approved_by IS NULL AND approved_at IS NULL AND approved_package_id IS NULL AND approved_content_sha256 IS NULL)
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL AND approved_package_id IS NOT NULL
            AND approved_content_sha256 IS NOT NULL)),
  CONSTRAINT chk_privacy_request_type
    CHECK (request_type IN ('ACCESS', 'CORRECTION', 'DELETION', 'RESTRICTION', 'OBJECTION')),
  CONSTRAINT chk_privacy_request_identity CHECK (identity_state IN ('UNVERIFIED', 'AMBIGUOUS', 'VERIFIED')),
  CONSTRAINT fk_privacy_request_reopen FOREIGN KEY (tenant_id, reopen_of_request_id)
    REFERENCES t_privacy_request (tenant_id, id),
  INDEX idx_privacy_request_status (tenant_id, status, due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='NF07 DSAR case';

-- 子表の FK は tenant を含む複合 FK とし、DB 層でも tenant をまたぐ関連を作れないようにする。
CREATE TABLE t_privacy_request_subject_link (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id         VARCHAR(100) NOT NULL COMMENT '論理tenant識別子',
  request_id        BIGINT       NOT NULL COMMENT 't_privacy_request.id',
  subject_ref       VARCHAR(128) NOT NULL COMMENT '種別付き opaque 参照（例: ENGINEER:123）',
  link_state        VARCHAR(20)  NOT NULL COMMENT 'CANDIDATE/SELECTED/REJECTED',
  resolved_by       BIGINT       NULL COMMENT '判定者 sys_user.id',
  resolved_at       DATETIME(6)  NULL COMMENT '判定日時',
  resolution_code   VARCHAR(50)  NULL COMMENT '判定コード',
  CONSTRAINT uk_privacy_subject_link UNIQUE (tenant_id, request_id, subject_ref),
  CONSTRAINT chk_privacy_subject_link_state CHECK (link_state IN ('CANDIDATE', 'SELECTED', 'REJECTED')),
  CONSTRAINT fk_privacy_subject_link_request FOREIGN KEY (tenant_id, request_id)
    REFERENCES t_privacy_request (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='DSAR と本人の明示 link（自動結合しない）';

CREATE TABLE t_privacy_action (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id         VARCHAR(100) NOT NULL COMMENT '論理tenant識別子',
  request_id        BIGINT       NOT NULL COMMENT 't_privacy_request.id',
  action_type       VARCHAR(20)  NOT NULL COMMENT 'SEARCH/EXPORT_DRAFT/MANUAL_ACTION',
  provider_id       VARCHAR(100) NULL COMMENT 'provider',
  data_element_id   VARCHAR(128) NULL COMMENT 'data element',
  status            VARCHAR(20)  NOT NULL COMMENT 'PENDING/CLAIMED/SUCCEEDED/BLOCKED/UNKNOWN/FAILED',
  idempotency_key   VARCHAR(128) NOT NULL COMMENT '冪等key',
  claim_token       VARCHAR(64)  NULL COMMENT 'claim token',
  lease_until       DATETIME(6)  NULL COMMENT 'lease 期限',
  result_count      INT          NULL COMMENT '結果件数',
  result_hash       CHAR(64)     NULL COMMENT '結果 hash',
  blocked_reason    VARCHAR(50)  NULL COMMENT '拒否理由コード',
  evidence_ref      VARCHAR(128) NULL COMMENT '証跡参照',
  row_version       BIGINT       NOT NULL DEFAULT 0 COMMENT '楽観lock',
  created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uk_privacy_action_idem UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT chk_privacy_action_type CHECK (action_type IN ('SEARCH', 'EXPORT_DRAFT', 'MANUAL_ACTION')),
  CONSTRAINT chk_privacy_action_status
    CHECK (status IN ('PENDING', 'CLAIMED', 'SUCCEEDED', 'BLOCKED', 'UNKNOWN', 'FAILED')),
  CONSTRAINT fk_privacy_action_request FOREIGN KEY (tenant_id, request_id)
    REFERENCES t_privacy_request (tenant_id, id),
  INDEX idx_privacy_action_request (tenant_id, request_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='DSAR の検索・草案・手動対応の記録';

CREATE TABLE t_privacy_export_package (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id       VARCHAR(100) NOT NULL COMMENT '論理tenant識別子',
  request_id      BIGINT       NOT NULL COMMENT 't_privacy_request.id',
  stored_name     VARCHAR(255) NOT NULL COMMENT 'FileStorageService の保存名。汎用 file 経路からは取得させない（§3.5.2）',
  content_sha256  CHAR(64)     NOT NULL COMMENT '内容 hash',
  size_bytes      BIGINT       NOT NULL COMMENT 'byte 数',
  status          VARCHAR(20)  NOT NULL COMMENT 'DRAFT/APPROVED/DELIVERED/PURGED',
  purge_after     DATETIME(6)  NOT NULL COMMENT 'この日時以降に削除する',
  created_by      BIGINT       NOT NULL COMMENT '作成者 sys_user.id',
  created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  approved_by     BIGINT       NULL COMMENT '承認者 sys_user.id（作成者と別人）',
  approved_at     DATETIME(6)  NULL COMMENT '承認日時',
  purged_at       DATETIME(6)  NULL COMMENT '削除日時',
  CONSTRAINT uk_privacy_export_package UNIQUE (tenant_id, stored_name),
  CONSTRAINT uk_privacy_export_package_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT uk_privacy_export_package_request UNIQUE (tenant_id, id, request_id),
  CONSTRAINT chk_privacy_export_package_sod CHECK (approved_by IS NULL OR approved_by <> created_by),
  CONSTRAINT chk_privacy_export_package_approval CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
  CONSTRAINT chk_privacy_export_package_status CHECK (status IN ('DRAFT', 'APPROVED', 'DELIVERED', 'PURGED')),
  CONSTRAINT chk_privacy_export_package_size CHECK (size_bytes >= 0),
  CONSTRAINT fk_privacy_export_request FOREIGN KEY (tenant_id, request_id)
    REFERENCES t_privacy_request (tenant_id, id),
  INDEX idx_privacy_export_package_stored_name (stored_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='DSAR 開示 package（一時保存）';

-- request と package は相互に参照するため、承認 package の FK は両表の作成後に足す。
-- (tenant_id, approved_package_id, id) を package の (tenant_id, id, request_id) に結び、
-- 同じ tenant の別 request の package を承認対象にできないようにする（R2-P3-5）。
ALTER TABLE t_privacy_request
  ADD CONSTRAINT fk_privacy_request_approved_package
  FOREIGN KEY (tenant_id, approved_package_id, id)
  REFERENCES t_privacy_export_package (tenant_id, id, request_id);
