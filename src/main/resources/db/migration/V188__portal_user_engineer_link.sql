-- ============================================================
-- V188: ポータルユーザーと要員の紐付け（NF01 N1-4 / 決定 B-NF01-8・Q-10）
--
-- 退社自動処理（ResignationGateChecker）はこれまで、要員の内部アカウントのemailでポータルユーザーを照合して
-- ポータルsessionを失効させていた。同じemailを持つ無関係なポータルユーザーを誤って処理しないよう、管理者が
-- ポータル管理画面で登録した紐付けだけを自動処理の対象にする。紐付けの無いポータルユーザーは自動処理せず、
-- 退社ゲートに「要確認」として表示する。
--
-- 1ポータルユーザーの現行紐付けは1件まで（1要員に複数のポータルユーザーは可）。解除は released_at /
-- released_by を記録し、行は履歴として残す。現行の一意性は生成列 active_portal_user_id
-- （未解除・未削除の行だけ portal_user_id）のUNIQUEで保証する（NULLは一意判定の対象外）。
-- tenant_id はポータル組織・要員と同じtenantであること。登録時にserviceが検査し、
-- NF01 read-only preflight の PORTAL_ENGINEER_LINK_SCOPE_MISMATCH が不整合を監視する。
-- FKは ON UPDATE / ON DELETE とも RESTRICT とする（STORED生成列のbase列のFKにCASCADEは使えない）。
-- 登録・解除の監査は t_audit_log へ同一transactionで必須記録する（新しい監査表は作らない）。
-- 規約（決定XB-6）: 新しい表は本migrationだけで作り、V1へ書き戻さない。H2のreplayは
-- src/test/resources/sql/schema-portal-user-engineer-link-h2.sql（FK・STORED指定なし）が同じ定義を持つ。
-- ============================================================
CREATE TABLE t_portal_user_engineer_link (
    id             BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    tenant_id      VARCHAR(100) NOT NULL COMMENT 'テナントID（ポータル組織・要員と同じtenant）',
    portal_user_id BIGINT       NOT NULL COMMENT 'ポータルユーザーID（t_portal_user.id）',
    engineer_id    BIGINT       NOT NULL COMMENT '要員ID（t_engineer.id）',
    linked_by      BIGINT       NOT NULL COMMENT '紐付けを登録した管理者のユーザーID',
    linked_at      DATETIME     NOT NULL COMMENT '紐付けの登録日時',
    released_by    BIGINT       NULL COMMENT '紐付けを解除した管理者のユーザーID',
    released_at    DATETIME     NULL COMMENT '紐付けの解除日時（NULL=現行の紐付け）',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
    deleted_flag   TINYINT      NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
    active_portal_user_id BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN released_at IS NULL AND deleted_flag = 0 THEN portal_user_id ELSE NULL END
        ) STORED COMMENT '現行紐付けの一意用（未解除・未削除の行だけportal_user_id）',
    UNIQUE KEY uk_portal_user_engineer_link_active (active_portal_user_id),
    INDEX idx_portal_user_engineer_link_engineer (tenant_id, engineer_id, released_at),
    INDEX idx_portal_user_engineer_link_user (portal_user_id),
    CONSTRAINT fk_portal_user_engineer_link_user FOREIGN KEY (portal_user_id) REFERENCES t_portal_user (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_portal_user_engineer_link_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='ポータルユーザーと要員の紐付け（NF01 退社自動処理の対象特定）';
