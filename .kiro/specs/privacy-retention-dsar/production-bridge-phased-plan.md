# NF07 production bridge 段階実施計画（W3-04 NF07-P）

状態: **PLAN（Plan Review 提出用）。この文書ではコード・migration・Spring Bean・設定を変更しない。**
作成日: 2026-11-14（Asia/Tokyo）。基準: `1be03bea`（S-NF 統合候補、branch `codex/snf-w3-nf07p-20261114`）。`com.ses.privacy` 配下のコードは `73aa7000` から無変更（main 68 file、test 24 file、Spring アノテーションなし、package 外からの参照なし）。

入力となった依頼者の決定（記録の所在は control repo `ses-claude-control-20261112-r1/reports/launch-decisions-20261113.md`、本repo外）:

| 決定 | 内容 |
|---|---|
| B-NF07-1（2026-11-13） | production bridge（W-1〜W-12）を全て接線してから本番公開する |
| B-NF07-2（2026-11-13） | privacy 管理権限は permission group の action 権限として付与する。baseline + deny に従い、既定は deny、管理者専用 group だけに開く。付与までは fail-closed |
| Q-1（2026-11-14） | 段階化する。第1期は F1（表・読み取り専用 policy source Bean・flag 既定OFF）、W-5 権限、A1 保全hold、A2 DSAR（半手動）。B1 自動処分と B2 recovery は署名後 |
| Q-8（2026-11-14） | DG-07 / D-01〜D-09 の署名前でも、値UNKNOWN・flag OFF・未知は一律拒否の形でコードをマージしてよい（tasks.md「F1〜M（DG-07完了まで実装禁止）」の停止条件を上書き。変更記録は tasks.md 同節） |
| Q-7（2026-11-14） | NF07 F1 の DDL を承認する（新規 migration だけで実装） |

本計画は D-01〜D-09、DG-07、G-*、P-* の署名・承認を代行しない。全項目は署名まで「未承認（記入禁止）」のまま（[`prod-wiring-decision-contract.md`](prod-wiring-decision-contract.md) §5）。batch ID は W3-14 だけが総括の採番済みで、他は仮名（総括が採番する）。

関連文書: [`policy-source-catalog-wiring-plan.md`](policy-source-catalog-wiring-plan.md)（N7-1 で取込）、[`status-alignment.md`](status-alignment.md)（N7-2）、[`journal-witness-grant-design.md`](journal-witness-grant-design.md)（W-6 設定契約・W-12 GRANT 設計案）。

---

## 0. 要約

- **第1期（署名前に実装・マージしてよい。全て flag OFF・値UNKNOWN・未知は拒否）**
  1. W3-14「F1 + PERM」: F1 表（`V?` 2本、暫定 V192/V193）、`privacy.retention.enabled` / `privacy.dsar.enabled`、決定ID束縛、読み取り専用 policy source Bean、説明API、W-5 権限。
  2. NF07-F2「read-only provider」と NF07-A1J「Journal 本番 adapter・設定契約」（並行可）。
  3. NF07-A1H「保全hold（開始のみ）と既存削除経路の hold 照会」。
  4. NF07-A2「DSAR（半手動）」。
- **署名後（B1/B2 は Q-1 により着手も署名後。その他は Q-8 により前倒し可）**: NF07-REG（W-2 policy 登録）、NF07-TRUST（W-3 KMS/CRL/OCSP）、NF07-REL（hold 解除 proof）、NF07-NF08-LINK、B1（W-10 処分）、B2（W-11 recovery）、M（W-13 release）。
- **外部で実施**: W-6 の Journal/Witness インスタンス（DBA/SRE）、W-12 の実 GRANT（DBA）。本計画は設定契約と GRANT 設計案だけを書く。
- **権限（W-5）**: `privacy.*` を `privacy-admin`（管理者専用 group）以外の全 group で deny、`privacy-admin` だけが許可。管理者 role の短絡許可を `privacy.*` に適用しない。group 未割当の legacy fallback は `privacy.*` を常に拒否する。

---

## 1. 前提と不変条件

| # | 不変条件 |
|---|---|
| I-1 | 法的な値（保持期間、起算trigger、処分方式、本人確認基準、DSAR期限、hold種類と権限者、unquarantine基準）は署名まで UNKNOWN。コード・migration・設定の既定値に書かない。技術上の現在値（例: `app.resume.retention-days`=30、g10 allow-list の 730日）を法的値へ昇格しない |
| I-2 | `privacy.retention.enabled` / `privacy.dsar.enabled` は既定OFF。真偽値として解釈できない値は起動失敗（既存 `FeatureSwitchProperties` と同じ扱い）。OFF の入口は 404 |
| I-3 | 未知は capability 単位で拒否する。決定ID欠落、policy UNKNOWN、provider 未登録、journal 不達、trust anchor 不明、失効状態不明は全て拒否（§5.2 の理由コード）。保全方向（hold 開始）は決定と journal が揃えば許可し、解除・処分・交付・外部送信は署名と専用 proof が揃うまで拒否する |
| I-4 | 第1期は業務データに対する削除・匿名化・restrict の writer、外部 provider の呼出、DSAR 成果物の自動交付を一切持たない（NF07 自身が作った一時 package を交付記録・期限で消すことは対象外） |
| I-5 | NF03 の日常 CRUD 等の既存業務を `POLICY_UNKNOWN` を理由に止めない（prod-wiring §4.1） |
| I-6 | 合成層（`SyntheticEnvironmentGuard`、test-only capability、`requireTestProfile()`）は変更しない。本番部品は別入口とする |
| I-7 | migration は新規だけ。番号は merged 状態で総括が採番する。V185 は NF10 が使用済み、最新は V186（[`status-alignment.md`](status-alignment.md) C-21） |
| I-8 | 各 batch は wave3 の共通規則に従う: worker は mvn を実行せず 1 commit を渡す → 総括が diff・BOM・LF・method 名の空白を確認 → Runner が test-compile → SHA に束縛した独立 Review → Runner が対象限定テスト（共通 guard を含む）→ `--no-ff` マージ |

---

## 2. W-1〜W-13 と batch の対応

| W | 内容 | batch | 期 | 第1期で作るもの | 本番で開くのに必要な署名・外部作業 |
|---|---|---|---|---|---|
| W-1 | F1 catalog/policy/hold/request の本番 DDL | W3-14 | 第1期 | §3.1 の表。disposition job 表は B1 へ移す | D-01、D-02（値）、P-SRC |
| W-2 | 正式 PolicyApprovalEnvelope の登録、`POLICY_UNKNOWN` の解除 | NF07-REG | 署名後（前倒し可） | なし | D-01、D-02、D-03（処分を含む policy は D-04 も） |
| W-3 | 本番 Trust Anchor / KMS / CRL・OCSP | W3-14（設定駆動 registry、既定は空。失効 provider は常に UNKNOWN）＋ NF07-TRUST | 第1期は空 registry だけ。実 provider は署名後 | 空 registry、常時 UNKNOWN の失効 provider | D-03 |
| W-4 | `ProductionPolicyReadonlyQueryService` の Spring 登録・API・NF03/NF08 接線 | W3-14（Bean・説明API）＋ NF07-NF08-LINK | Bean/API は第1期。NF08 接線は前倒し可。NF03 は日常業務を止めない契約（prod-wiring §4.1）なので、接続先は B1 の処分経路だけ | prod の Bean、`GET /api/privacy/policies/evaluate` | P-SRC、P-LE、P-ACT、P-API、G-ID |
| W-5 | privacy 権限の付与 | W3-14 | 第1期 | §6 | G-ID（割当規則）、D-01 |
| W-6 | 本番独立 Journal/Witness インスタンスと設定 | NF07-A1J（Journal adapter と設定契約）。Witness は B2 | code は第1期。インスタンスは外部 | adapter、起動時検査、DDL location | G-DBA、G-BKP |
| W-7 | 実 DB/file/AI provider、第三者 masking 規則 | NF07-F2 | 第1期（read-only、規則 UNKNOWN） | read-only provider、registry、redaction 枠 | D-06、D-08、AI G10、G-AUD、G-REC、G-EXP |
| W-8 | DSAR 受付・本人確認・export・交付（A2） | NF07-A2 | 第1期（半手動） | 受付〜交付記録 | D-01、D-06、D-09、G-AUD |
| W-9 | hold 開始/解除の authority 名簿、不可解除型（A1） | NF07-A1H（開始）＋ NF07-REL（解除） | 開始は第1期、解除は署名後 | hold 開始、既存削除経路の照会 | D-05、D-04、D-03（解除 proof） |
| W-10 | 処分 batch（B1）と flag の本番開放 | B1 | 署名後 | なし | D-02、D-04、D-09、全 gate |
| W-11 | unquarantine 手続、代表 restore/PITR 演習（B2） | B2 | 署名後 | なし | D-07、G-BKP（HFP-03-PROD-001〜008）、G-INC |
| W-12 | 本番最小 SQL 権限の実 GRANT | 外部（DBA） | 設計案は本 batch | [`journal-witness-grant-design.md`](journal-witness-grant-design.md) §3 | G-DBA |
| W-13 | PR・merge・deploy・Go/No-Go | M | 署名後 | なし | 全署名 + 独立 Review + 固定 SHA の全 gate |

依存の順序:

```text
W3-04 NF07-P（本計画）PASS
  └─ W3-14「F1 + PERM」（DDL lane: V?×2）
       ├─ NF07-F2（read-only provider。catalog seed がある場合は DDL lane）
       │    └─ NF07-A2（DSAR 半手動）
       └─ NF07-A1J（Journal adapter・設定契約。Journal DDL は別 location）
            └─ NF07-A1H（hold 開始・既存削除経路）
署名後: NF07-REG / NF07-TRUST / NF07-REL / NF07-NF08-LINK（前倒し可）→ B1 → B2 → M
```

---

## 3. 第1期の batch

### 3.1 W3-14「F1 + PERM」

**目的**: F1 表、flag、決定ID束縛、読み取り専用 policy source Bean、説明API、privacy 権限を、値UNKNOWN・flag OFF のままマージする。NF03/NF08 の呼出元は変更しない。

**ファイル集合（見込み）**

