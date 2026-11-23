-- ============================================================
-- V203: 機能の開放を system 内で行う（FE-A01。.kiro/specs/feature-enablement-console/design.md §2）
--
-- 開放単位の開放状態・申請・承認で変える設定・署名・署名の延長の記録・署名の組・遷移の履歴の9表と、
-- 追記専用の3表（履歴・組の member・延長の記録）の UPDATE / DELETE を拒否する trigger 6件、menu の seed、
-- data element の catalog 行2件を作る。開放状態の行は作らない（行の無い開放単位は CLOSED として扱う）。
--   - t_feature_enablement_generation   … tenant ごとの世代（全ての書込みが最初に lock する。lock の順は design §5.1）
--   - t_feature_enablement_request      … 申請（開放・設定の変更・署名の延長）。未決は対象ごとに1件（pending_slot）
--   - t_feature_enablement_state        … 開放単位の現在の開放状態（申請者・承認者を申請の行と複合 FK で一致させる）
--   - t_feature_enablement_setting      … 承認で変える設定（現在は NF02 の公開範囲だけ）
--   - t_feature_sign_off                … 署名の登録・確認・失効・取消し・差替え・消去（種別ごとに確認済みは1件）
--   - t_feature_sign_off_extension      … 証拠の類の署名の延長の記録（追記専用）
--   - t_feature_sign_off_bundle         … 署名の組（互いに束縛し合う署名の集合）
--   - t_feature_sign_off_bundle_member  … 組と署名の対応（追記専用。種別・tenant を署名の行と複合 FK で一致させる）
--   - t_feature_enablement_event        … 全ての遷移の履歴（追記専用）
--
-- 規則:
--   - 作成の順は FK の向き（generation → request → state → setting → sign_off → sign_off_extension → sign_off_bundle
--     → sign_off_bundle_member → event）。FK に参照動作（ON DELETE 等）を付けない。
--   - どの表も deleted_flag を持たない（論理削除しない）。時刻の列は DATETIME(6) で、JVM の時計
--     （AutoFillMetaObjectHandler.rowTimestamp(clock)）で書く。列の DEFAULT に CURRENT_TIMESTAMP を使わない。
--     日付の列（署名日・失効日）は JST の業務日の DATE。
--   - 申請者と承認者・却下者、登録者と確認者は別人（CHECK）。開放状態・設定・延長の記録の申請者・承認者は、参照する申請の行の
--     申請者・決定者と複合 FK で一致させる（申請の行が APPROVED であることは service と試験で固定する）。
--   - 未決の申請・確認済みの署名・確認済みの組は、それぞれ STORED の生成列と一意索引で1件に限る（NULL は一意性の対象外）。
--   - trigger は V195 と同じ単文の SIGNAL（DELIMITER・BEGIN を使わない）。DEFINER は書かない（migrator で作られる。
--     deploy-runbook §1.2 の DEFINER・binlog の規則）。trigger の件数は deploy-runbook §1.2・§3.4 の件数に含める。
-- 規約（決定XB-6）: V1 へ書き戻さず、本 migration だけで追加する。H2 の replay は
-- src/test/resources/sql/schema-feature-enablement-h2.sql（STORED 指定・COMMENT・trigger なし）と engineer-schema-h2.sql が
-- 同じ定義を持ち、FeatureEnablementSchemaContractTest が列・型・NULL 可否・生成式・一意索引・CHECK・FK の一致を固定する。
-- ============================================================

