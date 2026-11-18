-- ============================================================
-- V189: NF01 細粒度認可epoch（epoch A spine / W3-10）
--
-- 計画: .kiro/roadmap/2026-11-14-snf-nf01-fine-grained-epoch-plan.md §2.2・§2.3・§2.4・§5.12・§7.1
-- 決定: B-NF01-2（epoch DDLは新migrationだけで追加）、B-NF01-6（tenant単位の粗い失効を採らない）、
--       Q-3（role menuと認可configの変化はglobal単位で失効させる）。
--
-- 1. 全体epoch表 t_authorization_global_epoch（tenantを持たない1行表。初期値は singleton_id=1, epoch=0）。
-- 2. 既存の業務行をlock anchorにする細粒度epoch列（いずれも BIGINT NOT NULL DEFAULT 0、負値はCHECKで拒否）。
--      sys_user.actor_authz_epoch                 利用者単位の認可世代
--      t_engineer.engineer_scope_epoch            要員単位のscope世代
--      m_organization_unit.org_scope_epoch        組織部分木単位のscope世代
--      t_document.evidence_eligibility_epoch      文書の証跡利用可否の世代
--      t_lifecycle_task.task_generation           タスク世代（保留・取消・担当変更・免除で旧提出を失効させる）
--    DEFAULT 0 により、要員・利用者・文書・組織・タスクを作る既存の全経路にseed義務を負わせない
--    （本番は空DBへの新規導入のため、既存行のbackfillもDEFAULTで足りる。決定P-2）。
-- 3. B0（二者確認coordinatorの表、W3-18）の複合FKが参照する一意keyの前提（計画 §5.12 冒頭）。
--      t_lifecycle_case   (tenant_id, id)              uk_lifecycle_case_tenant_id
--      t_lifecycle_task   (case_id, id)                uk_lifecycle_task_case_id
--      t_document_version (tenant_id, document_id, id) uk_document_version_tenant_doc_id
--    sys_user (tenant_id, id) は V165 の uk_sys_user_tenant_id が既に同じ列構成で存在するため追加しない
--    （同じkeyを再追加すると重複indexになる。W3-18はV165のkeyを参照する）。
--    いずれも id（主キー）を含むため重複は起こり得ない。key列は NOT NULL である。
--
-- epoch列は entity（SysUser・Engineer・OrganizationUnit・Document・LifecycleTask）に写像しない（計画 §2.4 E-7）。
-- 写像すると updateById(entity) が読取時の値を書き戻して増分を巻き戻すため、読書きは
-- AuthorizationEpochMapper の明示SQLだけで行う（AuthorizationEpochColumnIsolationTest が固定する）。
-- 増分は updated_at = updated_at で更新日時を変えず、@Version 列にも触れない（E-8・E-9）。
-- 上限（BIGINTの最大値）では増分せず、業務更新ごと409でrollbackする（E-6）。
-- triggerは追加しない（配備runbookのtrigger件数は変わらない）。
-- 規約（決定XB-6）: V1と既存migrationは変更しない。H2のreplayは
-- src/test/resources/sql/schema-authorization-epoch-h2.sql（IF NOT EXISTSで冪等）が同じ定義を持つ。
-- ============================================================
CREATE TABLE t_authorization_global_epoch (
    singleton_id TINYINT     NOT NULL PRIMARY KEY COMMENT '常に1',
    epoch        BIGINT      NOT NULL DEFAULT 0 COMMENT '全体認可世代（単調増加）',
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最後に世代を進めた日時',
    CONSTRAINT ck_authz_global_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_authz_global_epoch_nonneg CHECK (epoch >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='全体認可epoch（role menu・認可configの変化）';

INSERT INTO t_authorization_global_epoch (singleton_id, epoch) VALUES (1, 0);

ALTER TABLE sys_user
    ADD COLUMN actor_authz_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '利用者単位の認可世代（NF01 epoch A）',
    ADD CONSTRAINT ck_sys_user_actor_authz_epoch CHECK (actor_authz_epoch >= 0);

ALTER TABLE t_engineer
    ADD COLUMN engineer_scope_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '要員単位のscope世代（NF01 epoch A）',
    ADD CONSTRAINT ck_engineer_scope_epoch CHECK (engineer_scope_epoch >= 0);

ALTER TABLE m_organization_unit
    ADD COLUMN org_scope_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '組織部分木単位のscope世代（NF01 epoch A）',
    ADD CONSTRAINT ck_org_scope_epoch CHECK (org_scope_epoch >= 0);

ALTER TABLE t_document
    ADD COLUMN evidence_eligibility_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '証跡利用可否の世代（NF01 epoch A）',
    ADD CONSTRAINT ck_document_evidence_eligibility_epoch CHECK (evidence_eligibility_epoch >= 0);

ALTER TABLE t_lifecycle_task
    ADD COLUMN task_generation BIGINT NOT NULL DEFAULT 0 COMMENT 'タスク世代（旧提出の失効用。NF01 epoch A）',
    ADD CONSTRAINT ck_lifecycle_task_generation CHECK (task_generation >= 0);

ALTER TABLE t_lifecycle_case
    ADD UNIQUE KEY uk_lifecycle_case_tenant_id (tenant_id, id);

ALTER TABLE t_lifecycle_task
    ADD UNIQUE KEY uk_lifecycle_task_case_id (case_id, id);

ALTER TABLE t_document_version
    ADD UNIQUE KEY uk_document_version_tenant_doc_id (tenant_id, document_id, id);