| 区分 | ファイル |
|---|---|
| migration（DDL lane） | `src/main/resources/db/migration/V?__nf07_privacy_f1_core.sql`（暫定 V192）、`V?__nf07_privacy_permission_seed.sql`（暫定 V193） |
| H2 と DDL lane の付随 | `src/test/resources/sql/schema-privacy-f1-h2.sql`（新規）、`src/test/resources/application-test.yml`（schema-locations に追記）、`src/test/resources/sql/permission-group-seed-h2.sql`（`privacy-admin` group と deny 行）、`src/test/java/com/ses/migration/LatestMigrationVersion.java`、`src/test/java/com/ses/config/ProductionSchemaVersionGuardTest.java`、`.kiro/specs/production-launch/deploy-runbook.md` の版数・件数行、`scripts/test-suites/mysql-shard-*.txt`（新 MySQL 試験を1回だけ登録） |
| 設定・flag | `src/main/resources/application.yml`（`privacy.*` 節だけ。`ai.*` 節は NF08-1 の所有なので触れない）、`com.ses.privacy.production.PrivacyFeatureProperties`、`PrivacyDecisionBinding`（D-01〜D-09 の決定ID）、`PrivacyProductionConfigurationValidator` |
| 機能スイッチ | `com.ses.config.feature.Feature`（`NF07_RETENTION` / `NF07_DSAR` を追加）、`FeatureSwitchProperties`（NF07 の Toggle は `privacy.*` の値を返す）、`.kiro/specs/production-launch/feature-switches.md` |
| entity / mapper | `com.ses.entity.privacy.PiiDataElement`、`RetentionPolicy`、`com.ses.mapper.privacy.PiiDataElementMapper`、`RetentionPolicyMapper`（`@MapperScan("com.ses.mapper")` は subpackage を含む）。他の F1 表の entity は利用する batch で追加する |
| foundation（package-private の追加） | `ProductionPolicyCatalogPort`、`CurrentPolicySource`、`ProductionPolicyTrustRegistry`、`ProductionPolicyReadonlyQueryService`、record `PolicyLookupKey` / `ProductionCatalogBinding` / `CurrentPolicySnapshot`（[`policy-source-catalog-wiring-plan.md`](policy-source-catalog-wiring-plan.md) §2 の契約）、公開 factory `ProductionPrivacyComponents`（生成前に `ProductionEnvironmentGuard` を通す）。`SecurityContextCallerContextProvider`（§6.4 の判定変更）。`PolicyFailureReason` に `SOURCE_DISABLED`（`POLICY_UNKNOWN` 群）を追加する案 |
| Spring 配線（foundation 外） | `com.ses.privacy.production.PrivacyProductionConfiguration`（prod は production service、非 prod は常に `POLICY_UNKNOWN` を返す `UnavailablePolicyReadonlyQueryPort`）、`DbProductionPolicyCatalog`、`DbCurrentPolicySource`、`ConfiguredPolicyTrustRegistry`（既定は空）、`UnknownRevocationStatusProvider`（常に UNKNOWN） |
| API | `com.ses.controller.api.PrivacyPolicyApiController`（`GET /api/privacy/policies/evaluate` だけ）、`com.ses.privacy.production.PrivacyAccessGuard`（service 層の再認可。`AuthorizationService` が取れなければ拒否） |
| 権限 | `AuthorizationServiceImpl`、`ActionPermissionResolver`、`PermissionGroupManagementServiceImpl`（割当 guard）、`BreakGlassServiceImpl`（`privacy.` 拒否）、`SecurityConfig`（`/privacy/**`・`/api/privacy/**` を `hasRole("管理者")`） |
| messages | `messages{,_en,_ko,_zh_CN}.properties`（例: `error.privacy.disabled`、`error.privacy.decisionUnknown`、`error.permission.adminOnlyGroup`、`error.permission.privacyGroupConflict`。各言語で翻訳し、日本語を複写しない） |

foundation package 内には Spring アノテーションを置かない（`PrivacyFoundationIsolationTest` を拡張して固定する）。Spring 登録は `com.ses.privacy.production` に置き、foundation 側は `ProductionPrivacyComponents` だけを public にする。

**DDL 草案（番号なし。`V?__nf07_privacy_f1_core.sql`）**

```sql
-- NF07 F1: catalog / policy / hold projection / DSAR request。法的な値は一切seedしない。
-- 処分 job（t_disposition_job / t_disposition_item）は署名後の B1 で別 migration とする。

CREATE TABLE m_pii_data_element (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        VARCHAR(100) NOT NULL DEFAULT 'default',
  data_element_id  VARCHAR(128) NOT NULL COMMENT '固定ID（例: AI_PROMPT_LOG）。pii-inventory.md の行と対応',
  storage_kind     VARCHAR(20)  NOT NULL COMMENT 'DB_COLUMN/FILE_OBJECT/AI_PAYLOAD/LOG_OR_BACKUP',
  storage_ref      VARCHAR(255) NOT NULL COMMENT 'table.column / provider.object-kind / payload.field',
  data_class       VARCHAR(50)  NOT NULL DEFAULT 'UNKNOWN',
  owner_state      VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
  owner_ref        VARCHAR(100) NULL,
  provider_id      VARCHAR(100) NULL COMMENT 'F2 の provider。NULL=未登録（検索・処分の対象外として報告する）',
  third_party_flag TINYINT      NOT NULL DEFAULT 1 COMMENT '第三者情報を含み得る（既定は含む扱い）',
  inventory_status VARCHAR(30)  NOT NULL DEFAULT 'UNKNOWN' COMMENT 'CONFIRMED_TECHNICAL/PROVISIONAL/UNKNOWN。法的承認ではない',
  inventory_ref    VARCHAR(255) NULL,
  created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag     TINYINT      NOT NULL DEFAULT 0,
  CONSTRAINT uk_pii_data_element UNIQUE (tenant_id, data_element_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF07 PII data element catalog（構造のみ）';

-- policy の正本候補。値は署名済み envelope の canonical bytes にだけ入る。
-- current pointer は生成列 + UNIQUE で (tenant, module, 法人, data element, action, provider) ごとに高々1件にする。
CREATE TABLE m_retention_policy (
  id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id               VARCHAR(100) NOT NULL,
  policy_id               VARCHAR(128) NOT NULL,
  policy_revision         VARCHAR(64)  NOT NULL,
  module_code             VARCHAR(50)  NOT NULL,
  legal_entity_id         BIGINT       NULL COMMENT 'NULL=tenant-wide。P-LE 未決のため法人単位の主張はしない',
  data_element_id         VARCHAR(128) NOT NULL,
  target_action           VARCHAR(50)  NOT NULL,
  target_provider         VARCHAR(100) NOT NULL,
  status                  VARCHAR(20)  NOT NULL DEFAULT 'CANDIDATE' COMMENT 'CANDIDATE/CURRENT/SUPERSEDED/REVOKED',
  current_key             VARCHAR(512) GENERATED ALWAYS AS (
                            CASE WHEN status = 'CURRENT'
                                 THEN CONCAT_WS('|', tenant_id, module_code, IFNULL(legal_entity_id, 0),
                                                data_element_id, target_action, target_provider)
                                 ELSE NULL END) STORED,
  envelope_schema_version INT          NOT NULL,
  envelope_canonical      LONGBLOB     NOT NULL COMMENT '署名対象の canonical bytes',
  envelope_signature      VARBINARY(256) NOT NULL,
  envelope_digest         CHAR(64)     NOT NULL,
  authority_id            VARCHAR(100) NOT NULL,
  key_id                  VARCHAR(100) NOT NULL,
  registered_by           BIGINT       NOT NULL,
  registered_at           DATETIME(6)  NOT NULL,
  activated_by            BIGINT       NULL,
  activated_at            DATETIME(6)  NULL,
  row_version             BIGINT       NOT NULL DEFAULT 0,
  CONSTRAINT uk_retention_policy_revision UNIQUE (tenant_id, policy_id, policy_revision),
  CONSTRAINT uk_retention_policy_current UNIQUE (current_key),
  CONSTRAINT chk_retention_policy_activation CHECK (status <> 'CURRENT' OR (activated_by IS NOT NULL AND activated_at IS NOT NULL AND activated_by <> registered_by))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='NF07 retention policy（署名envelope + current pointer）';

-- hold は独立 Journal が正本で、本表は projection。理由本文は保存せず、コード・hash・ticket 参照だけを持つ。
CREATE TABLE t_legal_hold (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id           VARCHAR(100) NOT NULL,
  hold_id             VARCHAR(64)  NOT NULL COMMENT 'Journal 上の hold 識別子',
  target_type         VARCHAR(20)  NOT NULL COMMENT 'SUBJECT/DATA_ELEMENT',
  subject_ref         VARCHAR(128) NULL COMMENT '種別付き opaque 参照（例: ENGINEER:123）',
  data_element_id     VARCHAR(128) NULL,
  hold_kind           VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN' COMMENT 'D-05 で確定するまで UNKNOWN',
  status              VARCHAR(20)  NOT NULL COMMENT 'ACTIVE/RELEASED',
  reason_code         VARCHAR(50)  NOT NULL,
  reason_hash         CHAR(64)     NOT NULL,
  ticket_ref          VARCHAR(100) NOT NULL,
  requested_by        BIGINT       NOT NULL,
  requested_at        DATETIME(6)  NOT NULL,
  journal_revision    BIGINT       NOT NULL,
  journal_event_hash  CHAR(64)     NOT NULL,
  released_at         DATETIME(6)  NULL,
  CONSTRAINT uk_legal_hold UNIQUE (tenant_id, hold_id),
  CONSTRAINT chk_legal_hold_target CHECK ((target_type = 'SUBJECT' AND subject_ref IS NOT NULL AND data_element_id IS NULL)
                                       OR (target_type = 'DATA_ELEMENT' AND data_element_id IS NOT NULL AND subject_ref IS NULL)),
  INDEX idx_legal_hold_subject (tenant_id, subject_ref, status),
  INDEX idx_legal_hold_element (tenant_id, data_element_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='NF07 hold projection（正本は独立Journal）';

-- 行が1件でもあれば「hold 機構が使われた」とみなし、既存削除 batch は Journal との追随確認を必須にする（§3.4）。
-- 最初の Journal append の前に行を作り、append 中の command を pending_command_id に記録する。
CREATE TABLE t_privacy_projection_state (
  tenant_id            VARCHAR(100) NOT NULL,
  journal_id           VARCHAR(64)  NOT NULL,
  engaged_at           DATETIME(6)  NOT NULL,
  last_applied_seq     BIGINT       NOT NULL DEFAULT 0,
  last_applied_hash    CHAR(64)     NULL,
  pending_command_id   VARCHAR(64)  NULL COMMENT 'Journal append 前に記録し、projection 完了で NULL に戻す',
  verified_at          DATETIME(6)  NULL,
  PRIMARY KEY (tenant_id, journal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='hold projection の追随位置（A1H の batch 単位 gate が参照）';

-- DSAR。依頼者の連絡先・本人確認書類は保存せず、外部 ticket / 証跡の参照IDだけを持つ（第1期）。
CREATE TABLE t_privacy_request (
  id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id              VARCHAR(100) NOT NULL,
  request_no             VARCHAR(40)  NOT NULL,
  request_type           VARCHAR(20)  NOT NULL COMMENT 'ACCESS/CORRECTION/DELETION/RESTRICTION/OBJECTION',
  status                 VARCHAR(30)  NOT NULL COMMENT 'PrivacyCaseState の部分集合',
  channel                VARCHAR(20)  NOT NULL,
  received_at            DATETIME(6)  NOT NULL,
  due_date               DATE         NULL COMMENT 'D-06 の期限規則で計算。規則未設定なら NULL',
  intake_ticket_ref      VARCHAR(128) NOT NULL,
  identity_state         VARCHAR(20)  NOT NULL DEFAULT 'UNVERIFIED' COMMENT 'UNVERIFIED/AMBIGUOUS/VERIFIED',
  identity_evidence_ref  VARCHAR(128) NULL,
  identity_rule_ref      VARCHAR(128) NULL COMMENT '適用した D-06 本人確認基準の決定ID',
  identity_verified_by   BIGINT       NULL,
  identity_verified_at   DATETIME(6)  NULL,
  case_owner_id          BIGINT       NULL,
  scope_snapshot_hash    CHAR(64)     NULL,
  prepared_by            BIGINT       NULL,
  approved_by            BIGINT       NULL,
  approved_at            DATETIME(6)  NULL,
  delivered_by           BIGINT       NULL,
  delivered_at           DATETIME(6)  NULL,
  delivery_method        VARCHAR(20)  NULL,
  delivery_evidence_ref  VARCHAR(128) NULL,
  reopen_of_request_id   BIGINT       NULL,
  row_version            BIGINT       NOT NULL DEFAULT 0,
  created_by             BIGINT       NOT NULL,
  created_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag           TINYINT      NOT NULL DEFAULT 0,
  CONSTRAINT uk_privacy_request_no UNIQUE (tenant_id, request_no),
  CONSTRAINT chk_privacy_request_sod CHECK (approved_by IS NULL OR prepared_by IS NULL OR approved_by <> prepared_by),
  INDEX idx_privacy_request_status (tenant_id, status, due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF07 DSAR case';

CREATE TABLE t_privacy_request_subject_link (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id         VARCHAR(100) NOT NULL,
  request_id        BIGINT       NOT NULL,
  subject_ref       VARCHAR(128) NOT NULL,
  link_state        VARCHAR(20)  NOT NULL COMMENT 'CANDIDATE/SELECTED/REJECTED',
  resolved_by       BIGINT       NULL,
  resolved_at       DATETIME(6)  NULL,
  resolution_code   VARCHAR(50)  NULL,
  CONSTRAINT uk_privacy_subject_link UNIQUE (tenant_id, request_id, subject_ref),
  CONSTRAINT fk_privacy_subject_link_request FOREIGN KEY (request_id) REFERENCES t_privacy_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='DSAR と本人の明示 link（自動結合しない）';

CREATE TABLE t_privacy_action (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id         VARCHAR(100) NOT NULL,
  request_id        BIGINT       NOT NULL,
  action_type       VARCHAR(20)  NOT NULL COMMENT 'SEARCH/EXPORT_DRAFT/MANUAL_ACTION',
  provider_id       VARCHAR(100) NULL,
  data_element_id   VARCHAR(128) NULL,
  status            VARCHAR(20)  NOT NULL COMMENT 'PENDING/CLAIMED/SUCCEEDED/BLOCKED/UNKNOWN/FAILED',
  idempotency_key   VARCHAR(128) NOT NULL,
  claim_token       VARCHAR(64)  NULL,
  lease_until       DATETIME(6)  NULL,
  result_count      INT          NULL,
  result_hash       CHAR(64)     NULL,
  blocked_reason    VARCHAR(50)  NULL,
  evidence_ref      VARCHAR(128) NULL,
  row_version       BIGINT       NOT NULL DEFAULT 0,
  created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uk_privacy_action_idem UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT fk_privacy_action_request FOREIGN KEY (request_id) REFERENCES t_privacy_request(id),
  INDEX idx_privacy_action_request (tenant_id, request_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='DSAR の検索・草案・手動対応の記録';

CREATE TABLE t_privacy_export_package (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id       VARCHAR(100) NOT NULL,
  request_id      BIGINT       NOT NULL,
  stored_name     VARCHAR(255) NOT NULL COMMENT 'FileStorageService の保存名（quarantine→published）',
  content_sha256  CHAR(64)     NOT NULL,
  size_bytes      BIGINT       NOT NULL,
  status          VARCHAR(20)  NOT NULL COMMENT 'DRAFT/APPROVED/DELIVERED/PURGED',
  purge_after     DATETIME(6)  NOT NULL,
  created_by      BIGINT       NOT NULL,
  created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  purged_at       DATETIME(6)  NULL,
  CONSTRAINT uk_privacy_export_package UNIQUE (tenant_id, stored_name),
  CONSTRAINT fk_privacy_export_request FOREIGN KEY (request_id) REFERENCES t_privacy_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='DSAR 開示 package（一時保存）';
```

