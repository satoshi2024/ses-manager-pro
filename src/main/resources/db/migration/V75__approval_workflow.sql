-- ==========================================================
-- V75: 承認ワークフロー・内部統制 DDL
-- spec: approval-workflow-internal-control (S07 T042 / F1)
--
-- 【本スクリプトが唯一の正】
-- 本specで追加する5テーブルは全て新規テーブルであり、V1__create_tables.sql（統合baseline）へは
-- 追記しない（platform-invariants §4.3、CRM V73と同じ方針: 新規テーブルは自身のmigrationのみで
-- fresh/legacy両経路が同一スキーマへ到達する。既存の対象5業務のテーブル(t_quotation/t_contract/
-- t_invoice/t_bp_payment/m_system_config)は本specでは一切変更しない。target_versionの実値取得と
-- 対象entityへの`@Version`追加はF2（アダプタ実装）で対象ごとに決定する。
--
-- 【F1のスコープ】
-- route/request/action/delegationのDDLとengine coreのみ。5対象アダプタ(F2)はまだ登録しない。
-- 金額帯境界・時間asOf・状態機械はdesign.md §6決定表のとおり実装する（実装中に変更しない）。
-- ==========================================================

-- ============================================================
-- 1. m_approval_route (承認route定義)
--    current: active_flag=1 かつ valid_from<=today<=valid_to(NULL=無期限)。
--    history: version_no + valid_from/to。route改版は新しい行を追加する運用とし、
--    旧行はvalid_toを閉じて残す（進行中申請はroute_snapshot_jsonを使うため旧行の削除は不要）。
-- ============================================================
CREATE TABLE IF NOT EXISTS `m_approval_route` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    `tenant_id`       BIGINT NOT NULL DEFAULT 1          COMMENT 'テナントID(G0決定により当面は1固定)',
    `request_type`    VARCHAR(50) NOT NULL               COMMENT '対象種別(例: quotation.submit, contract.activate)',
    `organization_id` BIGINT NULL                        COMMENT '対象組織(NULL=全組織対象、最も非具体的)',
    `min_amount`      DECIMAL(14,0) NULL                 COMMENT '金額帯下限(inclusive、税込、NULL=下限なし)',
    `max_amount`      DECIMAL(14,0) NULL                 COMMENT '金額帯上限(inclusive、税込、NULL=上限なし)',
    `version_no`      INT NOT NULL DEFAULT 1             COMMENT 'route編集版数(同一route改版時に新しい行へ増分)',
    `valid_from`      DATE NOT NULL                      COMMENT '適用開始日(inclusive)',
    `valid_to`        DATE NULL                          COMMENT '適用終了日(inclusive、NULL=無期限)',
    `active_flag`     TINYINT NOT NULL DEFAULT 1         COMMENT '有効フラグ(0で無効化、削除しない)',
    `created_by`      BIGINT NULL                        COMMENT '作成者(sys_user.id)',
    `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
    `updated_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
    `deleted_flag`    TINYINT NOT NULL DEFAULT 0         COMMENT '論理削除フラグ',
    INDEX `idx_approval_route_lookup` (`tenant_id`, `request_type`, `active_flag`, `valid_from`, `valid_to`),
    CONSTRAINT `fk_approval_route_org` FOREIGN KEY (`organization_id`) REFERENCES `m_organization_unit`(`id`)
        ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT `fk_approval_route_created_by` FOREIGN KEY (`created_by`) REFERENCES `sys_user`(`id`)
        ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT `chk_approval_route_amount_range`
        CHECK (`min_amount` IS NULL OR `max_amount` IS NULL OR `min_amount` <= `max_amount`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='承認route定義';

-- ============================================================
-- 2. m_approval_route_step (route内のstep定義)
--    同一step_noを共有する複数行が1つの並列group(全員承認で次stepへ、1人却下で終端)を成す。
--    approver_type: 'USER'(固定user) / 'ROLE'(sys_user.roleに一致する全員。申請者は候補から除外) /
--                   'APPLICANT_MANAGER'(申請時点の申請者所属`t_user_organization.manager_user_id`)。
--    R1.3が挙げる「permission group / 組織責任者 / 財務責任者」はG7既定(組織上長→財務/管理者)を
--    満たす3種で表現可能なため、F1ではこの3種のみ実装する。他のapprover_typeは今後の拡張として
--    値だけ許容し、resolverは未対応typeを「approver解決不能」として扱う(R3.4と同じfail-closed)。
-- ============================================================
CREATE TABLE IF NOT EXISTS `m_approval_route_step` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    `route_id`       BIGINT NOT NULL                    COMMENT '所属route',
    `step_no`        INT NOT NULL                       COMMENT 'step順序(1始まり)。同値は並列group',
    `parallel_group` INT NOT NULL                       COMMENT '並列group識別子(通常はstep_noと同値)',
    `approver_type`  VARCHAR(30) NOT NULL                COMMENT 'USER / ROLE / APPLICANT_MANAGER',
    `approver_value` VARCHAR(255) NULL                   COMMENT 'USER:sys_user.id、ROLE:role名。APPLICANT_MANAGERはNULL',
    `sla_hours`      INT NULL                            COMMENT 'step開始からの期限時間(NULL=期限なし、escalation対象外)',
    `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
    `updated_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
    `deleted_flag`   TINYINT NOT NULL DEFAULT 0          COMMENT '論理削除フラグ',
    INDEX `idx_approval_route_step_route` (`route_id`, `step_no`),
    CONSTRAINT `fk_approval_route_step_route` FOREIGN KEY (`route_id`) REFERENCES `m_approval_route`(`id`)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='承認route step定義';

-- ============================================================
-- 3. t_approval_request (承認申請)
--    route_snapshot_json / payload_json / diff_json は申請時点で確定し以後不変(design §6.1)。
--    target_version: 申請時点の対象entity版のsnapshot。実値の取得・再検証方法はF2(対象adapter)が
--    対象ごとに決める。F1のengineはこの値をそのまま保存・比較なしで通過させるだけ。
--    status: draft/requested/in_review/returned/approved/rejected/withdrawn/conflict(design §6.4)。
-- ============================================================
CREATE TABLE IF NOT EXISTS `t_approval_request` (
    `id`                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    `request_no`          VARCHAR(30) NULL                   COMMENT '申請番号(採番後に確定。AR-{id}形式)',
    `request_type`        VARCHAR(50) NOT NULL               COMMENT '対象種別',
    `target_type`         VARCHAR(30) NOT NULL               COMMENT 'QUOTATION/CONTRACT/INVOICE/BP_PAYMENT/MONTHLY_CLOSING',
    `target_id`           BIGINT NULL                        COMMENT '対象ID(MONTHLY_CLOSINGはNULL、月はpayload_jsonへ)',
    `target_version`      BIGINT NULL                        COMMENT '申請時点の対象entity版snapshot(F2が意味を定義)',
    `applicant_id`        BIGINT NOT NULL                    COMMENT '申請者(sys_user.id)',
    `organization_id`     BIGINT NULL                        COMMENT '対象組織(route解決に使用したもの)',
    `amount_snapshot`     DECIMAL(14,0) NULL                 COMMENT '申請時点の金額(税込、NULL=金額なし申請)',
    `payload_json`        JSON NOT NULL                      COMMENT '申請時点の対象差分(許可fieldのみ、PII最小化)',
    `diff_json`           JSON NULL                          COMMENT '表示用before/after差分(field label付き)',
    `route_snapshot_json` JSON NOT NULL                      COMMENT '申請時点で確定したroute+step+承認者snapshot',
    `status`              VARCHAR(20) NOT NULL DEFAULT 'draft' COMMENT 'draft/requested/in_review/returned/approved/rejected/withdrawn/conflict',
    `current_step`        INT NOT NULL DEFAULT 0             COMMENT '現在のstep_no(0=未開始)',
    `requested_at`        DATETIME NULL                       COMMENT '申請確定日時',
    `finalized_at`        DATETIME NULL                       COMMENT '終端到達日時',
    `idempotency_key`     VARCHAR(100) NULL                  COMMENT '呼出側の冪等キー(再送で同一申請を返す)',
    `version`             INT NOT NULL DEFAULT 1             COMMENT '楽観ロック(current_stepと複合CASで使用)',
    `created_by`          BIGINT NULL                        COMMENT '作成者(通常はapplicant_idと同一)',
    `created_at`          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
    `updated_at`          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
    `deleted_flag`        TINYINT NOT NULL DEFAULT 0         COMMENT '論理削除フラグ',
    UNIQUE KEY `uk_approval_request_no` (`request_no`),
    UNIQUE KEY `uk_approval_request_idempotency` (`idempotency_key`),
    INDEX `idx_approval_request_applicant` (`applicant_id`, `status`),
    INDEX `idx_approval_request_target` (`target_type`, `target_id`),
    INDEX `idx_approval_request_status` (`status`, `current_step`),
    CONSTRAINT `fk_approval_request_applicant` FOREIGN KEY (`applicant_id`) REFERENCES `sys_user`(`id`)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT `fk_approval_request_org` FOREIGN KEY (`organization_id`) REFERENCES `m_organization_unit`(`id`)
        ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='承認申請';

-- ============================================================
-- 4. t_approval_action (承認アクション履歴)
--    approver_slot_user_id = COALESCE(delegated_from, approver_user_id)。
--    本人と代理の同時解決が同一slotへ二重にactionを記録しないよう、このUNIQUEで
--    「先着1件のみ有効、2件目はDB制約でCAS失敗」を構造的に保証する(design §6.4)。
-- ============================================================
CREATE TABLE IF NOT EXISTS `t_approval_action` (
    `id`                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    `request_id`            BIGINT NOT NULL                    COMMENT '対象申請',
    `step_no`                INT NOT NULL                       COMMENT '実行時点のstep_no(withdrawは申請時点のcurrent_step)',
    `approver_user_id`      BIGINT NOT NULL                    COMMENT '実際に操作したuser(代理時は代理者本人)',
    `approver_slot_user_id` BIGINT NOT NULL                    COMMENT '解決されたslotの持ち主(代理時は委任元)。二重action防止キー',
    `action`                VARCHAR(20) NOT NULL                COMMENT 'APPROVE/REJECT/RETURN/WITHDRAW',
    `comment`               TEXT NULL                          COMMENT 'コメント',
    `delegated_from`        BIGINT NULL                        COMMENT '代理実行時の委任元user(本人操作時はNULL)',
    `acted_at`              DATETIME NOT NULL                  COMMENT '操作日時',
    UNIQUE KEY `uk_approval_action_slot` (`request_id`, `step_no`, `approver_slot_user_id`),
    INDEX `idx_approval_action_request` (`request_id`, `step_no`),
    CONSTRAINT `fk_approval_action_request` FOREIGN KEY (`request_id`) REFERENCES `t_approval_request`(`id`)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT `fk_approval_action_approver` FOREIGN KEY (`approver_user_id`) REFERENCES `sys_user`(`id`)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT `fk_approval_action_delegated_from` FOREIGN KEY (`delegated_from`) REFERENCES `sys_user`(`id`)
        ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='承認アクション履歴';

-- ============================================================
-- 5. t_approval_delegation (代理設定)
--    代理は承認操作の実行時点で評価する(申請時点ではない、design §6.1)。
--    request_types_json IS NULL は全種別対象。
-- ============================================================
CREATE TABLE IF NOT EXISTS `t_approval_delegation` (
    `id`                 BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    `from_user_id`       BIGINT NOT NULL                    COMMENT '委任元(元の承認者)',
    `to_user_id`         BIGINT NOT NULL                    COMMENT '代理者',
    `valid_from`         DATE NOT NULL                      COMMENT '代理期間開始(inclusive)',
    `valid_to`           DATE NULL                          COMMENT '代理期間終了(inclusive、NULL=無期限)',
    `request_types_json` JSON NULL                          COMMENT '対象種別の限定(NULL=全種別対象)',
    `reason`             VARCHAR(500) NULL                  COMMENT '代理理由',
    `approved_by`        BIGINT NULL                        COMMENT '代理設定を承認したuser',
    `created_by`         BIGINT NULL                        COMMENT '作成者',
    `created_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
    `updated_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
    `deleted_flag`       TINYINT NOT NULL DEFAULT 0         COMMENT '論理削除フラグ',
    INDEX `idx_approval_delegation_lookup` (`from_user_id`, `valid_from`, `valid_to`),
    CONSTRAINT `fk_approval_delegation_from` FOREIGN KEY (`from_user_id`) REFERENCES `sys_user`(`id`)
        ON DELETE RESTRICT,
    CONSTRAINT `fk_approval_delegation_to` FOREIGN KEY (`to_user_id`) REFERENCES `sys_user`(`id`)
        ON DELETE RESTRICT,
    CONSTRAINT `fk_approval_delegation_approved_by` FOREIGN KEY (`approved_by`) REFERENCES `sys_user`(`id`)
        ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT `chk_approval_delegation_not_self` CHECK (`from_user_id` <> `to_user_id`),
    CONSTRAINT `chk_approval_delegation_period` CHECK (`valid_to` IS NULL OR `valid_from` <= `valid_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='承認代理設定';

-- ============================================================
-- 6. action permission (ActionPermissionResolverのRESOURCE_NAMESへ`approval`を登録した変更と対)
--    未登録rootのままだと/api/approval/**は管理者を含む全roleが403になる(CRM-R2-P1-01と同じ罠)。
--    baseline+deny方式のため、group割当済みの非管理者roleへ明示seedしないと403のままになる。
--    要員(role-worker相当)はSecurityConfigのanyRequestが4管理ロール限定のため対象外(既存仕様)。
-- ============================================================
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (SELECT 'approval.*' AS action_key) a
WHERE g.tenant_id = 'default'
  AND g.enabled = 1
  AND g.group_key IN ('role-sales', 'role-hr', 'role-manager');
