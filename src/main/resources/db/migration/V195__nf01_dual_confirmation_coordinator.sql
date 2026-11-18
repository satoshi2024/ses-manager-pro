-- ============================================================
-- V195: NF01 二者確認coordinatorの表（B0 / W3-18）
--
-- 計画: .kiro/roadmap/2026-11-14-snf-nf01-fine-grained-epoch-plan.md §5.3〜§5.9・§5.12・§6.1 M1・§7.7
-- 決定: B-NF01-2（DDLは新migrationだけで追加）、§12 O-4（不変表のDB trigger採否。本migrationで採用）。
--
-- 1. 二者確認（DUAL_CONFIRMATION）の提出・確認・投影・冪等性・監査の7表を作る。行は作らない。
--      t_lifecycle_dual_submission           提出（revisionごとに1行。認可epochのsnapshotを持つ）       不変
--      t_lifecycle_dual_submission_document  提出に含めた文書版（1提出1文書。O-2）                       不変
--      t_lifecycle_dual_submission_org_fence 提出時の組織集合とそのepoch                                  不変
--      t_lifecycle_dual_confirmation         確認・差戻しの決定（提出ごとに1行）                         不変
--      t_lifecycle_dual_projection           taskごとの現在状態（PENDING / CONFIRMED / REJECTED）       可変（projection_version のCAS）
--      t_lifecycle_dual_idempotency          Idempotency-Key と要求hashの対応                            不変
--      t_lifecycle_dual_event                提出・supersede・確認・差戻し・拒否の追記監査（FKなし。§5.8） 不変
-- 2. 複合FKが参照する一意keyは既存のものを使い、本migrationでは追加しない（重複indexになるため）。
--      t_lifecycle_case   (tenant_id, id)              uk_lifecycle_case_tenant_id       （V189）
--      t_lifecycle_task   (case_id, id)                uk_lifecycle_task_case_id         （V189）
--      t_document_version (tenant_id, document_id, id) uk_document_version_tenant_doc_id （V189）
--      sys_user           (tenant_id, id)              uk_sys_user_tenant_id             （V165）
--    参照先の tenant_id は VARCHAR(100)・utf8mb4_unicode_ci（表の既定）であり、本migrationの各表も同じ文字集合・照合順序で作る。
--    組織はtenantに依存しない（計画 §2.6）ため、組織fenceは m_organization_unit (id) だけを参照する。
--    task世代（t_lifecycle_task の世代列）はFKにしない。attempt内でtask行の現在値と比べる（計画 §5.12、現行契約 §9.1）。
-- 3. 不変の6表へのUPDATE・DELETEを BEFORE trigger で拒否する（V132と同じ単文の SIGNAL。DELIMITER不要。計画 §12 O-4）。
--    mapper層でも同じ6表のUPDATE・DELETEを拒否する（共通基 com.ses.common.mybatis.AppendOnlyMapper。H2のreplayにtriggerを
--    置かないため）。AppendOnlySqlInjector はこれらのmapperへ更新・削除のMappedStatementを注入しない。
--    trigger 12件の追加により、配備runbookのtrigger件数は 55 → 67 になる（DBAのTRIGGER権限は既存のtriggerと同じ前提）。
--    自由記述列（business_comment・decision_comment）の削除・匿名化が必要になった場合は、本migrationを変えず、forward migrationで
--    列を限定したtriggerへ置き換える（計画 §12 O-8）。
-- 4. idempotency_key は ascii_bin（大文字小文字を区別する。MySQL 8.0 の ascii_bin は PAD SPACE で末尾空白を同一視する）。
--    ck_dual_idem_key で空文字と許可外の文字（末尾空白・改行を含む）を拒否し、APIの文字種 [A-Za-z0-9._~-] とDBを一致させる
--    （計画 §5.7）。正規表現の末尾anchorは末尾の改行の前でも一致するため、否定文字クラスが1文字も一致しないことで判定する。
--
-- 規約（決定XB-6）: V1と既存migrationは変更しない。H2のreplayは
-- src/test/resources/sql/schema-nf01-dual-confirmation-h2.sql と engineer-schema-h2.sql が同じ表を持つ（triggerは置かない）。
-- ============================================================