H2 では生成列と CHECK の構文が異なるため、`schema-privacy-f1-h2.sql` を別に持つ（CLAUDE.md の「MySQL 専用 migration には H2 変種を置く」に従う）。H2 変種の差分は MySQL 試験 `FlywayNf07PrivacyF1MigrationTest` が実 MySQL で補う。

**DDL 草案（番号なし。`V?__nf07_privacy_permission_seed.sql`）** は §6.3 に置く。

**依存**: W3-04（本計画）の PASS、DDL lane の割当（総括）、`ActionPermissionMatrixTest` の所有（wave3 §2 で NF07-PERM に割当済み）、`application.yml` は `privacy.*` 節だけを変更する。

**対象限定テスト**

| 種別 | test class | 確認すること |
|---|---|---|
| Fast | `ActionPermissionMatrixTest` | §6.5 の変更。既存 assertion は変更しない。`BUSINESS_ACTIONS` は編集しない |
| Fast | `PermissionGroupManagementServiceImplTest` | `privacy-admin` を管理者 role 以外へ割り当てると 400、`privacy.*` deny を持つ group との同時割当は 409、既存の session 失効・監査は不変 |
| Fast | `BreakGlassServiceImplTest` | `allowed_actions` に `privacy.` 接頭辞を含む申請は 400 |
| Fast | `MenuPermissionFilterTest` | `/api/privacy/**` は `privacy-admin` 未所属の管理者でも 403 JSON |
| Fast | `FeatureSwitchFilterTest`、`FeatureSwitchRouteCoverageTest` | NF07 の入口が OFF で 404、NF 外の入口を誤遮断しない |
| Fast | `PrivacyFeaturePropertiesTest`（新規） | 既定 OFF、`ture` 等で bind 失敗 |
| Fast | `PrivacyDecisionBindingTest`（新規） | placeholder 断片・重複ID・未来の署名日を拒否。規則は NF08 の `AiExternalSendApproval` と一致（乖離を検出する比較 test を置く） |
| Fast | `PrivacyProductionConfigurationValidatorTest`（新規） | prod で flag ON かつ D-01 / D-09 の決定ID欠落なら起動失敗（固定文言、値は出さない）。flag OFF では決定ID不要 |
| Fast | `ProductionPolicyReadonlyQueryServiceTest`（新規） | policy-source-catalog-wiring-plan.md §4 の (a)〜(e)。flag OFF では source の読取 0 回で `POLICY_UNKNOWN`/`SOURCE_DISABLED` |
| Fast（H2） | `DbCurrentPolicySourceTest`（新規） | 0件は `NOT_FOUND`、CANDIDATE/SUPERSEDED/REVOKED は current として返さない、読取は1文の一貫 snapshot |
| Fast | `SecurityContextCallerContextProviderTest` | §6.4 の変更後、`privacy-admin` 所属の管理者だけが通る。GrantedAuthority 文字列だけでは通らない |
| Fast | `PrivacyFoundationIsolationTest` | foundation 内に Spring アノテーションなし。package-private 部品は `ProductionPrivacyComponents` 経由でしか生成できない |
| Fast | `PrivacyPolicyApiControllerTest`（新規、`@WebMvcTest`） | service 層の再認可で 403、`ApiResult` 形式 |
| 共通 guard | `MigrationScriptIntegrityTest`、`MessageBundleConsistencyTest`、`ProductionSchemaVersionGuardTest`、`MySqlTestShardInventoryTest` | 版番号重複なし、4言語の key 一致、最新版数、shard 登録が1回 |
| MySQL（`@Tag("mysql")`） | `FlywayNf07PrivacyF1MigrationTest`（新規） | 空DBから最新まで適用、F1 表と collation、`current_key` の2件目 CURRENT が Duplicate、`chk_retention_policy_activation`、`privacy-admin` 以外の全 group に `privacy.*` deny、`privacy-admin` に `*` と `privacy.*`（deny 0）、menu `privacy` は管理者だけ |

**完了条件**

1. 上記テストが固定 SHA で 0 failure / 0 error / 0 skip（Runner 実行）。
2. 既定構成（flag OFF）で `/privacy/**`・`/api/privacy/**` が 404 になり、policy source の DB 読取が 0 回。
3. 既存 5 role の業務 action の判定が変わらない（`ActionPermissionMatrixTest` の既存 assertion が無変更で通る）。
4. 保持期間・処分方式などの法的な値が migration・設定既定値に1件も無い（Review で確認）。
5. `com.ses.privacy.foundation` の合成層の test（`PrivacyJournalLinearizationMySqlTest` 等）に回帰がない（Runner が既存 shard で確認）。

**Demo**

1. 既定（flag OFF）で起動し、管理者で `GET /api/privacy/policies/evaluate?moduleCode=NF08_AI_SUMMARY&dataElementId=AI_PROMPT_LOG&targetAction=EXTERNAL_DISPATCH&targetProvider=gemini-adapter` を呼ぶ → 404。サイドバーに「プライバシー管理」が出ない。
2. dev で `PRIVACY_RETENTION_ENABLED=true` として再起動し、`role-admin` だけの管理者で同じ API を呼ぶ → 403（`privacy.*` deny）。
3. 別の管理者が `PUT /api/permission-groups/users/{id}` で対象管理者を `privacy-admin` だけに割り当てる → 対象者の session が失効する → 再ログインして同じ API を呼ぶ → 200、`decisionStatus=POLICY_UNKNOWN`。
4. 営業ユーザーに `privacy-admin` を割り当てようとする → 400。`role-admin` と `privacy-admin` の同時割当 → 409。
5. 起動失敗の確認は `PrivacyProductionConfigurationValidatorTest` の結果で示す（prod 相当の設定、flag ON、決定ID欠落）。

**規模**: L（main 約22 file、test 約14 class、migration 2本 + H2 1本、messages 4本）。

### 3.2 NF07-F2「read-only provider」