CREATE TABLE t_feature_enablement_generation (
    tenant_id   VARCHAR(100) NOT NULL COMMENT '設定の tenant',
    generation  BIGINT       NOT NULL COMMENT '変更の通し番号（書込みの transaction が1つ上げる）',
    updated_at  DATETIME(6)  NOT NULL COMMENT '最後に上げた時刻',
    PRIMARY KEY (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='機能の開放の世代（tenantごと）';

CREATE TABLE t_feature_enablement_request (
    id                    BIGINT        NOT NULL AUTO_INCREMENT COMMENT '申請の番号',
    tenant_id             VARCHAR(100)  NOT NULL COMMENT '設定の tenant',
    request_type          VARCHAR(20)   NOT NULL COMMENT 'OPEN / SETTING_CHANGE / SIGN_OFF_EXTENSION',
    target_key            VARCHAR(64)   NOT NULL COMMENT '開放単位の key・設定の key・signoff:<id>',
    proposed_value        VARCHAR(100)  NULL     COMMENT 'SETTING_CHANGE の値（NULL は未設定へ戻す）・延長の新しい失効日',
    status                VARCHAR(20)   NOT NULL COMMENT 'PENDING / APPROVED / REJECTED / WITHDRAWN / EXPIRED / STALE / CANCELLED',
    pending_slot          TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN 1 ELSE NULL END) STORED
                          COMMENT '未決の申請だけ1（生成列。誰も書かない）',
    condition_fingerprint CHAR(64)      NOT NULL COMMENT '条件の指紋（design §4.5）',
    condition_summary     VARCHAR(2000) NOT NULL COMMENT '条件の code と成否だけ（値・氏名を含めない）',
    reason                VARCHAR(500)  NULL     COMMENT '申請の理由（個人名・連絡先を書かない）',
    requested_by          BIGINT        NOT NULL COMMENT '申請者 sys_user.id',
    requested_at          DATETIME(6)   NOT NULL COMMENT '申請の時刻',
    expires_at            DATETIME(6)   NOT NULL COMMENT '未決の期限',
    decided_by            BIGINT        NULL     COMMENT '承認者・却下者・取り下げた申請者 sys_user.id',
    decided_at            DATETIME(6)   NULL     COMMENT '終えた時刻',
    decision_reason       VARCHAR(500)  NULL     COMMENT '却下・取下げ等の理由（個人名・連絡先を書かない）',
    version               INT           NOT NULL DEFAULT 0 COMMENT '楽観 lock の版',
    PRIMARY KEY (id),
    CONSTRAINT uk_fe_request_pending UNIQUE (tenant_id, target_key, pending_slot),
    CONSTRAINT uk_fe_request_actors UNIQUE (id, requested_by, decided_by),
    KEY idx_fe_request_status (tenant_id, status, expires_at),
    CONSTRAINT ck_fe_request_type CHECK (request_type IN ('OPEN', 'SETTING_CHANGE', 'SIGN_OFF_EXTENSION')),
    CONSTRAINT ck_fe_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'EXPIRED', 'STALE',
        'CANCELLED')),
    CONSTRAINT ck_fe_request_decision CHECK (
           (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decided_by <> requested_by)
        OR (status = 'WITHDRAWN' AND decided_by IS NOT NULL AND decided_by = requested_by AND decided_at IS NOT NULL)
        OR (status IN ('EXPIRED', 'STALE', 'CANCELLED') AND decided_by IS NULL AND decided_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='機能の開放の申請';

CREATE TABLE t_feature_enablement_state (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '行の番号',
    tenant_id           VARCHAR(100) NOT NULL COMMENT '設定の tenant',
    feature_key         VARCHAR(64)  NOT NULL COMMENT '開放単位の key',
    state               VARCHAR(30)  NOT NULL COMMENT 'CLOSED / OPEN / OPEN_PENDING_RESTART / AUTO_CLOSED / MANUALLY_CLOSED',
    version             INT          NOT NULL DEFAULT 0 COMMENT '楽観 lock の版',
    open_request_id     BIGINT       NULL     COMMENT '開放を承認した申請',
    opened_by           BIGINT       NULL     COMMENT '申請者 sys_user.id',
    approved_by         BIGINT       NULL     COMMENT '承認者 sys_user.id（申請者と別人）',
    opened_at           DATETIME(6)  NULL     COMMENT '開放の時刻',
    restart_applied_at  DATETIME(6)  NULL     COMMENT 'NF05: 起動で反映した時刻',
    closed_reason       VARCHAR(64)  NULL     COMMENT '閉鎖・自動閉鎖の理由の code',
    closed_by           BIGINT       NULL     COMMENT '手動閉鎖の管理者（自動閉鎖は NULL）',
    closed_at           DATETIME(6)  NULL     COMMENT '閉鎖の時刻',
    unverifiable_since  DATETIME(6)  NULL     COMMENT '評価できない状態の始まり（全 instance が共有する一時の閉鎖）',
    unverifiable_reason VARCHAR(64)  NULL     COMMENT '評価できない理由の code',
    updated_at          DATETIME(6)  NOT NULL COMMENT '最後に変えた時刻',
    PRIMARY KEY (id),
    CONSTRAINT uk_fe_state UNIQUE (tenant_id, feature_key),
    CONSTRAINT ck_fe_state_key CHECK (feature_key IN ('NF01', 'NF01_DUAL_CONFIRMATION', 'NF02', 'NF02_CUSTOMER_PORTAL',
        'NF03', 'NF03_CERTIFICATION_PII', 'NF04', 'NF05_INBOUND', 'NF05_OUTBOUND', 'NF06', 'NF07_RETENTION', 'NF07_DSAR',
        'NF10')),
    CONSTRAINT ck_fe_state_value CHECK (state IN ('CLOSED', 'OPEN', 'OPEN_PENDING_RESTART', 'AUTO_CLOSED',
        'MANUALLY_CLOSED')),
    CONSTRAINT ck_fe_state_open CHECK (state NOT IN ('OPEN', 'OPEN_PENDING_RESTART')
        OR (open_request_id IS NOT NULL AND opened_by IS NOT NULL AND approved_by IS NOT NULL
            AND opened_by <> approved_by AND opened_at IS NOT NULL)),
    CONSTRAINT ck_fe_state_restart CHECK (state <> 'OPEN_PENDING_RESTART' OR feature_key IN ('NF05_INBOUND',
        'NF05_OUTBOUND')),
    CONSTRAINT ck_fe_state_closed CHECK (state NOT IN ('AUTO_CLOSED', 'MANUALLY_CLOSED')
        OR (closed_reason IS NOT NULL AND closed_at IS NOT NULL)),
    CONSTRAINT ck_fe_state_manual CHECK (state <> 'MANUALLY_CLOSED' OR closed_by IS NOT NULL),
    CONSTRAINT fk_fe_state_request FOREIGN KEY (open_request_id, opened_by, approved_by)
        REFERENCES t_feature_enablement_request (id, requested_by, decided_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='機能の開放状態';

CREATE TABLE t_feature_enablement_setting (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '行の番号',
    tenant_id     VARCHAR(100) NOT NULL COMMENT '設定の tenant',
    setting_key   VARCHAR(64)  NOT NULL COMMENT '設定の key',
    setting_value VARCHAR(100) NULL     COMMENT '値（NULL は未設定）',
    version       INT          NOT NULL DEFAULT 0 COMMENT '楽観 lock の版',
    request_id    BIGINT       NOT NULL COMMENT '承認した申請',
    requested_by  BIGINT       NOT NULL COMMENT '申請者（承認者と別人）',
    approved_by   BIGINT       NOT NULL COMMENT '承認者',
    approved_at   DATETIME(6)  NOT NULL COMMENT '承認の時刻',
    PRIMARY KEY (id),
    CONSTRAINT uk_fe_setting UNIQUE (tenant_id, setting_key),
    CONSTRAINT ck_fe_setting_key CHECK (setting_key IN ('nf02-customer-portal.visible-since')),
    CONSTRAINT ck_fe_setting_separation CHECK (requested_by <> approved_by),
    CONSTRAINT fk_fe_setting_request FOREIGN KEY (request_id, requested_by, approved_by)
        REFERENCES t_feature_enablement_request (id, requested_by, decided_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='承認で変える設定';

CREATE TABLE t_feature_sign_off (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '署名の番号',
    tenant_id         VARCHAR(100) NOT NULL COMMENT '設定の tenant',
    kind_code         VARCHAR(64)  NOT NULL COMMENT '署名の種別 code（目録で検査する）',
    reference         VARCHAR(128) NOT NULL COMMENT '決定 ID・文書 ID・ticket 等',
    signer_role       VARCHAR(64)  NOT NULL COMMENT '署名者の役割の code',
    signer_name       VARCHAR(100) NULL     COMMENT '署名者の氏名（任意の個人データ。消去の対象）',
    signed_on         DATE         NOT NULL COMMENT '署名日（JST 業務日）',
    expires_on        DATE         NOT NULL COMMENT '失効日（JST 業務日。当日から無効）',
    structured_values TEXT         NULL     COMMENT '種別ごとの値（JSON。秘密値を置かない）',
    stored_name       VARCHAR(255) NULL     COMMENT '文書の保存名（消去の後は NULL）',
    original_name     VARCHAR(255) NULL     COMMENT '文書の元の file 名（氏名を含み得るため消去の対象）',
    kind_class        VARCHAR(10)  NOT NULL COMMENT 'DECISION（決定の類）/ EVIDENCE（証拠の類）',
    content_sha256    CHAR(64)     NOT NULL COMMENT '文書の SHA-256（小文字 hex）',
    size_bytes        BIGINT       NOT NULL COMMENT '文書の大きさ',
    status            VARCHAR(20)  NOT NULL COMMENT 'REGISTERED / CONFIRMED / REJECTED / REVOKED / SUPERSEDED / EXPIRED / PURGED',
    active_slot       TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'CONFIRMED' THEN 1 ELSE NULL END) STORED
                      COMMENT '確認済みの署名だけ1（生成列。誰も書かない）',
    registered_by     BIGINT       NOT NULL COMMENT '登録者 sys_user.id',
    registered_at     DATETIME(6)  NOT NULL COMMENT '登録の時刻',
    confirmed_by      BIGINT       NULL     COMMENT '確認者 sys_user.id（登録者と別人）',
    confirmed_at      DATETIME(6)  NULL     COMMENT '確認の時刻',
    ended_by          BIGINT       NULL     COMMENT '却下・取消しの管理者',
    ended_at          DATETIME(6)  NULL     COMMENT '終えた時刻',
    end_reason        VARCHAR(64)  NULL     COMMENT '終えた理由の code',
    superseded_by_id  BIGINT       NULL     COMMENT '差し替えた新しい署名',
    purge_after       DATE         NULL     COMMENT '氏名・文書を消す期日',
    purged_at         DATETIME(6)  NULL     COMMENT '消去の時刻',
    version           INT          NOT NULL DEFAULT 0 COMMENT '楽観 lock の版',
    PRIMARY KEY (id),
    CONSTRAINT uk_fe_sign_off_active UNIQUE (tenant_id, kind_code, active_slot),
    CONSTRAINT uk_fe_sign_off_kind UNIQUE (id, kind_code, tenant_id),
    CONSTRAINT uk_fe_sign_off_stored_name UNIQUE (stored_name),
    KEY idx_fe_sign_off_kind (tenant_id, kind_code, status),
    KEY idx_fe_sign_off_purge (status, purge_after),
    CONSTRAINT ck_fe_sign_off_status CHECK (status IN ('REGISTERED', 'CONFIRMED', 'REJECTED', 'REVOKED', 'SUPERSEDED',
        'EXPIRED', 'PURGED')),
    CONSTRAINT ck_fe_sign_off_separation CHECK (confirmed_by IS NULL OR confirmed_by <> registered_by),
    CONSTRAINT ck_fe_sign_off_confirmed CHECK (status NOT IN ('CONFIRMED', 'SUPERSEDED', 'EXPIRED')
        OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)),
    CONSTRAINT ck_fe_sign_off_dates CHECK (expires_on > signed_on),
    CONSTRAINT ck_fe_sign_off_purged CHECK (status <> 'PURGED'
        OR (stored_name IS NULL AND signer_name IS NULL AND original_name IS NULL AND purged_at IS NOT NULL)),
    CONSTRAINT ck_fe_sign_off_class CHECK (kind_class IN ('DECISION', 'EVIDENCE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='機能の開放の署名';

CREATE TABLE t_feature_sign_off_extension (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '延長の記録の番号',
    tenant_id       VARCHAR(100) NOT NULL COMMENT '設定の tenant',
    sign_off_id     BIGINT       NOT NULL COMMENT '延長した署名',
    request_id      BIGINT       NOT NULL COMMENT '延長の申請（request_type = SIGN_OFF_EXTENSION）',
    old_expires_on  DATE         NOT NULL COMMENT '延長の前の失効日',
    new_expires_on  DATE         NOT NULL COMMENT '延長の後の失効日',
    requested_by    BIGINT       NOT NULL COMMENT '延長の申請者',
    confirmed_by    BIGINT       NOT NULL COMMENT '延長を承認した管理者（申請者と別人）',
    confirmed_at    DATETIME(6)  NOT NULL COMMENT '承認の時刻',
    PRIMARY KEY (id),
    KEY idx_fe_extension_sign_off (sign_off_id, id),
    CONSTRAINT ck_fe_extension_separation CHECK (confirmed_by <> requested_by),
    CONSTRAINT ck_fe_extension_dates CHECK (new_expires_on > old_expires_on),
    CONSTRAINT fk_fe_extension_sign_off FOREIGN KEY (sign_off_id) REFERENCES t_feature_sign_off (id),
    CONSTRAINT fk_fe_extension_request FOREIGN KEY (request_id, requested_by, confirmed_by)
        REFERENCES t_feature_enablement_request (id, requested_by, decided_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='署名の延長の記録（追記専用）';

CREATE TABLE t_feature_sign_off_bundle (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '組の番号',
    tenant_id     VARCHAR(100) NOT NULL COMMENT '設定の tenant',
    bundle_kind   VARCHAR(64)  NOT NULL COMMENT '目録の組の種類',
    status        VARCHAR(20)  NOT NULL COMMENT 'REGISTERED / CONFIRMED / REJECTED / SUPERSEDED / REVOKED',
    active_slot   TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'CONFIRMED' THEN 1 ELSE NULL END) STORED
                  COMMENT '確認済みの組だけ1（生成列。誰も書かない）',
    registered_by BIGINT       NOT NULL COMMENT '登録者 sys_user.id',
    registered_at DATETIME(6)  NOT NULL COMMENT '登録の時刻',
    confirmed_by  BIGINT       NULL     COMMENT '確認者 sys_user.id（登録者と別人）',
    confirmed_at  DATETIME(6)  NULL     COMMENT '確認の時刻',
    ended_by      BIGINT       NULL     COMMENT '却下・取消しの管理者',
    ended_at      DATETIME(6)  NULL     COMMENT '終えた時刻',
    end_reason    VARCHAR(64)  NULL     COMMENT '終えた理由の code',
    version       INT          NOT NULL DEFAULT 0 COMMENT '楽観 lock の版',
    PRIMARY KEY (id),
    CONSTRAINT uk_fe_bundle_active UNIQUE (tenant_id, bundle_kind, active_slot),
    CONSTRAINT uk_fe_bundle_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_fe_bundle_status CHECK (status IN ('REGISTERED', 'CONFIRMED', 'REJECTED', 'SUPERSEDED', 'REVOKED')),
    CONSTRAINT ck_fe_bundle_separation CHECK (confirmed_by IS NULL OR confirmed_by <> registered_by),
    CONSTRAINT ck_fe_bundle_confirmed CHECK (status NOT IN ('CONFIRMED', 'SUPERSEDED')
        OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='署名の組';

CREATE TABLE t_feature_sign_off_bundle_member (
    bundle_id     BIGINT       NOT NULL COMMENT '組',
    tenant_id     VARCHAR(100) NOT NULL COMMENT '設定の tenant',
    kind_code     VARCHAR(64)  NOT NULL COMMENT '署名の種別 code',
    sign_off_id   BIGINT       NOT NULL COMMENT '署名',
    carried_over  TINYINT      NOT NULL COMMENT '1: 旧い組から引き継いだ確認済みの署名',
    PRIMARY KEY (bundle_id, kind_code),
    CONSTRAINT fk_fe_bundle_member_bundle FOREIGN KEY (bundle_id, tenant_id)
        REFERENCES t_feature_sign_off_bundle (id, tenant_id),
    CONSTRAINT fk_fe_bundle_member_sign_off FOREIGN KEY (sign_off_id, kind_code, tenant_id)
        REFERENCES t_feature_sign_off (id, kind_code, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='署名の組の member（追記専用）';

CREATE TABLE t_feature_enablement_event (
    id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '履歴の番号',
    tenant_id         VARCHAR(100)  NOT NULL COMMENT '設定の tenant',
    subject_type      VARCHAR(20)   NOT NULL COMMENT 'FEATURE / REQUEST / SIGN_OFF / BUNDLE / SETTING / SUBGATE / MASTER',
    subject_key       VARCHAR(64)   NOT NULL COMMENT '開放単位・種別・設定の key',
    subject_id        BIGINT        NULL     COMMENT '申請・署名・組の番号',
    event_type        VARCHAR(40)   NOT NULL COMMENT '遷移の種類',
    from_state        VARCHAR(30)   NULL     COMMENT '前の状態',
    to_state          VARCHAR(30)   NULL     COMMENT '後の状態',
    reason_code       VARCHAR(64)   NULL     COMMENT '理由の code',
    condition_summary VARCHAR(2000) NULL     COMMENT '条件の code と成否だけ（値・氏名を含めない）',
    actor_type        VARCHAR(10)   NOT NULL COMMENT 'USER / SYSTEM',
    actor_user_id     BIGINT        NULL     COMMENT '操作した利用者 sys_user.id',
    correlation_id    VARCHAR(64)   NULL     COMMENT '同じ操作の行をまとめる ID',
    generation        BIGINT        NOT NULL COMMENT '遷移の後の世代',
    occurred_at       DATETIME(6)   NOT NULL COMMENT '遷移の時刻',
    PRIMARY KEY (id),
    KEY idx_fe_event_subject (tenant_id, subject_type, subject_key, id),
    KEY idx_fe_event_time (tenant_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='機能の開放の履歴（追記専用）';

-- 追記専用の3表の UPDATE・DELETE を拒否する（アプリ側は AppendOnlyMapper が同じ操作を拒否する）
CREATE TRIGGER trg_fe_event_no_update BEFORE UPDATE ON t_feature_enablement_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_feature_enablement_event is append-only';

CREATE TRIGGER trg_fe_event_no_delete BEFORE DELETE ON t_feature_enablement_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_feature_enablement_event is append-only';

CREATE TRIGGER trg_fe_bundle_member_no_update BEFORE UPDATE ON t_feature_sign_off_bundle_member
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_feature_sign_off_bundle_member is append-only';

CREATE TRIGGER trg_fe_bundle_member_no_delete BEFORE DELETE ON t_feature_sign_off_bundle_member
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_feature_sign_off_bundle_member is append-only';

CREATE TRIGGER trg_fe_extension_no_update BEFORE UPDATE ON t_feature_sign_off_extension
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_feature_sign_off_extension is append-only';

CREATE TRIGGER trg_fe_extension_no_delete BEFORE DELETE ON t_feature_sign_off_extension
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_feature_sign_off_extension is append-only';

-- ------------------------------------------------------------
-- menu と role の付与（管理者だけ）。t_permission_group_action には足さない（管理者は role-admin の '*' で通る。
-- 管理者以外の group に feature-enablement.* を seed しない。CLAUDE.md の resource wildcard＋deny）。
-- 画面・API は FE-A09・A10 で足す。sort_order は未使用値 263。
-- ------------------------------------------------------------
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'feature-enablement', '機能の開放', '/feature-enablement', '/api/feature-enablement', 263
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'feature-enablement');

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', m.id
FROM m_menu m
WHERE m.menu_key = 'feature-enablement'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu r WHERE r.role = '管理者' AND r.menu_id = m.id);

-- ------------------------------------------------------------
-- data element の catalog 行（V198 と同じ列）。署名者の氏名と署名の文書。owner・データ分類・inventory 状態は既定の UNKNOWN、
-- 保持期間は持たない（D-02 の署名で確定する）。provider は無い（NULL）。氏名と文書は消去の経路（FeatureSignOffPurgeScheduler。
-- PrivacyHoldPathGuard の D17）を持つため、hold の gate が判定できるよう subject_attribution は NOT_ATTRIBUTABLE とする
-- （署名者は F2 provider で本人を導出できない。DATA_ELEMENT hold があるときだけ消去を止める）。値の正本は pii-inventory.md の DB-151。
-- tenant は sys_user に存在する tenant から取る。行が既にあれば入れない（V198 と同じ）。
-- ------------------------------------------------------------
INSERT INTO m_pii_data_element (tenant_id, data_element_id, storage_kind, storage_ref, provider_id,
                                subject_attribution, inventory_ref)
SELECT t.tenant_id, d.data_element_id, d.storage_kind, d.storage_ref, d.provider_id, d.subject_attribution,
       d.inventory_ref
FROM (SELECT DISTINCT tenant_id FROM sys_user WHERE tenant_id IS NOT NULL AND tenant_id <> '') t
CROSS JOIN (
  SELECT 'FEATURE_SIGN_OFF_SIGNER_NAME' AS data_element_id, 'DB_COLUMN' AS storage_kind,
         't_feature_sign_off.signer_name' AS storage_ref, NULL AS provider_id,
         'NOT_ATTRIBUTABLE' AS subject_attribution, 'pii-inventory.md#DB-151' AS inventory_ref
  UNION ALL SELECT 'FEATURE_SIGN_OFF_DOCUMENT', 'FILE_OBJECT', 't_feature_sign_off.stored_name',
         NULL, 'NOT_ATTRIBUTABLE', 'pii-inventory.md#DB-151'
) d
WHERE NOT EXISTS (SELECT 1 FROM m_pii_data_element e
                  WHERE e.tenant_id = t.tenant_id AND e.data_element_id = d.data_element_id);