CREATE TABLE t_lifecycle_dual_submission (
    id                      BIGINT        AUTO_INCREMENT PRIMARY KEY,
    tenant_id               VARCHAR(100)  NOT NULL COMMENT 'テナントID（案件・提出者と同じtenant）',
    case_id                 BIGINT        NOT NULL COMMENT 't_lifecycle_case.id',
    task_id                 BIGINT        NOT NULL COMMENT 't_lifecycle_task.id',
    engineer_id             BIGINT        NOT NULL COMMENT 'caseの要員（attempt内で一致確認）',
    task_generation         BIGINT        NOT NULL COMMENT '提出時のtask世代',
    revision                INT           NOT NULL COMMENT '同一task・世代内の提出版（1から）',
    submitter_user_id       BIGINT        NOT NULL COMMENT '提出者 sys_user.id',
    submitter_role_snapshot VARCHAR(30)   NOT NULL COMMENT '提出時の提出者ロール',
    global_epoch            BIGINT        NOT NULL COMMENT '提出時の全体認可epoch',
    submitter_actor_epoch   BIGINT        NOT NULL COMMENT '提出時の提出者actor epoch',
    engineer_scope_epoch    BIGINT        NOT NULL COMMENT '提出時の要員scope epoch',
    task_version_snapshot   INT           NOT NULL COMMENT '提出時のtask行のversion',
    evidence_canonical_json LONGTEXT      NOT NULL COMMENT '証跡の正規化JSON',
    evidence_sha256         CHAR(64)      NOT NULL COMMENT '証跡の正規化JSONのSHA-256',
    request_sha256          CHAR(64)      NOT NULL COMMENT '要求の正規化hash',
    business_comment        VARCHAR(1000) NULL     COMMENT '提出時の業務コメント',
    created_at              DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_dual_submission_revision (tenant_id, task_id, task_generation, revision),
    UNIQUE KEY uk_dual_submission_binding (tenant_id, task_id, task_generation, revision, id, evidence_sha256),
    UNIQUE KEY uk_dual_submission_tenant_id (tenant_id, id),
    CONSTRAINT fk_dual_submission_case FOREIGN KEY (tenant_id, case_id) REFERENCES t_lifecycle_case (tenant_id, id),
    CONSTRAINT fk_dual_submission_task FOREIGN KEY (case_id, task_id) REFERENCES t_lifecycle_task (case_id, id),
    CONSTRAINT fk_dual_submission_submitter FOREIGN KEY (tenant_id, submitter_user_id) REFERENCES sys_user (tenant_id, id),
    CONSTRAINT ck_dual_submission_nonneg CHECK (task_generation >= 0 AND revision > 0
        AND global_epoch >= 0 AND submitter_actor_epoch >= 0 AND engineer_scope_epoch >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='二者確認の提出（追記専用）';

CREATE TABLE t_lifecycle_dual_submission_document (
    tenant_id                  VARCHAR(100) NOT NULL COMMENT 'テナントID（提出・文書版と同じtenant）',
    submission_id              BIGINT       NOT NULL COMMENT 't_lifecycle_dual_submission.id',
    document_id                BIGINT       NOT NULL COMMENT 't_document.id',
    document_version_id        BIGINT       NOT NULL COMMENT 't_document_version.id',
    version_no                 INT          NOT NULL COMMENT '提出時の版番号',
    sha256                     CHAR(64)     NOT NULL COMMENT '提出時の版のSHA-256',
    evidence_eligibility_epoch BIGINT       NOT NULL COMMENT '提出時の文書の証跡利用可否epoch',
    PRIMARY KEY (submission_id, document_id),
    CONSTRAINT fk_dual_doc_submission FOREIGN KEY (tenant_id, submission_id)
        REFERENCES t_lifecycle_dual_submission (tenant_id, id),
    CONSTRAINT fk_dual_doc_version FOREIGN KEY (tenant_id, document_id, document_version_id)
        REFERENCES t_document_version (tenant_id, document_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='二者確認の提出に含めた文書版（追記専用）';

CREATE TABLE t_lifecycle_dual_submission_org_fence (
    tenant_id       VARCHAR(100) NOT NULL COMMENT 'テナントID（提出と同じtenant）',
    submission_id   BIGINT       NOT NULL COMMENT 't_lifecycle_dual_submission.id',
    organization_id BIGINT       NOT NULL COMMENT 'm_organization_unit.id',
    org_scope_epoch BIGINT       NOT NULL COMMENT '提出時の組織部分木scope epoch',
    PRIMARY KEY (submission_id, organization_id),
    CONSTRAINT fk_dual_org_submission FOREIGN KEY (tenant_id, submission_id)
        REFERENCES t_lifecycle_dual_submission (tenant_id, id),
    CONSTRAINT fk_dual_org_unit FOREIGN KEY (organization_id) REFERENCES m_organization_unit (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='二者確認の提出時の組織集合（追記専用）';

CREATE TABLE t_lifecycle_dual_confirmation (
    id                      BIGINT        AUTO_INCREMENT PRIMARY KEY,
    tenant_id               VARCHAR(100)  NOT NULL COMMENT 'テナントID（提出・確認者と同じtenant）',
    case_id                 BIGINT        NOT NULL COMMENT 't_lifecycle_case.id',
    task_id                 BIGINT        NOT NULL COMMENT 't_lifecycle_task.id',
    task_generation         BIGINT        NOT NULL COMMENT '確認対象の提出のtask世代',
    submission_id           BIGINT        NOT NULL COMMENT 't_lifecycle_dual_submission.id',
    submission_revision     INT           NOT NULL COMMENT '確認対象の提出版',
    evidence_sha256         CHAR(64)      NOT NULL COMMENT '確認対象の証跡hash（ConfirmationBinding）',
    confirmer_user_id       BIGINT        NOT NULL COMMENT '確認者 sys_user.id',
    confirmer_role_snapshot VARCHAR(30)   NOT NULL COMMENT '確認時の確認者ロール',
    global_epoch            BIGINT        NOT NULL COMMENT '確認時の全体認可epoch',
    confirmer_actor_epoch   BIGINT        NOT NULL COMMENT '確認時の確認者actor epoch（snapshotとは比べない）',
    decision                VARCHAR(10)   NOT NULL COMMENT 'CONFIRMED, REJECTED',
    decision_comment        VARCHAR(1000) NULL     COMMENT '確認・差戻しのコメント',
    decided_at              DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_dual_confirmation_submission (tenant_id, submission_id),
    CONSTRAINT fk_dual_confirmation_submission
        FOREIGN KEY (tenant_id, task_id, task_generation, submission_revision, submission_id, evidence_sha256)
        REFERENCES t_lifecycle_dual_submission (tenant_id, task_id, task_generation, revision, id, evidence_sha256),
    CONSTRAINT fk_dual_confirmation_confirmer FOREIGN KEY (tenant_id, confirmer_user_id) REFERENCES sys_user (tenant_id, id),
    CONSTRAINT ck_dual_confirmation_decision CHECK (decision IN ('CONFIRMED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='二者確認の確認・差戻し（追記専用）';

CREATE TABLE t_lifecycle_dual_projection (
    tenant_id          VARCHAR(100) NOT NULL COMMENT 'テナントID',
    task_id            BIGINT       NOT NULL COMMENT 't_lifecycle_task.id（taskごとに1行）',
    case_id            BIGINT       NOT NULL COMMENT 't_lifecycle_case.id',
    task_generation    BIGINT       NOT NULL COMMENT '現在の提出のtask世代',
    revision           INT          NOT NULL COMMENT '現在の提出版',
    submission_id      BIGINT       NOT NULL COMMENT '現在の提出 t_lifecycle_dual_submission.id',
    evidence_sha256    CHAR(64)     NOT NULL COMMENT '現在の提出の証跡hash',
    state              VARCHAR(12)  NOT NULL COMMENT 'PENDING, CONFIRMED, REJECTED',
    projection_version BIGINT       NOT NULL DEFAULT 0 COMMENT 'CAS用の版',
    updated_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, task_id),
    CONSTRAINT fk_dual_projection_case FOREIGN KEY (tenant_id, case_id) REFERENCES t_lifecycle_case (tenant_id, id),
    CONSTRAINT fk_dual_projection_task FOREIGN KEY (case_id, task_id) REFERENCES t_lifecycle_task (case_id, id),
    CONSTRAINT fk_dual_projection_submission
        FOREIGN KEY (tenant_id, task_id, task_generation, revision, submission_id, evidence_sha256)
        REFERENCES t_lifecycle_dual_submission (tenant_id, task_id, task_generation, revision, id, evidence_sha256),
    CONSTRAINT ck_dual_projection_state CHECK (state IN ('PENDING', 'CONFIRMED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='二者確認のtaskごとの現在状態';

CREATE TABLE t_lifecycle_dual_idempotency (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(100) NOT NULL COMMENT 'テナントID',
    case_id         BIGINT       NOT NULL COMMENT 't_lifecycle_case.id',
    task_id         BIGINT       NOT NULL COMMENT 't_lifecycle_task.id',
    task_generation BIGINT       NOT NULL COMMENT '検索時のlock済みtask行の世代',
    operation       VARCHAR(10)  NOT NULL COMMENT 'SUBMIT, CONFIRM（差戻しはCONFIRM）',
    actor_user_id   BIGINT       NOT NULL COMMENT '要求者 sys_user.id',
    idempotency_key VARCHAR(190) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Idempotency-Key（大文字小文字を区別）',
    request_sha256  CHAR(64)     NOT NULL COMMENT '要求の正規化hash',
    submission_id   BIGINT       NOT NULL COMMENT '結果の提出 t_lifecycle_dual_submission.id',
    confirmation_id BIGINT       NULL     COMMENT '結果の確認 t_lifecycle_dual_confirmation.id（CONFIRMのみ）',
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_dual_idempotency (tenant_id, task_id, task_generation, operation, actor_user_id, idempotency_key),
    CONSTRAINT fk_dual_idem_case FOREIGN KEY (tenant_id, case_id) REFERENCES t_lifecycle_case (tenant_id, id),
    CONSTRAINT fk_dual_idem_actor FOREIGN KEY (tenant_id, actor_user_id) REFERENCES sys_user (tenant_id, id),
    CONSTRAINT ck_dual_idem_operation CHECK (operation IN ('SUBMIT', 'CONFIRM')),
    CONSTRAINT ck_dual_idem_key CHECK (CHAR_LENGTH(idempotency_key) >= 1
        AND NOT REGEXP_LIKE(idempotency_key, '[^A-Za-z0-9._~-]', 'c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='二者確認の冪等性（追記専用）';

CREATE TABLE t_lifecycle_dual_event (
    id                      BIGINT       AUTO_INCREMENT PRIMARY KEY,
    tenant_id               VARCHAR(100) NOT NULL COMMENT '主体のDB上のtenant',
    case_id                 BIGINT       NULL     COMMENT '主体のtenant内で解決できた案件ID',
    task_id                 BIGINT       NULL     COMMENT '主体のtenant内で解決できたtask ID',
    task_generation         BIGINT       NULL     COMMENT '主体のtenant内で解決できたtask世代',
    submission_id           BIGINT       NULL     COMMENT '主体のtenant内で解決できた提出ID',
    requested_task_id       BIGINT       NULL     COMMENT '要求されたtask ID（他tenantの値でもよい）',
    requested_submission_id BIGINT       NULL     COMMENT '要求された提出ID（他tenantの値でもよい）',
    actor_user_id           BIGINT       NOT NULL COMMENT '操作者 sys_user.id',
    operation               VARCHAR(10)  NOT NULL COMMENT 'SUBMIT, CONFIRM, REJECT',
    event_type              VARCHAR(20)  NOT NULL COMMENT 'SUBMITTED, SUPERSEDED, CONFIRMED, REJECTED, DENIED',
    reason_code             VARCHAR(60)  NULL     COMMENT '拒否理由（計画 §5.8）',
    http_status             SMALLINT     NULL     COMMENT '拒否時の応答status',
    attempt_count           TINYINT      NULL     COMMENT '試行回数',
    occurred_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_dual_event_task (tenant_id, task_id, occurred_at),
    CONSTRAINT ck_dual_event_type CHECK (event_type IN ('SUBMITTED', 'SUPERSEDED', 'CONFIRMED', 'REJECTED', 'DENIED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='二者確認の追記監査（FKなし。他tenantのIDでもinsertが失敗しない）';

-- 不変の6表: UPDATE・DELETEをDBでも拒否する（V132と同じ方式。訂正は新しい行の追記で表す）
CREATE TRIGGER trg_lifecycle_dual_submission_no_update BEFORE UPDATE ON t_lifecycle_dual_submission
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_lifecycle_dual_submission is append-only';

CREATE TRIGGER trg_lifecycle_dual_submission_no_delete BEFORE DELETE ON t_lifecycle_dual_submission
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_lifecycle_dual_submission is append-only';

CREATE TRIGGER trg_lifecycle_dual_submission_document_no_update BEFORE UPDATE ON t_lifecycle_dual_submission_document
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_lifecycle_dual_submission_document is append-only';

CREATE TRIGGER trg_lifecycle_dual_submission_document_no_delete BEFORE DELETE ON t_lifecycle_dual_submission_document
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_lifecycle_dual_submission_document is append-only';

CREATE TRIGGER trg_lifecycle_dual_submission_org_fence_no_update BEFORE UPDATE ON t_lifecycle_dual_submission_org_fence
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_lifecycle_dual_submission_org_fence is append-only';

CREATE TRIGGER trg_lifecycle_dual_submission_org_fence_no_delete BEFORE DELETE ON t_lifecycle_dual_submission_org_fence
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_lifecycle_dual_submission_org_fence is append-only';

CREATE TRIGGER trg_lifecycle_dual_confirmation_no_update BEFORE UPDATE ON t_lifecycle_dual_confirmation
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_lifecycle_dual_confirmation is append-only';

CREATE TRIGGER trg_lifecycle_dual_confirmation_no_delete BEFORE DELETE ON t_lifecycle_dual_confirmation
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_lifecycle_dual_confirmation is append-only';

CREATE TRIGGER trg_lifecycle_dual_idempotency_no_update BEFORE UPDATE ON t_lifecycle_dual_idempotency
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_lifecycle_dual_idempotency is append-only';

CREATE TRIGGER trg_lifecycle_dual_idempotency_no_delete BEFORE DELETE ON t_lifecycle_dual_idempotency
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_lifecycle_dual_idempotency is append-only';

CREATE TRIGGER trg_lifecycle_dual_event_no_update BEFORE UPDATE ON t_lifecycle_dual_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_lifecycle_dual_event is append-only';

CREATE TRIGGER trg_lifecycle_dual_event_no_delete BEFORE DELETE ON t_lifecycle_dual_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_lifecycle_dual_event is append-only';