**目的**: DSAR 検索と dry-run の母集団を作る本番の read-only provider と registry、第三者 redaction 規則の枠を用意する。規則・範囲の値は UNKNOWN のまま。外部 provider（Gemini、CloudSign、freee、mailbox、backup/replica）は登録しない＝scope 外として常に呼ばない。

**ファイル集合（見込み）**

- `com.ses.privacy.production.ProductionPrivacyProviderRegistry`（未登録 provider は `PROVIDER_UNREGISTERED`。合成 `PrivacyProviderRegistry` は変更しない）
- `com.ses.privacy.production.provider.*`: 初期候補 `EngineerProfileProvider`（`t_engineer` 系）、`CandidateProvider`（`t_candidate` 系）、`SysUserProvider`、`DocumentFileProvider`（`t_document` / `t_document_version` / `t_file_security_metadata`、`FileReferenceProvider` と `FileScopeValidationService` を経由）、`IngestionTextProvider`（`t_resume_ingestion` / `t_bp_availability_ingestion` / `t_project_ingestion` の `extracted_text`）、`AiLogProvider`（`t_ai_recommendation_run`、legacy `t_ai_log`）。初期集合は Plan Review で確定する
- `com.ses.privacy.production.redaction.ThirdPartyRedactor`（規則は D-06 の決定ID付き設定から読む。未設定なら全 field を「要手動確認」に回し、自動では出さない）
- `com.ses.privacy.production.PrivacyCoverageReport`（登録済み／未登録の data element と provider の一覧。`m_pii_data_element.provider_id IS NULL` を必ず報告する）
- `PrivacyCatalogApiController`（`GET /api/privacy/catalog`、`privacy.catalog.view`）
- migration（DDL lane）: `V?__nf07_privacy_data_element_catalog_seed.sql`（初期 provider が扱う data element の構造行だけを seed。owner・保持などは UNKNOWN）

**DDL 草案（番号なし）**

```sql
-- data element の構造行だけ。inventory_status は UNKNOWN、owner も UNKNOWN。保持期間は持たない。
INSERT INTO m_pii_data_element (tenant_id, data_element_id, storage_kind, storage_ref, provider_id, inventory_ref)
SELECT t.tenant_id, d.data_element_id, d.storage_kind, d.storage_ref, d.provider_id, d.inventory_ref
FROM (SELECT DISTINCT tenant_id FROM m_permission_group WHERE group_key = 'role-admin') t
JOIN (
  SELECT 'ENGINEER_PROFILE' AS data_element_id, 'DB_COLUMN' AS storage_kind, 't_engineer.*' AS storage_ref,
         'engineer-profile' AS provider_id, 'pii-inventory.md#t_engineer' AS inventory_ref
  -- UNION ALL ...（初期 provider の対象だけ。Plan Review で確定）
) d
WHERE NOT EXISTS (SELECT 1 FROM m_pii_data_element e WHERE e.tenant_id = t.tenant_id AND e.data_element_id = d.data_element_id);
```

**依存**: W3-14。DDL lane（seed がある場合）。`FileReferenceProvider` / `FileScopeValidationService` の既存規約。

**対象限定テスト**: `ProductionPrivacyProviderRegistryTest`（未登録・重複・外部 provider 登録の拒否）、provider ごとの H2 test（scope 外の行を返さない、他 tenant の行を返さない、`DataScopeService` と同じ母集団、write 0）、`ThirdPartyRedactorTest`（規則未設定で自動出力 0、規則ありでも第三者 field を出さない）、`PrivacyCoverageReportTest`、`PrivacyCatalogApiControllerTest`、共通 guard（`MigrationScriptIntegrityTest`、`MessageBundleConsistencyTest`）。MySQL では seed の重複投入なしを `FlywayNf07PrivacyF1MigrationTest` に追加する。

**完了条件**: provider の呼出が全て read-only（mapper の select だけ、write 0 を構造 test で確認）。外部 provider への接続コードが無い。未登録の data element が coverage report に必ず出る。

**Demo**: privacy-admin で `GET /api/privacy/catalog` → 登録済み provider と未登録 data element（例: audit log、backup）が分かれて表示される。未登録側の理由が `PROVIDER_UNREGISTERED`。

**規模**: L（provider 6 前後、test 10 class 前後）。

### 3.3 NF07-A1J「Journal 本番 adapter と設定契約」

**目的**: hold の正本となる独立 Journal への本番接続を作る（W-6 の code 側）。合成 `JdbcPrivacyJournalAdapter` の `requireTestProfile()` は変更しない。Witness / attestor は B2 で扱う。

**ファイル集合（見込み）**

- `com.ses.privacy.production.journal.ProductionPrivacyJournalAdapter`（合成 adapter と同じ CAS・hash chain・command replay の規則。共通部分は foundation の package-private helper へ抽出し、合成側の挙動は変えない）
- `PrivacyJournalDataSourceConfiguration`（`privacy.journal.datasource.*`。journal 設定がある場合だけ DataSource を作る。flag とは独立にし、flag を OFF にしても既存 hold の効力確認（§3.4 の gate）ができるようにする。hold 開始の可否は flag と決定IDで別に判定する）
- `PrivacyJournalStartupVerifier`（[`journal-witness-grant-design.md`](journal-witness-grant-design.md) §2.3 の起動時検査）
- Journal DDL: `src/main/resources/db/privacy-journal/V?__privacy_journal_core.sql`（application DB の `db/migration` とは別 location・別履歴表。test 用 `src/test/resources/privacy-journal/schema-test.sql` を基にする）
- `PrivacyJournalMigrationIntegrityTest`（`MigrationScriptIntegrityTest` と同型。別 location の版番号重複・空 script を検出）
- deploy 手順: `.kiro/specs/production-launch/deploy-runbook.md` に Journal 用 Flyway job の節を追加

**DDL 草案（番号なし。Journal インスタンス側）**: 表の形は合成の `sf_journal_head` / `sf_journal_event` / `sf_journal_command` / `sf_journal_action` / `sf_journal_lease` を本番名（`pj_head` / `pj_event` / `pj_command` / `pj_action` / `pj_lease`）へ移したものとし、列・制約・collation（identity 列は binary NO PAD）は合成 schema と同一にする。差分は名前だけで、意味の変更は Plan Review を要する。

**依存**: W3-14（flag、決定ID束縛）。G-DBA のインスタンスは開放時に必要で、実装には不要（Testcontainers で検証）。

**対象限定テスト**: `ProductionPrivacyJournalAdapterMySqlTest`（`@Tag("mysql")`、3 container: app DB / journal / 予備。append・CAS・replay・ACK 不明・transaction 中断で head 不変、`HOLD_RELEASED` は本番 adapter でも拒否）、`PrivacyJournalStartupVerifierMySqlTest`（`innodb_flush_log_at_trx_commit`≠1、`sync_binlog`≠1、binlog 無効、read_only、app DB と同じ `server_uuid`、過剰権限の principal をそれぞれ与えて起動拒否）、`PrivacyJournalDataSourceConfigurationTest`（journal 設定が無ければ DataSource 生成 0・接続試行 0、flag OFF でも設定があれば reader 接続を作る）、`PrivacyJournalMigrationIntegrityTest`、既存 `PrivacyJournalLinearizationMySqlTest` の回帰、`MySqlTestShardInventoryTest`。

**完了条件**: journal 設定が無ければ接続試行 0。Journal 設定が app DB を指すと起動失敗。合成 adapter と本番 adapter の hash chain が同一入力で一致（同値性 test）。

**Demo**: Testcontainers の journal に対し、本番 adapter で HOLD_STARTED を append → 同じ command ID の再送が元の ACK を返す → `innodb_flush_log_at_trx_commit=2` の container では起動検査が失敗する、を test 結果で示す。

**規模**: L（main 6 前後、MySQL test 3 class）。

### 3.4 NF07-A1H「保全 hold（開始のみ）と既存削除経路の hold 照会」

**目的**: privacy-admin が subject / data element に hold を掛けられるようにし、既存の自動削除経路が hold を尊重するようにする。解除は第1期に作らない（解除は D-05 と D-03 を前提とする NF07-REL。誤った hold は保持期間を延ばすだけで、データを失わせない方向に倒れる）。

**ファイル集合（見込み）**

- `com.ses.privacy.production.hold.PrivacyHoldService`（開始: 決定 D-05 の ID が束縛されていること、journal が使えること、`privacy.hold.manage` を確認 → `t_privacy_projection_state` に `pending_command_id` を記録（行が無ければ作る）→ Journal に HOLD_STARTED を append → ACK 後に `t_legal_hold` へ projection し `pending_command_id` を NULL に戻す。監査は `recordRequired`、失敗なら操作失敗。先に書く pending 記録は成功応答ではなく、gate を fail-closed に倒すための印である（SF-04a の「local DB 更新を先に成功応答しない」に反しない））
- `PrivacyHoldGate`（削除 batch の1回ごとの gate。**判定は flag に依存させない**: flag を OFF に戻しても、既に掛けた hold は守る）
  - `t_privacy_projection_state` に当該 tenant の行が無い（hold 機構を一度も使っていない）→ 「hold なし」。追加の照会はこの1回だけで、既存挙動は変わらない。
  - 行がある → Journal の head と `last_applied_seq` / `last_applied_hash` が一致し、`pending_command_id` が NULL であることを確認してから hold 集合を返す。一致しない・pending が残る・Journal 不達・journal 設定が無い場合は `UNAVAILABLE` を返し、呼出側はその回の削除を全て skip する（ACK 後に projection が失敗した hold を取りこぼさないため）。hold 機構を使った後で journal 設定が無くなっている場合は、起動時に警告を出す（削除 batch が全て止まるため）。
- `PrivacyHoldApiController`（`GET /api/privacy/holds`、`POST /api/privacy/holds`）、`templates/privacy/holds.html`、`static/js/modules/privacy-holds.js`、`templates/layout/sidebar.html`（`privacy` menu、表示条件は feature ON かつ `privacy.view` 許可）
- 既存削除経路の接続（初期候補。Plan Review で確定）: `ResumeRetentionCleanupServiceImpl`、`DocumentRetentionJob`、`DocumentArchiveFileCleanupScheduler`、`AiRecommendationRetentionScheduler`、`IntegrationHubRetentionPurgeScheduler`、`ExternalApiReadSnapshotPurgeScheduler`。対象外とする理由を付けて記録するもの: `FileCleanupScheduler`（参照元の無い孤立 file が対象で subject を持たない）、`PwaClientMutationCleanupScheduler`・`ImportStageReconcileScheduler`（一時的な技術データ）
- subject を判定できない行の扱い: 有効な SUBJECT hold が1件以上あれば、subject を決められない行は削除せず件数を警告ログ・通知へ出す。有効 hold が0件なら既存挙動どおり削除する

**DDL**: 無し（W3-14 の `t_legal_hold` / `t_privacy_projection_state` を使う）。

