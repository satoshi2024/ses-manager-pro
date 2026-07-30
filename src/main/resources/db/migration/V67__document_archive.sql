-- ============================================================
-- V67: 法定文書台帳・電子保存 (legal-document-ledger-archive)
-- 電帳法を意識した文書原本・版・台帳・廃棄・アクセス管理
-- ============================================================

-- ============================================================
-- 1. m_document_type — 文書種別マスタ
-- ============================================================
CREATE TABLE m_document_type (
  id                     BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  code                   VARCHAR(50)   NOT NULL COMMENT '種別コード (例: CONTRACT, INVOICE_OUT)',
  name                   VARCHAR(100)  NOT NULL COMMENT '種別名',
  direction              VARCHAR(10)   NOT NULL COMMENT '方向: OUTGOING/INCOMING/INTERNAL',
  retention_years        INT           NOT NULL COMMENT '法定保存年数',
  retention_start_rule   VARCHAR(50)   NOT NULL COMMENT '起算日ルール: TRANSACTION_DATE/SIGNED_AT/CLOSED_AT/DISPATCH_END',
  legal_hold_supported   TINYINT       NOT NULL DEFAULT 1 COMMENT '法的holdの可否 (1=可,0=不可)',
  created_at             DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at             DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag           TINYINT       NOT NULL DEFAULT 0 COMMENT '論理削除',
  UNIQUE KEY uk_document_type_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文書種別マスタ';

-- ============================================================
-- 2. t_document — 文書台帳
-- ============================================================
CREATE TABLE t_document (
  id                       BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id                VARCHAR(100)  NOT NULL DEFAULT 'default' COMMENT 'テナントID',
  legal_entity_id          VARCHAR(100)  COMMENT '法人ID（将来multi-entity用）',
  document_type            VARCHAR(50)   NOT NULL COMMENT '文書種別コード (m_document_type.code 参照)',
  document_no              VARCHAR(100)  COMMENT '文書番号',
  title                    VARCHAR(500)  COMMENT '文書タイトル',
  counterparty_type        VARCHAR(50)   COMMENT '相手先区分: CUSTOMER/BP/INTERNAL',
  counterparty_id          BIGINT        COMMENT '相手先ID（顧客/BPマスタ）',
  counterparty_name_snapshot VARCHAR(200) COMMENT '相手先名スナップショット（変更不変保証）',
  transaction_date         DATE          COMMENT '取引日',
  amount                   DECIMAL(15,0) COMMENT '金額（円）。金額なし文書はNULL',
  currency                 CHAR(3)       NOT NULL DEFAULT 'JPY' COMMENT '通貨コード',
  direction                VARCHAR(10)   NOT NULL COMMENT '方向: OUTGOING/INCOMING/INTERNAL',
  status                   VARCHAR(20)   NOT NULL DEFAULT 'DRAFT' COMMENT '状態: DRAFT/CONFIRMED/AMENDED/CANCELLED/DISPOSED',
  retention_until          DATE          COMMENT '保存期限（計算済み固定値。NULLは未確定＝廃棄候補から除外）',
  legal_hold_flag          TINYINT       NOT NULL DEFAULT 0 COMMENT '法的hold中フラグ (1=hold中)',
  `version`                BIGINT        NOT NULL DEFAULT 1 COMMENT '楽観ロック用バージョン',
  created_by               BIGINT        COMMENT '作成者ユーザーID',
  created_at               DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at               DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag             TINYINT       NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',

  INDEX idx_document_type      (document_type),
  INDEX idx_document_transaction_date (transaction_date),
  INDEX idx_document_amount    (amount),
  INDEX idx_document_counterparty (counterparty_type, counterparty_id),
  INDEX idx_document_status    (status),
  INDEX idx_document_retention (retention_until),
  INDEX idx_document_legal_hold (legal_hold_flag),
  INDEX idx_document_tenant    (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文書台帳';

-- ============================================================
-- 3. t_document_version — 文書版（append-only）
-- ============================================================
CREATE TABLE t_document_version (
  id               BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  document_id      BIGINT        NOT NULL COMMENT '文書ID',
  version_no       INT           NOT NULL COMMENT '版番号（1始まり、単調増加）',
  storage_key      VARCHAR(500)  NOT NULL COMMENT 'Storageオブジェクトキー（パスに元ファイル名を使わない）',
  original_name    VARCHAR(500)  NOT NULL COMMENT '元ファイル名（表示・manifest用）',
  content_type     VARCHAR(100)  COMMENT 'MIMEタイプ',
  size_bytes       BIGINT        COMMENT 'ファイルサイズ（バイト）',
  sha256           CHAR(64)      NOT NULL COMMENT 'SHA-256ハッシュ値（hex小文字）',
  source_type      VARCHAR(50)   NOT NULL COMMENT '取得経路: GENERATED/RECEIVED/CLOUDSIGN_SIGNED/CLOUDSIGN_CERT/MANUAL',
  business_key     VARCHAR(200)  COMMENT '業務一意キー（source_type+business_key+version_discriminatorで冪等制御）',
  version_discriminator VARCHAR(100) COMMENT '版識別子（同一business_keyでの複数版区別）',
  external_id      VARCHAR(200)  COMMENT '外部文書ID（CloudSign document_id等）',
  scan_status      VARCHAR(30)   NOT NULL DEFAULT 'PENDING' COMMENT 'scanステータス: PENDING/CLEAN/REJECTED/SKIPPED。NULLは未scan扱い（閲覧不可）',
  change_reason    VARCHAR(500)  COMMENT '差替理由（旧版との差分説明）',
  created_by       BIGINT        NOT NULL COMMENT '登録ユーザーID',
  created_at       DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '登録日時',
  updated_at       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag     TINYINT       NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',

  UNIQUE KEY uk_document_version_no (document_id, version_no),
  UNIQUE KEY uk_document_idempotency (source_type, business_key, version_discriminator),
  INDEX idx_dv_document   (document_id),
  INDEX idx_dv_sha256     (sha256),
  INDEX idx_dv_external   (external_id),
  INDEX idx_dv_scan_status (scan_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文書版（append-only）';

-- ============================================================
-- 4. t_document_link — 文書と業務エンティティの関連
-- ============================================================
CREATE TABLE t_document_link (
  id           BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  document_id  BIGINT       NOT NULL COMMENT '文書ID',
  target_type  VARCHAR(50)  NOT NULL COMMENT 'リンク先種別: CUSTOMER/PROJECT/CONTRACT/INVOICE/etc.',
  target_id    BIGINT       NOT NULL COMMENT 'リンク先エンティティID',
  created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag TINYINT      NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',

  UNIQUE KEY uk_document_link (document_id, target_type, target_id),
  INDEX idx_dl_document  (document_id),
  INDEX idx_dl_target    (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文書業務リンク';

-- ============================================================
-- 5. t_document_access_log — 文書アクセス監査ログ
-- ============================================================
CREATE TABLE t_document_access_log (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  document_id BIGINT       NOT NULL COMMENT '文書ID',
  version_id  BIGINT       COMMENT '文書版ID（特定版操作の場合）',
  action      VARCHAR(30)  NOT NULL COMMENT 'アクション: VIEW/DOWNLOAD/EXPORT/REGISTER/AMEND/DISPOSE',
  user_id     BIGINT       NOT NULL COMMENT '操作ユーザーID',
  ip_hash     VARCHAR(64)  COMMENT 'IPアドレスのSHA-256ハッシュ（生IPは保存しない）',
  occurred_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作日時',

  INDEX idx_dal_document   (document_id),
  INDEX idx_dal_user       (user_id),
  INDEX idx_dal_occurred   (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文書アクセス監査ログ';

-- ============================================================
-- 6. t_document_disposal_request — 廃棄申請
-- ============================================================
CREATE TABLE t_document_disposal_request (
  id            BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  document_id   BIGINT       NOT NULL COMMENT '文書ID',
  requested_by  BIGINT       NOT NULL COMMENT '廃棄申請者ユーザーID（管理者のみ）',
  approved_by   BIGINT       COMMENT '廃棄承認者ユーザーID（申請者と別の管理者）',
  status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '状態: PENDING/APPROVED/REJECTED/DISPOSED/FAILED',
  reason        VARCHAR(1000) NOT NULL COMMENT '廃棄理由',
  disposed_at   DATETIME     COMMENT '廃棄実施日時',
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '申請日時',
  updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag  TINYINT      NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',

  INDEX idx_ddr_document (document_id),
  INDEX idx_ddr_status   (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文書廃棄申請';

-- ============================================================
-- シードデータ: 文書種別マスタ（provisional mapping準拠）
-- ============================================================
INSERT INTO m_document_type (code, name, direction, retention_years, retention_start_rule, legal_hold_supported) VALUES
  ('CONTRACT',         '契約書',           'OUTGOING', 10, 'CLOSED_AT',        1),
  ('INVOICE_OUT',      '請求書（発行）',   'OUTGOING', 10, 'TRANSACTION_DATE', 1),
  ('INVOICE_IN',       '請求書（受領）',   'INCOMING', 10, 'TRANSACTION_DATE', 1),
  ('QUOTATION',        '見積書',           'OUTGOING', 10, 'TRANSACTION_DATE', 1),
  ('WORK_REPORT',      '作業報告書',       'OUTGOING', 10, 'TRANSACTION_DATE', 1),
  ('SIGNED_PDF',       '署名済PDF',        'OUTGOING', 10, 'SIGNED_AT',        1),
  ('ESIGN_CERT',       '合意締結証明書',   'INCOMING', 10, 'SIGNED_AT',        1),
  ('DISPATCH_LEDGER',  '派遣元管理台帳',   'INTERNAL',  3, 'DISPATCH_END',     1);