**依存**: NF07-A1J、W3-14。既存 scheduler に触れるため、NF10 の scheduler 方針（test profile では scheduler 無効、対象 test が経路を直接呼ぶ）に従い、`SchedulingDisabledDrainTest`（NF05-C 所有）は変更しない。

**対象限定テスト**: `PrivacyHoldServiceTest`（D-05 決定ID欠落で `DECISION_UNKNOWN`、journal 不達で 503、ACK 前に projection を書かない、監査失敗で操作失敗）、`PrivacyHoldServiceMySqlTest`（`@Tag("mysql")`、ACK 不明時の再送で二重 hold を作らない）、`PrivacyHoldGateTest`（未使用なら照会1回で「hold なし」、追随遅れ・pending 残り・不達・journal 設定なしで `UNAVAILABLE`、hold を掛けた後に flag を OFF にしても hold を守る）、接続した各 scheduler の test（hold ありで対象 subject を消さない、`UNAVAILABLE` でその回を全 skip、hold なしで既存挙動と同じ件数を消す）、`PrivacyHoldApiControllerTest`、`ActionPermissionMatrixTest`（`privacy.hold.*`）、`FeatureSwitchRouteCoverageTest`、`StaticAssetLocalityTest`、`MessageBundleConsistencyTest`。

**完了条件**: hold 機構を一度も使っていない間は、既存 scheduler の挙動が変わらない（増える SQL は gate の照会1回だけ）。hold 開始は Journal の ACK を先に取る。flag の ON/OFF で既存 hold の効力が変わらない。解除の endpoint が存在しない（`POST /api/privacy/holds/{id}/release` は 404）。

**Demo**: (1) dev で retention flag ON、D-05 の試験用決定IDを設定し、application DB とは別の MySQL instance を dev 用 journal として設定 → privacy-admin が `ENGINEER:<id>` に hold を開始 → 一覧に ACTIVE で出る。(2) 対象者に紐づく取込履歴が保持日数を過ぎている状態で `ResumeRetentionCleanupServiceImpl` を手動実行 → 対象者の `extracted_text` は残り、他者の分は消える。(3) journal を停止して再実行 → その回は1件も消さず警告が出る。(4) journal を戻し、retention flag だけを OFF（journal 設定は残す）にして再起動・再実行 → 対象者の分は引き続き残り、他者の分は消える。

**規模**: L（main 10 前後、scheduler 6 か所の改修、test 12 class 前後、画面1）。

### 3.5 NF07-A2「DSAR（半手動）」

**目的**: DSAR の受付から交付記録までを system で追跡する。本人確認・名寄せ・交付は人が行い、system は記録・期限管理・read-only 検索・redaction 済み草案の作成・二者承認を受け持つ。第1期で自動処理するのは開示請求（ACCESS）の草案作成までで、訂正・削除・利用停止・異議は受付・期限管理・手動対応の記録だけを行う（system は処分を実行しない）。

**状態遷移**（`PrivacyCaseState` の部分集合を DB 値に使う）

| 状態 | 入る条件 | 担当 |
|---|---|---|
| REQUESTED | 受付（`intake_ticket_ref` 必須。D-06 の期限規則から `due_date` を計算） | privacy-admin |
| IDENTITY_VERIFIED | 本人確認の記録（`identity_evidence_ref`、適用した D-06 基準の決定ID）。基準未設定なら `DECISION_UNKNOWN` で進めない | privacy-admin |
| SCOPED | 名寄せの人手裁定（候補は F2 provider が提示し、氏名一致だけでは自動結合しない。SELECTED が1件でない場合は BLOCKED）と scope snapshot（provider 一覧・data element・as-of の hash） | privacy-admin |
| PREVIEWED | F2 provider の read-only 検索 → redaction 済み草案 package（未登録 provider・規則未設定の項目は「要手動確認」一覧として添付） | privacy-admin（作成者） |
| APPROVAL_PENDING → APPROVED | 作成者以外の privacy-admin が承認（DB の CHECK と service の両方で作成者≠承認者） | 別の privacy-admin |
| CONFIRMED | system 外での交付後に交付記録（方法・証跡参照）。package は記録と同時に PURGED | privacy-admin |
| BLOCKED / UNKNOWN / CANCELLED | 本人不明・複数候補・redaction 不能・provider 障害・取下げ | — |

再申立・再開は `reopen_of_request_id` で新 case を作る（元 case は書き換えない）。

**ファイル集合（見込み）**: `com.ses.privacy.production.dsar.PrivacyRequestService`、`PrivacySearchService`（F2 を呼ぶ）、`PrivacyExportPackageService`（`FileStorageService` で quarantine→published。package の最長保存期間 `privacy.dsar.package-max-age` は D-06 で決める値で、未設定なら草案を作らない）、`PrivacyExportPackageFileReferenceProvider`（`FileReferenceProvider` 登録。登録しないと `FileCleanupScheduler` に消される）、`FileScopeValidationService` への規則追加（`privacy.dsar.export` 保持者・同一 tenant・APPROVED の package だけ。既定は deny のまま）、`PrivacyDsarDeadlineScheduler`（期限接近を privacy-admin へ通知。feature OFF・test profile では動かない）、`PrivacyDsarApiController`、`templates/privacy/dsar/*.html`、`static/js/modules/privacy-dsar.js`、`com.ses.entity.privacy.*` / `com.ses.mapper.privacy.*`（request / subject link / action / package）、messages 4本。

**DDL**: 無し（W3-14 の表を使う）。

**依存**: NF07-F2、W3-14。NF07-A1H があれば case 画面に該当 subject の hold を表示する（任意）。

**対象限定テスト**: `PrivacyRequestServiceTest`（各遷移の前提、D-06 決定ID欠落で進めない、SoD、CAS 競合で 409、監査失敗で操作失敗）、`PrivacyRequestServiceMySqlTest`（`@Tag("mysql")`、二重承認・二重交付記録の競合）、`PrivacySearchServiceTest`（同姓同名で自動結合しない、他 tenant 0 件、未登録 provider を報告）、`PrivacyExportPackageServiceTest`（第三者情報 0、規則未設定で自動出力 0、交付記録で PURGED、`purge_after` 超過で削除）、`PrivacyExportPackageFileReferenceProviderTest`、`FileScopeValidationServiceTest`（package の download 可否）、`PrivacyDsarDeadlineSchedulerTest`、`PrivacyDsarApiControllerTest`、`ActionPermissionMatrixTest`（`privacy.dsar.*`）、`FeatureSwitchRouteCoverageTest`、`StaticAssetLocalityTest`、`MessageBundleConsistencyTest`。

**完了条件**: system から外部への送信経路が無い。依頼者の連絡先・本人確認書類の本体を保存しない。第三者情報を含む項目は自動で package に入らない。承認者≠作成者が DB と service の両方で強制される。

**Demo**: (1) dsar flag ON、D-06 の試験用決定IDと期限規則を dev に設定。(2) privacy-admin A が ACCESS 請求を受付 → 本人確認を記録 → 候補から本人を選ぶ（同姓の別人は自動で結合されない）→ 検索 → 草案作成（「要手動確認」一覧に未登録 provider が並ぶ）。(3) A 自身の承認は拒否される → privacy-admin B が承認 → A が package を download → 交付を記録 → package が PURGED になる。

**規模**: XL（main 15 前後、画面3、test 14 class 前後）。必要なら「受付〜SCOPED」と「検索〜交付」に分割する（Plan Review で決める）。

---

## 4. 署名後の batch

| batch | 内容 | ファイル集合（見込み） | DDL 草案 | 依存 | 対象限定テスト | 完了条件 / Demo | 規模 |
|---|---|---|---|---|---|---|---|
| NF07-REG（W-2） | 署名済み envelope の登録（verify-then-store）と、別の privacy-admin による current 切替 | `PrivacyPolicyRegistrationService`、`PrivacyPolicyApiController` に `POST /api/privacy/policies`・`POST /api/privacy/policies/{id}/activate`（`privacy.policy.manage`） | 無し（F1 を使う） | W3-14、D-03 の anchor（試験では test-only 鍵） | `PrivacyPolicyRegistrationServiceTest`（改竄・未知 anchor・失効 UNKNOWN・SoD・二重 current の CAS）、MySQL で current 切替の並行 | 登録者≠有効化者、旧 current は同一 tx で SUPERSEDED。Demo: test 鍵で署名した envelope を登録 → 別人が有効化 → 説明 API が `APPROVED_ACTIVE` | M |
| NF07-TRUST（W-3） | KMS/HSM 連携の anchor registry、CRL/OCSP の失効 provider | D-03 で決まる方式に依存（`KmsPolicyTrustRegistry`、`OcspRevocationStatusProvider` 等） | 無し | D-03 | 鍵 rotation の重複期間、失効伝播、provider 障害で UNKNOWN | 失効が即時に `POLICY_REVOKED` へ反映 | M〜L |
| NF07-REL（W-9） | hold 解除 proof（`VerifiedHoldRelease`、[`sf-w2-protocol.md`](sf-w2-protocol.md) §11.1）と解除 API | `VerifiedHoldRelease`、verifier、本番 adapter の `HOLD_RELEASED`、`POST /api/privacy/holds/{id}/release`（`privacy.hold.release`） | 無し | D-05（権限者名簿・SoD）、D-03（署名）、別 Plan Review | §11.1 の拒否条件全件、MySQL で head/hold revision CAS | 要求者・発行者・承認者の分離。Demo: 二者の署名付き proof で解除 → 同 command の再送は元 ACK | M |
| NF07-NF08-LINK | NF08 の送信 gate が NF07 policy を参照する（§8） | NF08 側 `AiProductionApprovalGate` / `GeminiTextServiceImpl`（NF08 所有者と調整）、NF07 側 `PolicyEnforcementPort`（説明 DTO を返さない内部判定） | 無し | W3-14、NF08-1 の hot file 調整 | NF07 UNKNOWN/REVOKED/EXPIRED で送信拒否、policyId 不一致で拒否 | §8 の手順 | S〜M |
| B1（W-10） | 処分 batch: item ごとの CAS・冪等・再送・部分失敗・誤対象取消、approved policy の allow-list、緊急停止、子スイッチ `privacy.retention.disposition.enabled`（親は retention） | `DispositionJobService`、worker、outbox（SF-W3）、DB 監査 | 下記 | D-02、D-04、D-09、全 gate、NF07-REG | SF-W3 の Demo 全件、hold との線形化 | flag 既定 OFF、人手承認の無い batch は拒否 | XL |
| B2（W-11） | Witness/attestor の本番化、recovery の本番配線、unquarantine 手続、DB+binary 同時点 restore 演習 | `ProductionLatestWitnessAdapter`、attestor（鍵は D-03）、`PrivacyRecoveryService` の本番入口、unquarantine API（D-07 の承認者） | Witness インスタンス側 DDL（別 location） | D-07、G-BKP、G-INC、W-6 の witness インスタンス | slice-d §7.3 の未完境界の全件 | restore 後は quarantine を自動解除しない | XL |
| M（W-13） | release 判定 | release 固定 SHA での coverage 閉環 scan（未分類 0・欠落 0）、全 gate、Go/No-Go | 無し | 全署名 | CI 全 job、PITR | Go/No-Go 記録 | M |

B1 の DDL 草案（番号なし）:

```sql
CREATE TABLE t_disposition_job (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL, job_key VARCHAR(64) NOT NULL,
  policy_id VARCHAR(128) NOT NULL, policy_revision VARCHAR(64) NOT NULL, method VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL, requested_by BIGINT NOT NULL, approved_by BIGINT NULL, approved_at DATETIME(6) NULL,
  emergency_stopped TINYINT NOT NULL DEFAULT 0, row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_disposition_job UNIQUE (tenant_id, job_key),
  CONSTRAINT chk_disposition_job_sod CHECK (approved_by IS NULL OR approved_by <> requested_by)
);
CREATE TABLE t_disposition_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL, job_id BIGINT NOT NULL,
  data_element_id VARCHAR(128) NOT NULL, target_ref VARCHAR(128) NOT NULL, target_version_hash CHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL, idempotency_key VARCHAR(128) NOT NULL, claim_token VARCHAR(64) NULL,
  lease_until DATETIME(6) NULL, attempt INT NOT NULL DEFAULT 0, result_evidence_ref VARCHAR(128) NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_disposition_item UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT fk_disposition_item_job FOREIGN KEY (job_id) REFERENCES t_disposition_job(id)
);
```

---

## 5. flag と UNKNOWN の扱い

### 5.1 flag

| key（環境変数） | 既定 | OFF のとき | ON にしたとき追加で要るもの（prod の起動時検査） |
|---|---|---|---|
| `privacy.retention.enabled`（`PRIVACY_RETENTION_ENABLED`） | false | `/privacy`（完全一致）・`/privacy/policies`・`/privacy/catalog`・`/privacy/holds` と対応する `/api/privacy/**` が 404、policy source は読まずに `POLICY_UNKNOWN`/`SOURCE_DISABLED`。`PrivacyHoldGate` の判定は flag に依存しない（掛けた hold は OFF でも守る。§3.4） | D-01・D-09 の決定ID、`privacy-admin` group が存在し有効な管理者が2名以上所属 |
| `privacy.dsar.enabled`（`PRIVACY_DSAR_ENABLED`） | false | `/privacy/dsar/**`・`/api/privacy/dsar/**` が 404、期限 scheduler 停止 | D-01・D-06・D-09 の決定ID、`privacy.dsar.due-days` と `privacy.dsar.package-max-age`、privacy-admin 2名以上、F2 provider が1件以上登録済み |
| `privacy.retention.disposition.enabled`（B1 で追加） | false | 処分 batch の入口と worker 停止 | 親が ON、D-02・D-04 の決定ID、緊急停止の設定 |

flag は `Feature` enum（`NF07_RETENTION` / `NF07_DSAR`）に登録して `FeatureSwitchFilter` の 404・`FeatureSwitchRouteCoverageTest` を共有する。両者は親子にしない（DSAR だけを先に開ける場合があるため）。`/privacy` を前方一致で登録すると `/privacy/dsar` まで retention 側で遮断されるので、`/privacy` は完全一致、他は機能ごとの下位 path で登録する。サイドバーの `privacy` 項目はどちらかの flag が ON かつ `privacy.view` 許可のときに出す（`Feature` の menu key は片方の機能にしか結べないため、sidebar 側で判定する）。設定 key は spec（D-09）の `privacy.*` を正とし、`app.features.*` 側に別名を作らない（`FeatureSwitchProperties.toggle()` が `privacy.*` の値を返す）。flag は公開可否であって権限ではないので、ON でも §6 の権限が別に要る。

### 5.2 UNKNOWN の理由コード（capability 単位の拒否）

| capability | 必要な決定・設定 | 欠けたときの結果 |
|---|---|---|
| policy の説明照会 | 無し（flag ON） | 値が無ければ `POLICY_UNKNOWN`（`POLICY_NOT_FOUND`、`REVOCATION_STATUS_UNKNOWN` など既存理由） |
| policy の登録・有効化（REG） | D-01、D-02、D-03 の anchor | 登録拒否（`UNKNOWN_TRUST_ANCHOR` 等） |
| hold 開始（A1H） | D-01、D-05、journal | `DECISION_UNKNOWN`（409）／`JOURNAL_UNAVAILABLE`（503） |
| hold 解除（REL） | D-05、D-03、解除 proof | 第1期は endpoint 自体が無い |
| DSAR の本人確認記録以降（A2） | D-01、D-06 | `DECISION_UNKNOWN`（409） |
| DSAR 草案 package | D-06 の redaction 規則と `package-max-age` | 草案を作らない／自動出力 0（要手動確認へ回す） |
| 処分（B1） | D-02、D-04、D-09、子スイッチ | 入口 404 ／ `POLICY_UNKNOWN` |
| unquarantine（B2） | D-07 | 隔離を維持 |
| NF08 外部送信 | NF07 policy が `APPROVED_ACTIVE`、NF08 の承認設定 | 送信拒否（§8） |

決定IDは `privacy.approval.d01.decision-id` / `privacy.approval.d01.signed-on` 〜 `d09` の形で設定する（環境変数 `PRIVACY_APPROVAL_D01_DECISION_ID` 等）。検査規則は NF08 の `AiExternalSendApproval`（ID の形式、`UNKNOWN`・`TBD`・`PENDING` 等の placeholder 断片の拒否、項目間の重複拒否、署名日が未来なら拒否）と同じにする。決定IDは「人が署名した」ことを system に伝える入力であって承認の証明ではない。値は署名後に人が設定し、AI・実装者は設定しない。

---

## 6. W-5 権限設計

### 6.1 action key

`ActionPermissionResolver` に `privacy` root を追加し、`/api/privacy/**` と `/privacy/**` を専用の分岐で解決する。この分岐は既存の `isExportPath` / `isDownloadPath` 判定より**前**に置く（後ろに置くと `/api/privacy/dsar/1/export` が `export.execute`、`.../download` が `file.download` に解決され、営業・要員が持つ action で action 層を通過してしまう）。

| action key | 経路 | 第1期 |
|---|---|---|
| `privacy.view` | `GET /privacy`、`GET /privacy/**`（画面）、`/api/privacy` 直下の未知 subpath の GET | ○ |
| `privacy.policy.view` | `GET /api/privacy/policies/**` | ○（W3-14） |
| `privacy.policy.manage` | `POST`/`PUT /api/privacy/policies/**` | key だけ予約（REG） |
| `privacy.catalog.view` | `GET /api/privacy/catalog/**` | ○（F2） |
| `privacy.hold.view` / `privacy.hold.manage` | `GET` / `POST /api/privacy/holds` | ○（A1H） |
| `privacy.hold.release` | `POST /api/privacy/holds/{id}/release` | key だけ予約（REL） |
| `privacy.dsar.view` / `privacy.dsar.manage` | `GET` / `POST`・`PUT /api/privacy/dsar/**` | ○（A2） |
| `privacy.dsar.approve` | `POST /api/privacy/dsar/{id}/approve` | ○（A2） |
| `privacy.dsar.export` | `/api/privacy/dsar/{id}/package/**`（download・export を含む） | ○（A2） |
| `privacy.disposition.*` / `privacy.recovery.*` | B1 / B2 | 署名後 |

`/api/privacy` 配下の未知 subpath は method に応じて `privacy.view` / `privacy.create` / `privacy.update` / `privacy.delete` に解決し、null にはしない（null は MenuPermissionFilter で全員 403 になる。privacy 配下に留めれば `privacy.*` の規則がそのまま効く）。

### 6.2 評価規則（`AuthorizationServiceImpl`）

```java
// isAllowed の既存分岐へ、管理者の短絡許可より前に1つ足す（既存の one-on-one.confidential 分岐と同型）。
if ("one-on-one.confidential".equals(actionKey)) {
    // 既存のまま
} else if (actionKey.startsWith("privacy.")) {
    if (!ROLE_ADMIN.equals(role)) {
        return false;                 // 管理者 role 以外は group に関係なく拒否（誤割当の防御）
    }
    // 管理者でも短絡許可しない。以降の group 評価（deny 優先）へ進む。
} else if (ROLE_ADMIN.equals(role)) {
    return true;                      // 既存のまま
}
// legacyRoleAllows の先頭:
if (actionKey.startsWith("privacy.")) {
    return false;                     // group 未割当のユーザーは privacy 管理者になれない
}
```

group 評価は既存どおり「有効な所属 group のどれかに deny があれば拒否、無ければ allow を探す」。candidate は既存の `candidateKeys`（exact、`privacy.*`、`*`）をそのまま使う。

### 6.3 seed（`V?__nf07_privacy_permission_seed.sql`、暫定 V193）

```sql
-- 1. 管理者専用 group。role-admin を持つ全 tenant に作る（新規 install では 'default' のみ）。
INSERT INTO m_permission_group (tenant_id, group_key, group_name, description, enabled, created_at, updated_at, deleted_flag)
SELECT g.tenant_id, 'privacy-admin', 'プライバシー管理（管理者専用）',
       'NF07 privacy.*。管理者roleのユーザーにだけ、role-adminの代わりに割り当てる', 1, NOW(), NOW(), 0
FROM m_permission_group g
WHERE g.group_key = 'role-admin'
  AND NOT EXISTS (SELECT 1 FROM m_permission_group e WHERE e.tenant_id = g.tenant_id AND e.group_key = 'privacy-admin');

-- 2. privacy-admin は role-admin と同じ baseline（'*'）に privacy.* を明示で加える。
INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, created_at, updated_at, deleted_flag)
SELECT g.tenant_id, g.id, a.action_key, 0, NOW(), NOW(), 0
FROM m_permission_group g
JOIN (SELECT '*' AS action_key UNION ALL SELECT 'privacy.*') a
WHERE g.group_key = 'privacy-admin'
  AND NOT EXISTS (SELECT 1 FROM t_permission_group_action x
                  WHERE x.tenant_id = g.tenant_id AND x.group_id = g.id AND x.action_key = a.action_key);

-- 3. privacy-admin 以外の全 group で privacy.* を deny（V110 と同型。tenant・enabled を問わない）。
UPDATE t_permission_group_action a
JOIN m_permission_group g ON g.id = a.group_id
SET a.deny_flag = 1, a.deleted_flag = 0, a.updated_at = NOW()
WHERE a.action_key = 'privacy.*' AND g.group_key <> 'privacy-admin';

INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, created_at, updated_at, deleted_flag)
SELECT g.tenant_id, g.id, 'privacy.*', 1, NOW(), NOW(), 0
FROM m_permission_group g
WHERE g.group_key <> 'privacy-admin'
  AND NOT EXISTS (SELECT 1 FROM t_permission_group_action x
                  WHERE x.tenant_id = g.tenant_id AND x.group_id = g.id AND x.action_key = 'privacy.*');

-- 4. menu。到達可否は action 層と SecurityConfig が決める（sidebar 表示用）。
INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('privacy', 'プライバシー管理', '/privacy', '/api/privacy', 97);
INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT '管理者', m.id FROM m_menu m WHERE m.menu_key = 'privacy';
```

`sort_order` 97 は仮。既存値との衝突は W3-14 で確認する。group 作成・action 編集の API は無い（`PermissionGroupApiController` は一覧と割当だけ）ので、group は migration でしか増えない。今後 group を追加する migration は `privacy.*` の deny 行も入れる必要があり、`FlywayNf07PrivacyF1MigrationTest` の「`privacy-admin` 以外で deny を持たない group が 0」がこれを検出する。

### 6.4 caller provider の権限判定

`SecurityContextCallerContextProvider.hasExplicitPrivacyAdminAuthority`（`ROLE_ADMIN` と `PRIVACY_ADMIN_READ` の GrantedAuthority を要求）は、この app では誰にも発行されない authority を前提にしている（role は `ROLE_<role>` だけ）。B-NF07-2 に従い、判定の正本を permission group action に移す。

- 変更後: 永続 `SysUser` を tenant 付きで再読し（既存）、role が `管理者` であることを確認した上で、`AuthorizationService.isAllowed(authentication, "privacy.policy.view")` が true の場合だけ `TrustedActorRole.ROLE_ADMIN` を返す。`AuthorizationService` が取れなければ拒否。
- GrantedAuthority `PRIVACY_ADMIN_READ` は発行しない。login 時に authority を焼き込む方式は、group 変更から再 login までの古い権限が残るため採らない（group 変更時は既存の `revokeAllForUser` で session も失効する）。
- これは Batch 4a の §4.2「role は GrantedAuthority から判定」を変更するので、W3-14 の Plan Review で明示的に承認を受ける。

### 6.5 `ActionPermissionMatrixTest` の変更点

- test user を追加: `9005`（管理者、`privacy-admin` だけ所属）、`9006`（営業、`privacy-admin` だけ所属＝誤割当の再現）、`9007`（管理者、`role-admin` だけ所属）。既存の `9000`（管理者）は現行 seed で group 未割当なので legacy fallback の管理者として使う。
- 追加 test:
  - `privacy系actionはprivacy-admin所属の管理者だけに許可される`: `privacy.view`、`privacy.policy.view`、`privacy.hold.manage`、`privacy.dsar.approve`、`privacy.unknown-sub.view` について、9005 は true、9007（`role-admin` の管理者）・9000（未割当の管理者）・9001〜9004・9006 は false。
  - `privacy-admin所属の管理者は管理者の既存actionを失わない`: 9005 で `dashboard.view`、`user.view`、`one-on-one.confidential` が true。
  - `legacy fallbackはprivacy系actionを誰にも許可しない`: 9000・9999 で false。
  - `privacyのURIはexportとdownloadより先にprivacy actionへ解決される`: `GET /api/privacy/dsar/1/package/download` → `privacy.dsar.export`、`POST /api/privacy/dsar/1/export` → `privacy.dsar.export`、`POST /api/privacy/holds` → `privacy.hold.manage`、`GET /api/privacy/unknown-sub` → `privacy.view`、`GET /privacy/holds` → `privacy.view`。
- 機密 action の集合に追加: `既知resource許可を持つroleでも機密actionは拒否指定が優先される` と `マネージャーは全権限wildcardを持たず機密actionを実行できない` に `privacy.view`・`privacy.dsar.manage` を加える。
- seed 内容の検査（`permissionGroupSeedの内容そのものを検査する`）: 営業 deny 8→9、HR 7→8、マネージャー 8→9。追加で、group 1（`role-admin`）と 5（`role-member`）に `privacy.*` deny が1件、group 6（`privacy-admin`）に `*` 許可1件・`privacy.*` 許可1件・deny 0件、`privacy-admin` 以外で `privacy.*` deny を持たない group が0件。「非管理者 group（2〜5）に `*` 許可が無い」は不変。
- `BUSINESS_ACTIONS` は変更しない（privacy key を足さない）。
- `permission-group-seed-h2.sql` に group 6 と上記の行、test user 9005・9006・9007 の `t_user_permission_group` 所属行を足す（DDL lane の所有範囲）。

### 6.6 「allow-list 禁止」との整合

CLAUDE.md の規則は「role ごとに許可 key を列挙すると、URI root から機械生成される未列挙 key が黙って 403 になる」ことを禁じている。本設計は次の理由でこれに当たらない。

1. 許可は resource wildcard `privacy.*` の1行だけで、`privacy-admin` に与える。privacy 配下に endpoint が増えても列挙の追加は要らない（未知 subpath も `privacy.*` に入る）。
2. 他の group が privacy を使えないのは deny 行（差し引き）と code の role 検査による。列挙から漏れたから拒否されるのではない。
3. `BUSINESS_ACTIONS` を編集しない。既存業務 action の結果は変わらない。
4. legacy fallback は `privacy.*` を無条件に拒否する（admin-only action と同じ扱い）。

なお、CLAUDE.md の「API root を足しても AuthorizationService / legacy fallback の変更は要らない」は V66_1 以降の実装（非管理者 group は既知 resource の wildcard を持ち、未知 root は拒否）と食い違っている。本 batch はその記述に依存しない。CLAUDE.md の修正は本 batch の範囲外（merge 時の調整対象）。

### 6.7 割当の排他と guard（deny 優先の帰結）

deny は所属 group 全体で優先されるため、`role-admin`（`privacy.*` deny）と `privacy-admin`（許可）の両方に所属すると privacy は拒否される。このため privacy 管理者は **`role-admin` の代わりに `privacy-admin` を割り当てる**。`privacy-admin` は `*` を持つので、管理者としての既存 action（短絡許可の対象外である `one-on-one.confidential` を含む）は失わない。

`PermissionGroupManagementServiceImpl.replaceAssignments` に次を足す。

- 要求に `privacy-admin` を含み、対象ユーザーの role が `管理者` でなければ 400（`error.permission.adminOnlyGroup`）。`privacy-admin` は `*` を持つので、誤割当を data の deny だけに任せない。
- 要求に `privacy-admin` と、`privacy.*` deny を持つ他の group が同時に含まれれば 409（`error.permission.privacyGroupConflict`）。判定は group key の固定値ではなく deny 行の有無で行う。
- role 変更時の既定 group への再割当（`SysUserServiceImpl`）は現行どおり全置換なので、管理者から外れたユーザーの `privacy-admin` は自動で外れる。

既知の副作用: 承認経路で `PERMISSION_GROUP=role-admin` を承認者に指定している場合、privacy 管理者はその承認者から外れる（seed にその経路は無く、運用者が設定した場合だけ起きる）。

### 6.8 その他の境界

- `SecurityConfig`: `/privacy/**`、`/api/privacy/**` を `hasRole("管理者")` で静的に制限する（`/user/**` と同じ二重化）。
- break-glass: `BreakGlassServiceImpl.validateAndSerializeActions` で `privacy.` 接頭辞を拒否する（`privacy` が既知 resource になると `isKnownAction` が true を返し、緊急 access の対象に入れてしまうため）。
- service 層の再認可: `MenuPermissionFilter` は `AuthorizationService` が取れない slice では素通しになるので、privacy の各 service は `PrivacyAccessGuard` で同じ action を再判定し、取れなければ拒否する。
- sidebar: feature ON かつ `privacy.view` が許可されたときだけ表示する（`allowedMenus` は role 単位なので、それだけでは privacy-admin 以外の管理者にも出てしまう）。

---

## 7. 署名項目ごとの開放手順

署名が届いた後に人（運用者）が行う。AI・実装者は値を設定しない。各手順の後に再起動し、起動 log の機能スイッチ状態と `PrivacyProductionConfigurationValidator` の結果を記録する。

| 署名項目 | 署名後に変える値 | 開く flag・capability | 前提 batch |
|---|---|---|---|
| D-01 正式 Privacy Owner | `PRIVACY_APPROVAL_D01_DECISION_ID` / `_SIGNED_ON`。requirements.md の `<OWNER>` を人が置換 | 単独では開かない（全 flag の前提） | W3-14 |
| D-02 / DG-07 核心（保持期間・trigger・法的根拠） | data element ごとの policy を issuer が署名した envelope にして登録・有効化（W-2）。`PRIVACY_APPROVAL_D02_*` | 対象 element が `POLICY_UNKNOWN` → `APPROVED_ACTIVE`。NF03 の `FEATURE_NF03_CERTIFICATION_PII_ENABLED`（B-NF03-1）は NF03 の owner がこの結果を確認して判断。NF08 は §8 | W3-14、NF07-REG、NF07-TRUST |
| D-03 Trust Anchor・KMS・失効 | `privacy.trust.anchors[*]`（authority ID・key ID・公開鍵・有効区間）、失効 provider の接続設定 | policy の検証が通るようになる（単独では flag を開かない） | NF07-TRUST |
| D-04 処分方式 | envelope の許可処分方式、`PRIVACY_APPROVAL_D04_*` | B1 の子スイッチの前提 | B1 |
| D-05 hold 権限者・SoD・hold 種類 | `PRIVACY_APPROVAL_D05_*`、hold 種類の値（`hold_kind` の許可値）、解除の承認者規則 | `privacy.retention.enabled` 下の hold 開始。解除は NF07-REL 後 | NF07-A1J、NF07-A1H（解除は NF07-REL） |
| D-06 DSAR SOP | `PRIVACY_APPROVAL_D06_*`、`privacy.dsar.due-days`、`privacy.dsar.package-max-age`、本人確認基準ID、redaction 規則（版付き） | `privacy.dsar.enabled` の前提 | NF07-F2、NF07-A2 |
| D-07 unquarantine | `PRIVACY_APPROVAL_D07_*`、unquarantine の承認者 group | B2 の unquarantine | B2 |
| D-08 外部 AI・電子契約 DPA | NF08 の `AI_EXTERNAL_SEND_DPA_REFERENCE` 等（NF08 側）。NF07 側は provider を外部登録しない方針を維持 | NF08 の送信 gate（§8） | NF07-NF08-LINK |
| D-09 本番 flag 開放 | `PRIVACY_APPROVAL_D09_*` を設定し、前提の署名が揃った flag から1つずつ `PRIVACY_RETENTION_ENABLED=true` / `PRIVACY_DSAR_ENABLED=true` にする（順序は問わない） | 両 flag | 各 flag の前提 batch |
| DG-07 全体 | 上記 D-01〜D-09 のうち DG7-a〜i に当たるもの＋ G-EXP・G-REC・G-AUD。tasks.md 0.4 coverage closure を release SHA の scan で閉じる | B1・M の前提 | M |
| P-SRC / P-LE / P-ACT / P-API | P-SRC: policy 正本ストアの承認（現行の `m_retention_policy` を正本と認めるか、別ストアへ移すか）。P-LE: 法人単位 policy の要否（要なら envelope schema v2）。P-ACT: Clock/NTP と許容 skew。P-API: 下流呼出の認可責任 | 説明照会の「本番 currentness 証明済み」表示、NF08 接線 | W3-14、NF07-NF08-LINK |
| G-DBA / G-BKP | Journal（と B2 で Witness）インスタンスの接続設定 `privacy.journal.datasource.*`、GRANT 実行、別 backup | hold 開始（journal 必須） | NF07-A1J |

---

## 8. NF08 との関係

現状（基準 `1be03bea`）:

- NF08 の外部送信承認設定 `ai.external-send-approval.nf07-policy-id`（環境変数 `AI_EXTERNAL_SEND_NF07_POLICY_ID`、`application.yml:387`）は、`AiExternalSendApproval.evaluate`（`AiExternalSendApproval.java:79-84`）で形式・placeholder・他の決定IDとの重複だけを検査している。NF07 の policy source とは照合していない。
- 設定値が placeholder（`UNKNOWN`・`TBD` 等の断片を含む）なら complete にならない。ただし形式が正しい任意の ID を設定すれば、NF07 に該当する policy が無くても complete になる（照合が無いため）。この穴を NF07-NF08-LINK で塞ぐ。それまでは、NF07 policy が `APPROVED_ACTIVE` であることの確認は運用手順（下の 4.）だけが担う。
- 呼出元は起動時の `AiProductionApprovalGate` と送信ごとの `GeminiTextServiceImpl`（`:252`、`:276`）。

NF07 の policy 値が確定した後の手順:

1. D-02（AI payload の保持・外部送信 policy）と D-08（Gemini の DPA）が署名される。
2. issuer が D-03 の鍵で envelope に署名する（key は合成 catalog と同じ形: module `NF08_AI_SUMMARY`、data element `AI_PROMPT_LOG`、action `EXTERNAL_DISPATCH`、provider `gemini-adapter`。本番の正式な値は F2/REG で確定）。
3. privacy-admin A が登録し、別の privacy-admin B が有効化する（NF07-REG）。
4. privacy-admin が `GET /api/privacy/policies/evaluate` で `APPROVED_ACTIVE` と policyId・revision・digest を確認する。
5. 運用者が NF08 の `AI_EXTERNAL_SEND_NF07_POLICY_ID` にその policyId を設定する（DG-08 の決定ID・DPA 参照など NF08 の他の項目と一緒に）。再起動して `AiProductionApprovalGate` が complete を返すことを確認する。
6. NF07-NF08-LINK（Q-8 により署名前に実装してよい）を入れておけば、送信ごとに NF07 の内部判定 port が policyId の一致と `APPROVED_ACTIVE` を確認し、UNKNOWN・EXPIRED・REVOKED・DRIFT・不一致なら送信を拒否する。policy が失効・期限切れになれば、NF08 の設定を変えなくても送信が止まる。再署名後は 3〜5 をやり直す。

注意点:

- `APPROVED_ACTIVE` だけでは送信を許可しない（prod-wiring §4.2）。NF08 自身の承認設定（model・用途・宛先・費用上限・送信ごとの監査、B-NF08-2）は従来どおり必要。prod-wiring §4.2.3 の `CaseApprovalEnvelope` / `VerifiedDispatchFence` は NF07 自身の外部 dispatch に適用する（[`status-alignment.md`](status-alignment.md) §3 X-1）。
- 説明照会（W-4）は caller を privacy 管理者に限るので、一般利用者の Copilot 送信から直接は呼べない。NF07-NF08-LINK では tenant だけを束縛し、説明 DTO を返さない内部判定 port（`PolicyEnforcementPort`）を別に設計する（Plan Review 対象）。
- `application.yml` の `ai.*` と NF08 の class は NF08-1 の所有（wave3 §2）。NF07-NF08-LINK はその所有者と調整して入れる。

---

## 9. 署名前実装のリスクと緩和

| # | リスク | 緩和 |
|---|---|---|
| R-1 | 署名前に flag が誤って ON にされる | prod では flag ON に D-01・D-09 等の決定IDが必須（起動失敗）。flag は真偽値として厳格に bind。OFF の入口は 404。起動 log に状態を出す |
| R-2 | 署名の内容が想定と違い、作り直しになる（例: hold 開始も二者、法人単位の policy、DSAR 期限の数え方） | 値は envelope・設定で外から与え、code に書かない。未定の列は UNKNOWN/NULL を許す。envelope は schema v1 だけを解釈し、法人 ID を署名対象に入れる v2 は P-LE 後に別 Plan（S-14）。batch ごとに Plan Review |
| R-3 | UNKNOWN が既定値として扱われる（技術上の現在値が法的値に見える、placeholder が通る） | placeholder 断片の拒否、技術値を `APPROVED_*` として返さない test、seed に期間を入れない Review 項目 |
| R-4 | 権限の誤割当（非管理者に `privacy-admin`、`role-admin` との併用で黙って拒否） | code の role 検査、割当 guard（400/409）、matrix test、割当変更時の監査と session 失効（既存） |
| R-5 | 既存の削除 batch の挙動が変わる。逆に、flag を OFF に戻したときに既存 hold が効かなくなる | hold 機構を一度も使っていない間は gate の照会1回だけで挙動不変。使った後は flag に関係なく hold を守り、確認できなければその回を全部 skip する（消しすぎる方向には倒さない）。scheduler ごとに test |
| R-6 | Journal インスタンスが遅れる／app DB を指してしまう | hold 開始は journal 必須（`JOURNAL_UNAVAILABLE`）。`server_uuid` の相違・耐久設定・過剰権限を起動時に検査して失敗させる |
| R-7 | 状態文書がまた食い違う | 状態文には日付・SHA・範囲・証拠を付ける規則（[`status-alignment.md`](status-alignment.md) §1）。verdict は外部記録で確認する |
| R-8 | 署名前に作った DDL を後で変えたくなる | 新しい migration だけで直す（既存 script は変更しない）。第1期の表は本番で flag ON まで空のまま。disposition 表は B1 まで作らない |
| R-9 | 新しい表に PII が溜まる | 依頼者連絡先・本人確認書類の本体は持たず参照IDだけ。hold 理由は code・hash・ticket。package は quarantine→published、交付記録で PURGED、`FileReferenceProvider` 登録 |
| R-10 | NF08 が `APPROVED_ACTIVE` を送信許可と読み違える | NF08 の承認設定は不変。NF07-NF08-LINK は両方を要求する |
| R-11 | 管理者の短絡許可を外したことで、privacy 以外の判定が変わる | 変更は `privacy.` 接頭辞だけ。matrix の既存 assertion は無変更で通ること |
| R-12 | 合成層を本番から呼べてしまう | 合成部品の guard は変更しない。本番部品は `ProductionPrivacyComponents` 経由だけ。`PrivacyFoundationIsolationTest` で固定 |
| R-13 | 署名前に作り込んだ code が開放されないまま腐る | 開放手順（§7）を batch ごとに test で再現できる形にし、M の前に固定 SHA で再実行する |
| R-14 | NF08 の `nf07PolicyId` は形式だけの検査なので、NF07 に policy が無くても形式の正しい ID で送信承認が complete になる | NF08 を開く前に NF07-NF08-LINK をマージする（Q-8 により署名前に実装可）。それまでは §8 の 4.（`APPROVED_ACTIVE` の確認）を開放手順の必須項目にする |

---

## 10. Plan Review で重点的に見てほしい箇所

1. §6.7 割当の排他（`role-admin` の代わりに `privacy-admin`、`privacy-admin` は `*` を持つ）が、deny 優先の既存意味を変えずに「管理者専用 group だけに開く」を満たしているか。代案（`privacy.*` の判定でだけ `*` を candidate から外し、`role-admin` に deny を置かない）との比較。
2. §6.4 caller provider の判定を GrantedAuthority から permission group action へ移すこと（Batch 4a §4.2 の変更）。
3. §6.1 privacy の解決分岐を export/download 判定より前に置くこと、未知 subpath を `privacy.view` 等へ解決すること。
4. §3.1 `m_retention_policy` の current pointer（生成列＋UNIQUE、有効化者≠登録者の CHECK）と、P-SRC 未署名の間は「本番 currentness 未証明」とする扱い。
5. §3.4 `PrivacyHoldGate` を batch 単位の gate にしたこと、判定を flag から切り離したこと（OFF に戻しても既存 hold を守る）、Journal append の前に書く pending 記録（ACK 後の projection 失敗で hold を取りこぼさないため）、subject を決められない行の扱い、対象外とした scheduler の理由。
6. §3.3 Journal を application DB の Flyway と別 location・別履歴表で管理すること。
7. §5.1 flag の設定 key を `privacy.*` にし、`Feature` enum と共有する形。
8. §3.5 A2 の範囲（ACCESS だけ草案まで、他の請求種別は記録だけ）と、package の最長保存期間を D-06 の値とし未設定なら作らないこと。
9. §4 B1 の DDL を F1 から外したこと（tasks.md の F1 定義の変更）。
10. §8 NF08 の内部判定 port を説明照会と別にすること。現行 NF08 は `nf07PolicyId` を形式でしか検査していないこと（R-14）と、NF08 開放前に NF07-NF08-LINK を入れる順序。

---

## 11. 依頼者の判断が要る未決事項

1. **第1期 hold の正本**: 本計画は独立 Journal を正本とする（SF-03a/SF-04a と同じ。後で正本を移す手間が無い）。その結果、hold の開放は DBA による Journal インスタンスの用意（W-6）を待つ。Journal を待たずに hold を開きたい場合は、第1期だけ application DB の `t_legal_hold` を正本とし、B1 の前に Journal へ移す代案がある（移行作業と二重正本の期間が生じる）。
2. **privacy 管理者の割当の排他**: 指示どおり全 group で `privacy.*` を deny すると、deny 優先のため privacy 管理者は `role-admin` と併用できず、`role-admin` の代わりに `privacy-admin` を割り当てることになる。承認経路で `role-admin` を承認者 group にしている場合、privacy 管理者はそこから外れる。この運用でよいか（不可なら §10-1 の代案を採る）。
