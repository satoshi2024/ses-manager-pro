# 機能の開放を system 内で行う 設計

- 状態: **草案（第 1 段階: spec のみ）**。要件は `requirements.md`（以下「要件」）、手順は `tasks.md`。
- 行番号は `88b31d45` の行番号である。実装の着手時に再確認する。
- 判断が要る点は §18 に J-01〜J-20 として一覧する。J-01・J-09・J-16 は決定済み（2026-11-22、利用者）で、決まった値で各節を書いた。他は推奨の状態で、各節は推奨で書く（独立 review の後に統括が決める）。

## 0. 位置付け

機能の開閉を、部署の設定（環境変数）から DB の開放状態へ移す。開閉の判定窓口（`FeatureSwitches`）と、それを呼ぶ filter・ControllerAdvice・scheduler・service 層の gate は変えず、`FeatureSwitches` が読む源だけを差し替える。prod の起動時検査のうち機能の条件を見ていたものは、同じ判定を「条件の評価器」として再利用し、起動を止める代わりにその機能を閉じる。NF05 だけは bean の構成が起動時に決まるため、開ける変更を次の起動で反映する。

## 1. 全体像

```
[画面 /feature-enablement] ──AJAX──> [FeatureEnablementApiController /api/feature-enablement/**]
                                          │
          ┌───────────────────────────────┼────────────────────────────────┐
          v                               v                                v
[FeatureEnablementService]     [FeatureSignOffService]          [FeatureEnablementQueryService]
 申請・承認・却下・取下げ・       署名の登録・確認・却下・取消し・     一覧・詳細・履歴・再評価
 閉鎖・自動閉鎖・設定の変更       差替え・文書の取得
          │                               │
          └──────────────┬────────────────┘
                         v
     [FeatureConditionRegistry] ── 開放単位ごとの評価器（起動時検査の判定を再利用）
                         │
                         v
  DB: t_feature_enablement_generation / _state / _request / _setting / _event、t_feature_sign_off
                         │ commit の後・世代の poll
                         v
  [FeatureEnablementSnapshotHolder]（memory。AtomicReference）
                         │
                         v
  [DatabaseFeatureEnablementStateSource] ──> [FeatureSwitches#isEnabled] ──> filter・advice・scheduler・gate

  起動時: [IntegrationHubEnablementBootResolver]（EnvironmentPostProcessor。NF05 だけ）
          [FeatureEnablementStartupEvaluator]（SmartLifecycle。web server の前）
  定期:   [FeatureEnablementConditionScheduler]（@Scheduled＋@SchedulerLock。void）
  緊急:   FEATURE_ENABLEMENT_ALLOWED（部署の設定）
```

package は `com.ses.featureenablement`（service・mapper 以外の部品）とし、entity・mapper は既存の規約（`com.ses.entity`・`com.ses.mapper`。追記専用の mapper は `AppendOnlyMapper` の規約どおり `@MapperScan` の外）に置く。

## 2. データモデル（V203）

### 2.1 表の一覧

| 表 | 役割 | 更新 |
|---|---|---|
| `t_feature_enablement_generation` | tenant ごとの世代（変更の通し番号）。全ての書込みの最初に lock する | 更新あり |
| `t_feature_enablement_state` | 開放単位の現在の開放状態 | 更新あり（version の CAS） |
| `t_feature_enablement_request` | 申請（開放・設定の変更）。未決から終了へ 1 回だけ遷移する | 更新あり（version の CAS） |
| `t_feature_enablement_setting` | 承認で変える設定（現在は NF02 の公開範囲だけ） | 更新あり |
| `t_feature_sign_off` | 署名の登録・確認・失効・取消し・差替え・消去 | 更新あり（version の CAS） |
| `t_feature_enablement_event` | 全ての遷移の履歴 | **追記専用**（`AppendOnlyMapper`＋trigger） |

- 版番号は V203 の 1 件とする（統括の指定）。後の段階で DDL が要れば V204 以降を統括に申請する。
- V203 の中の作成の順は FK の向きに合わせて generation → request → state → setting → sign_off → event、その後に trigger・menu・data element の seed とする（本節の説明の順とは違う）。
- どの表も `deleted_flag` を持たない（論理削除しない。entity に `deletedFlag` を置かないので、全体の `logic-delete-field` は掛からない）。
- 時刻の列は `DATETIME(6)` で、JVM の時計（`AutoFillMetaObjectHandler.rowTimestamp(clock)`）で書く。日付の列（署名日・失効日）は JST の業務日の `DATE`。
- `tenant_id` は `VARCHAR(100)`（V190 の `t_legal_hold` と同じ）。値は設定の tenant（`OIDC_TENANT_ID`、未設定なら `default`）。

### 2.2 `t_feature_enablement_generation`

```sql
CREATE TABLE t_feature_enablement_generation (
  tenant_id   VARCHAR(100) NOT NULL PRIMARY KEY,
  generation  BIGINT       NOT NULL,
  updated_at  DATETIME(6)  NOT NULL
);
```

- 行は最初の書込みで作る（`INSERT IGNORE` の後に `SELECT … FOR UPDATE`）。全ての書込みの transaction はこの行を最初に lock し、`generation` を 1 つ上げる。tenant の中の変更は直列になる（量が少ないため直列で足りる。§5.1 の lock の順）。
- snapshot はこの値で新旧を判断する。

### 2.3 `t_feature_enablement_state`

```sql
CREATE TABLE t_feature_enablement_state (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id          VARCHAR(100) NOT NULL,
  feature_key        VARCHAR(64)  NOT NULL,
  state              VARCHAR(30)  NOT NULL,
  version            INT          NOT NULL DEFAULT 0,
  open_request_id    BIGINT       NULL,
  opened_by          BIGINT       NULL,   -- 申請者 sys_user.id
  approved_by        BIGINT       NULL,   -- 承認者 sys_user.id（申請者と別人）
  opened_at          DATETIME(6)  NULL,
  restart_applied_at DATETIME(6)  NULL,   -- NF05: 起動で反映した時刻
  closed_reason      VARCHAR(64)  NULL,
  closed_by          BIGINT       NULL,   -- 手動閉鎖の管理者（自動閉鎖は NULL）
  closed_at          DATETIME(6)  NULL,
  updated_at         DATETIME(6)  NOT NULL,
  CONSTRAINT uk_fe_state UNIQUE (tenant_id, feature_key),
  CONSTRAINT ck_fe_state_key CHECK (feature_key IN ('NF01','NF01_DUAL_CONFIRMATION','NF02','NF02_CUSTOMER_PORTAL',
      'NF03','NF03_CERTIFICATION_PII','NF04','NF05_INBOUND','NF05_OUTBOUND','NF06','NF07_RETENTION','NF07_DSAR','NF10')),
  CONSTRAINT ck_fe_state_value CHECK (state IN ('CLOSED','OPEN','OPEN_PENDING_RESTART','AUTO_CLOSED','MANUALLY_CLOSED')),
  CONSTRAINT ck_fe_state_open CHECK (state NOT IN ('OPEN','OPEN_PENDING_RESTART')
      OR (open_request_id IS NOT NULL AND opened_by IS NOT NULL AND approved_by IS NOT NULL
          AND opened_by <> approved_by AND opened_at IS NOT NULL)),
  CONSTRAINT ck_fe_state_restart CHECK (state <> 'OPEN_PENDING_RESTART' OR feature_key IN ('NF05_INBOUND','NF05_OUTBOUND')),
  CONSTRAINT ck_fe_state_closed CHECK (state NOT IN ('AUTO_CLOSED','MANUALLY_CLOSED')
      OR (closed_reason IS NOT NULL AND closed_at IS NOT NULL)),
  CONSTRAINT ck_fe_state_manual CHECK (state <> 'MANUALLY_CLOSED' OR closed_by IS NOT NULL),
  CONSTRAINT fk_fe_state_request FOREIGN KEY (open_request_id) REFERENCES t_feature_enablement_request (id)
);
```

- 行が無い開放単位は `CLOSED` として扱う（行は最初の申請で作る）。
- 開放単位の key は全て既知のため CHECK で固定する（綴りの誤りを DB でも拒否する）。FK に参照動作（`ON DELETE` 等）を付けない（MySQL 8 は参照動作の列に CHECK を付けられない）。
- 「申請中」は `t_feature_enablement_request` の未決の行があることで表し、この表の値にしない（未決の申請は開放状態を変えない）。

### 2.4 `t_feature_enablement_request`

```sql
CREATE TABLE t_feature_enablement_request (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100)  NOT NULL,
  request_type          VARCHAR(20)   NOT NULL,   -- OPEN / SETTING_CHANGE
  target_key            VARCHAR(64)   NOT NULL,   -- 開放単位の key か設定の key
  proposed_value        VARCHAR(100)  NULL,       -- SETTING_CHANGE の値（NULL は未設定へ戻す）
  status                VARCHAR(20)   NOT NULL,
  pending_slot          TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN 1 ELSE NULL END) STORED,
  condition_fingerprint CHAR(64)      NOT NULL,   -- §4.5
  condition_summary     VARCHAR(2000) NOT NULL,   -- 条件の code と成否だけ（値・氏名を含めない）
  reason                VARCHAR(500)  NULL,
  requested_by          BIGINT        NOT NULL,
  requested_at          DATETIME(6)   NOT NULL,
  expires_at            DATETIME(6)   NOT NULL,
  decided_by            BIGINT        NULL,
  decided_at            DATETIME(6)   NULL,
  decision_reason       VARCHAR(500)  NULL,
  version               INT           NOT NULL DEFAULT 0,
  CONSTRAINT uk_fe_request_pending UNIQUE (tenant_id, target_key, pending_slot),
  CONSTRAINT ck_fe_request_type CHECK (request_type IN ('OPEN','SETTING_CHANGE')),
  CONSTRAINT ck_fe_request_status CHECK (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN','EXPIRED','STALE','CANCELLED')),
  CONSTRAINT ck_fe_request_decision CHECK (
         (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
      OR (status IN ('APPROVED','REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL
          AND decided_by <> requested_by)
      OR (status = 'WITHDRAWN' AND decided_by IS NOT NULL AND decided_by = requested_by AND decided_at IS NOT NULL)
      OR (status IN ('EXPIRED','STALE','CANCELLED') AND decided_by IS NULL AND decided_at IS NOT NULL)),
  INDEX idx_fe_request_status (tenant_id, status, expires_at)
);
```

- **申請者≠承認者・却下者**を `ck_fe_request_decision` で強制する（要件 FE-RQ-003）。先例は V190 の `chk_retention_policy_activation`（:85-86）と `chk_privacy_export_package_sod`（:253）。
- **同じ開放単位（設定）に未決は 1 件だけ**を `pending_slot` の一意索引で強制する（NULL は一意性の対象外）。先例は V202 の `active_slot`（:28・:40）。H2 も同じ生成式で作る。
- `STALE`・`EXPIRED`・`CANCELLED` は system が終える（`decided_by` は NULL）。

### 2.5 `t_feature_enablement_setting`

```sql
CREATE TABLE t_feature_enablement_setting (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id     VARCHAR(100) NOT NULL,
  setting_key   VARCHAR(64)  NOT NULL,
  setting_value VARCHAR(100) NULL,      -- NULL は未設定
  version       INT          NOT NULL DEFAULT 0,
  request_id    BIGINT       NOT NULL,  -- 承認した申請
  approved_by   BIGINT       NOT NULL,
  approved_at   DATETIME(6)  NOT NULL,
  CONSTRAINT uk_fe_setting UNIQUE (tenant_id, setting_key),
  CONSTRAINT ck_fe_setting_key CHECK (setting_key IN ('nf02-customer-portal.visible-since')),
  CONSTRAINT fk_fe_setting_request FOREIGN KEY (request_id) REFERENCES t_feature_enablement_request (id)
);
```

### 2.6 `t_feature_sign_off`

```sql
CREATE TABLE t_feature_sign_off (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id         VARCHAR(100) NOT NULL,
  kind_code         VARCHAR(64)  NOT NULL,   -- 要件 §6 の種別 code（code の目録で検査する）
  reference         VARCHAR(128) NOT NULL,   -- 決定 ID・文書 ID・ticket 等
  signer_role       VARCHAR(64)  NOT NULL,   -- 種別が許す役割の code
  signer_name       VARCHAR(100) NULL,       -- 任意の個人データ（§6.6）
  signed_on         DATE         NOT NULL,   -- JST 業務日
  expires_on        DATE         NOT NULL,   -- JST 業務日。当日から無効
  structured_values TEXT         NULL,       -- 種別ごとの値（JSON。秘密値を置かない）
  stored_name       VARCHAR(255) NULL,       -- FileStorageService の保存名（消去後は NULL）
  original_name     VARCHAR(255) NULL,
  content_sha256    CHAR(64)     NOT NULL,
  size_bytes        BIGINT       NOT NULL,
  status            VARCHAR(20)  NOT NULL,
  active_slot       TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'CONFIRMED' THEN 1 ELSE NULL END) STORED,
  registered_by     BIGINT       NOT NULL,
  registered_at     DATETIME(6)  NOT NULL,
  confirmed_by      BIGINT       NULL,
  confirmed_at      DATETIME(6)  NULL,
  ended_by          BIGINT       NULL,       -- 却下・取消しの管理者
  ended_at          DATETIME(6)  NULL,
  end_reason        VARCHAR(64)  NULL,
  superseded_by_id  BIGINT       NULL,
  purge_after       DATE         NULL,
  purged_at         DATETIME(6)  NULL,
  version           INT          NOT NULL DEFAULT 0,
  CONSTRAINT uk_fe_sign_off_active UNIQUE (tenant_id, kind_code, active_slot),
  CONSTRAINT uk_fe_sign_off_stored_name UNIQUE (stored_name),
  CONSTRAINT ck_fe_sign_off_status CHECK (status IN ('REGISTERED','CONFIRMED','REJECTED','REVOKED','SUPERSEDED','EXPIRED','PURGED')),
  CONSTRAINT ck_fe_sign_off_separation CHECK (confirmed_by IS NULL OR confirmed_by <> registered_by),
  CONSTRAINT ck_fe_sign_off_confirmed CHECK (status NOT IN ('CONFIRMED','SUPERSEDED','EXPIRED')
      OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)),
  CONSTRAINT ck_fe_sign_off_dates CHECK (expires_on > signed_on),
  CONSTRAINT ck_fe_sign_off_purged CHECK (status <> 'PURGED'
      OR (stored_name IS NULL AND signer_name IS NULL AND purged_at IS NOT NULL)),
  INDEX idx_fe_sign_off_kind (tenant_id, kind_code, status),
  INDEX idx_fe_sign_off_purge (status, purge_after)
);
```

- 種別ごとに確認済み（`CONFIRMED`）は 1 件だけ（`active_slot`）。差替えは同じ transaction で旧い行を `SUPERSEDED` にしてから新しい行を `CONFIRMED` にする（順を逆にすると一意索引で失敗する）。
- `REVOKED` は確認済みの取消し、`REJECTED` は未確認の却下。`EXPIRED` は失効日を過ぎた後に定期の処理が付ける（有効かどうかの判定は `expires_on` で行い、`EXPIRED` の付与を待たない）。
- 決定の種別（NF07 の D-xx、NF06 の admission、NF05 の DG-05）は、行の `signed_on`・`expires_on` を決定の署名日・失効日とし、`structured_values` に残りの値を置く（二重に持たない）。

### 2.7 `t_feature_enablement_event`（追記専用）

```sql
CREATE TABLE t_feature_enablement_event (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id         VARCHAR(100)  NOT NULL,
  subject_type      VARCHAR(20)   NOT NULL,  -- FEATURE / REQUEST / SIGN_OFF / SETTING / SUBGATE / MASTER
  subject_key       VARCHAR(64)   NOT NULL,  -- 開放単位・種別・設定の key
  subject_id        BIGINT        NULL,
  event_type        VARCHAR(40)   NOT NULL,  -- §11.4
  from_state        VARCHAR(30)   NULL,
  to_state          VARCHAR(30)   NULL,
  reason_code       VARCHAR(64)   NULL,
  condition_summary VARCHAR(2000) NULL,
  actor_type        VARCHAR(10)   NOT NULL,  -- USER / SYSTEM
  actor_user_id     BIGINT        NULL,
  correlation_id    VARCHAR(64)   NULL,
  generation        BIGINT        NOT NULL,
  occurred_at       DATETIME(6)   NOT NULL,
  INDEX idx_fe_event_subject (tenant_id, subject_type, subject_key, id),
  INDEX idx_fe_event_time (tenant_id, occurred_at)
);
CREATE TRIGGER trg_fe_event_no_update BEFORE UPDATE ON t_feature_enablement_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_feature_enablement_event is append-only';
CREATE TRIGGER trg_fe_event_no_delete BEFORE DELETE ON t_feature_enablement_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_feature_enablement_event is append-only';
```

- mapper は `FeatureEnablementEventMapper extends AppendOnlyMapper<FeatureEnablementEvent>`（`common/mybatis/AppendOnlyMapper.java:31`。先例 `LifecycleDualEventMapper:15`）。H2 には trigger を作らず、mapper 層だけで拒否する（CLAUDE.md の Layering）。
- trigger が 2 件増える。RB §3.4 の trigger の件数と `FlywayProdFreshInstallMySqlTest` の期待値を同じ change で直す。
- 保存期間: 監査の記録として削除しない（`t_audit_log` と同じ扱い）。`reason`・`decision_reason` は自由記述のため、画面で「個人名・連絡先を書かない」と案内し、500 字に制限する。

### 2.8 menu・権限・data element の seed（V203 の後半）

- `m_menu`: `feature-enablement`（名称「機能の開放」、`path_prefix` `/feature-enablement`、`api_prefix` `/api/feature-enablement`）。`t_role_menu`: `管理者` だけ。**`t_permission_group_action` に行を足さない**（管理者は `role-admin` の `*`。管理者以外の group に `feature-enablement.*` を seed しない。CLAUDE.md）。
- `m_pii_data_element`: `FEATURE_SIGN_OFF_SIGNER_NAME`（`t_feature_sign_off.signer_name`）と `FEATURE_SIGN_OFF_DOCUMENT`（署名の文書）の 2 件を V198（:25-28）と同じ列で登録する（分類・owner・inventory 状態は UNKNOWN、保持期間は持たない。D-02 の署名で確定する）。`privacy-retention-dsar/pii-inventory.md` に DB-1xx の行を足す。

### 2.9 条件の評価結果は永続化しない（推奨）

- 毎回の評価の結果は memory（最後の評価の結果と時刻）にだけ置く。DB に残すのは、申請・承認の時の要約（`condition_summary`）と、自動閉鎖・副入口の閉鎖の理由の code（履歴）である。
- 理由: 定期の評価は 13 件×5 分ごとで、毎回書くと履歴が意味の無い行で埋まり、backup の静止中は書けない（静止の間も評価は動く）。監査で要るのは「何を根拠に開いたか」と「なぜ閉じたか」で、それは遷移の行に残る。

### 2.10 H2 の同期と版番号の連動

- `src/test/resources/sql/schema-feature-enablement-h2.sql`（新規）: V203 の表を `CREATE TABLE IF NOT EXISTS`・COMMENT なし・同じ生成式・CHECK・一意索引・FK で作る（trigger は作らない）。`application-test.yml` の `schema-locations` の V202 の変種の後へ足す。
- `src/test/resources/sql/engineer-schema-h2.sql`: event → sign_off → setting → state → request → generation の順に DROP して作る。
- 契約試験 `FeatureEnablementSchemaContractTest`: V203 と H2 の変種の列・生成式・CHECK の一致を固定する（先例 `BackupQuiesceSchemaContractTest`）。
- 版番号に連動するもの（共通規則の 5）: `LatestMigrationVersion`（`LATEST = "203"`）、`ProductionSchemaVersionGuardTest`、RB の件数（versioned・SQL・trigger）、`DeployRunbookMigrationCountTest`、`FlywayProdFreshInstallMySqlTest`。grep `202` で全てを探す。

## 3. 判定層

### 3.1 源の切替え

```java
/** 開放状態の源。hot path から呼ばれ、DB に往復しない。 */
public interface FeatureEnablementStateSource {
    /** 開放状態・総閘・有効期限・一時の閉鎖だけで見た開閉（親と実行時条件は FeatureSwitches が掛ける）。 */
    boolean isOpen(Feature feature);
    /** 承認で変える設定の値（例: nf02-customer-portal.visible-since）。 */
    Optional<String> setting(String key);
    /** 世代（property の源は 0 固定）。 */
    long generation();
}
```

- `PropertyFeatureEnablementStateSource`: 現在の `FeatureSwitchProperties.toggle(feature).isEnabled()`、`visible-since` の property、NF05 の property を返す。dev・test の既定。
- `DatabaseFeatureEnablementStateSource`: snapshot（§3.2）を返す。prod の既定で、prod ではこれ以外を起動時に拒否する（`FeatureEnablementProductionValidator`。§10）。
- 選択は `app.feature-enablement.source`（`properties` / `database`。`application.yml` の既定は `properties`、`application-prod.yml` は `database`）。

**段階的な合入の間の扱い**: 目録の各開放単位に「DB で管理するか」の印を持ち、`database` の源でも印の無い開放単位は property の値を返す（合成の源）。段階 A は容易な 7 件に印を付け、B〜D が残りに付け、段階 E で印を削除して全てを DB にする。go-live は段階 E の後に限る（D-1: go-live の前に全てを終える）。

### 3.2 snapshot

```java
record FeatureEnablementSnapshot(long generation, Instant loadedAt, boolean masterAllowed,
        Map<Feature, UnitView> units,               // 開放状態と openUntil
        Map<String, String> settings,               // 設定
        Map<String, ConfirmedSignOff> signOffs) {}  // 確認済みの署名（種別 → 参照・期間・構造化された値）
record UnitView(EnablementState state, LocalDate openUntil, boolean restartPending) {}
```

- `openUntil` は、その開放単位が要する署名（目録）の最も早い `expires_on`（排他）。hot path はこれと JST の今日を比べるだけで、署名の失効を即時に反映する（scheduler が止まっていても効く）。
- `FeatureEnablementSnapshotLoader.load(tenant)` は 4 回の照会（世代、開放状態、設定、確認済みの署名）で作る。
- `FeatureEnablementSnapshotHolder` は `AtomicReference` で持つ。読取りは lock を取らない（`SystemConfigServiceImpl.getString` の `synchronized` を使わない理由）。

`DatabaseFeatureEnablementStateSource.isOpen(f)`:

```
s = holder.get()
if s == null or now - s.loadedAt > maxStaleness: return false          // fail-closed（§12）
if !s.masterAllowed: return false
u = s.units[f]; if u == null or u.state != OPEN: return false          // OPEN_PENDING_RESTART は閉
if !(today(JST) < u.openUntil): return false
if overlay.contains(f): return false                                   // 一時の閉鎖
return true
```

### 3.3 反映（同じ instance・他の instance・古さ）

- **同じ instance**: 書込みの transaction に `TransactionSynchronization.afterCommit` を登録し、commit の後に snapshot を読み直して差し替える（要求の応答より前）。読み直しに失敗したら、変更した開放単位を一時の閉鎖にし、次の poll に任せる。
- **他の instance**: `FeatureEnablementSnapshotRefresher` が専用の thread で `app.feature-enablement.refresh-interval`（既定 5 秒）ごとに世代の 1 行を読み、変わっていれば snapshot を読み直す。`@Scheduled` を使わない（`app.scheduling.enabled=false` で scheduler を一括で止めた時に、全ての開放単位が古さで閉じないため。読むだけで書かない）。`SchedulingDisabledDrainTest` の既知の一覧に理由つきで足す（RB-QUIESCE の `BackupQuiesceTicker` と同じ扱い）。
- **古さ**: 最後に読めた時刻から `app.feature-enablement.max-staleness`（既定 60 秒）を超えたら、全ての開放単位を閉とする。DB の状態は変えず、読めれば戻る。
- 多 instance の版の失効は世代で行う（TTL ではなく版。TTL は古さの上限としてだけ使う）。現在は単一 instance（SRE-5）。

### 3.4 `FeatureSwitches` の変更

- `isConfiguredOn(feature)`（:90-97）を `source.isOpen(feature)` に置き換える。`isEnabled`（:61-69）の親との論理積と実行時条件はそのまま。
- `new FeatureSwitches(FeatureSwitchProperties)` は property の源で包んで残す（`new` で作る既存の試験のため）。
- `logStates`（:40-55）を、起動時に加えて snapshot の差替えで実効値が変わったたびに出す（`[機能スイッチ] 状態: …`。並びと形式は現行のまま。NF05 の 2 件を足す）。
- `Feature` に `NF05_INBOUND`・`NF05_OUTBOUND` を足す（prefix・menu は持たない。`FeatureSwitchFilter` は何も遮断せず、inbound の 404 は既存の `ExternalApiDisabledFilter` が行う）。`propertyName()` は `integration.hub.public-api.enabled`・`integration.hub.external-transport.enabled`、`environmentVariable()` は現在の名前を返す（旧い変数の拒否に使う）。`FeatureSwitchProperties.toggle` は NF05 の property を返す。`FeatureSwitchRouteCoverageTest`・`FeatureSwitchDefaultsTest` の一覧を直す。

### 3.5 `FeatureRuntimeCondition` の拡張

- 現在の `satisfied()` に加えて、既定の method `Optional<String> unsatisfiedReason()` を足す（画面と log の理由の code）。既存の実装（`PrivacyDecisionWindowMonitor`・`PrivacyDsarDecisionWindowCondition`）はそのまま動く。
- 一時の閉鎖（overlay）を `FeatureEnablementOverlay`（`FeatureRuntimeCondition` の実装。開放単位ごとの理由の集合）として実装する: 評価器の例外（`UNVERIFIABLE`）、自動閉鎖を決めたが DB に書けていない間、NF05 の `ApplicationReadyEvent` での法人の未解決。

### 3.6 hot path の性能

- filter・ControllerAdvice の 1 回の判定は、`AtomicReference` の読取り・Map の参照・日付の比較・overlay の集合の参照だけで、DB の照会は 0 回（要件 FE-AC-006-02 を照会の数で固定する）。
- JST の今日は `LocalDate.now(clock.withZone(Asia/Tokyo))`。

## 4. 条件の評価器

### 4.1 目録（`FeatureEnablementCatalog`）

code の正本。開放単位ごとに次を持つ（要件 §6）:

- 親、`restartRequired`（NF05 だけ true）、`privacyUnit`（NF07 の 2 件。§11.3）。
- 要る署名の種別の一覧。種別ごとに: 署名者の役割（許す値）、有効期限の上限（既定 366 日）、構造化された値の schema と検査（§6.3）、他の開放単位との共用、条件付き（例: `NF02_PORTAL_INVENTORY` は公開範囲を設定する時だけ）。
- 要る鍵の一覧（名前と、有無・形式を判定する部品）。値は持たない。
- 副入口（NF07 保持・保全の 3 件）と、その条件。

### 4.2 interface

```java
public interface FeatureConditionEvaluator {
    /** 受け持つ開放単位。 */
    Set<Feature> features();
    /** 条件を評価する。DB へは書かない。例外は呼出し側が UNVERIFIABLE にする。 */
    List<FeatureConditionCheck> evaluate(Feature feature, FeatureEvaluationContext context);
}

public record FeatureConditionCheck(String code, Category category, Scope scope, Status status, boolean temporal,
                                    LocalDate validUntil, List<String> detailCodes) {
    public enum Category { MASTER, PARENT, AUTO, SIGN_OFF, SECRET, SETTING, ADVISORY }
    /** CONTINUOUS: 開いている間も評価し、外れたら自動閉鎖する。OPEN_ONLY: 申請・承認の時だけ開閉を左右する。 */
    public enum Scope { CONTINUOUS, OPEN_ONLY }
    public enum Status { SATISFIED, UNSATISFIED, UNVERIFIABLE, NOT_APPLICABLE }
}

public record FeatureEvaluationContext(String tenantId, LocalDate businessDate, Clock clock,
        FeatureEnablementSnapshot snapshot, Purpose purpose, Set<Feature> openAfterChange) {
    public enum Purpose { STARTUP, PERIODIC, CHANGE, REQUEST, APPROVAL, DISPLAY }
}
```

- `code` と `detailCodes` は固定の文字列（既存の違反 code をそのまま使う。例: `decisionBinding.d01.expiresOn.expired`、`privacyAdmins.insufficient[current=1;required=2]`、`dsar.dueDaysUnset`、`state=MISSING`）。値・digest・username・例外の本文を含めない（`PrivacyProductionConfigurationValidator` の message の規則と同じ）。
- `ADVISORY` は警告だけで、開閉を左右しない（NF10 の scheduler 無効）。NF02 の暦は `ADVISORY` ではなく `OPEN_ONLY` の check で、期限の接近と喪失は §7.4 の告警になる。
- `Scope.OPEN_ONLY` の check は、`Purpose.REQUEST`・`APPROVAL` の時だけ成否を開閉の根拠にする。`STARTUP`・`PERIODIC`・`CHANGE` の評価で `UNSATISFIED`・`UNVERIFIABLE` になっても自動閉鎖・overlay にせず、警告（§7.4）として扱う。現在 `OPEN_ONLY` は NF02 の全社共通カレンダーだけ（§18 J-16。決定済み）。他の check は全て `CONTINUOUS`。
- `openAfterChange` は、承認しようとしている開放単位を含めた「変更の後に開いている開放単位」。NF07 の capability の判定（保持・保全が開いている日に DSAR を足す等）に使う。
- `FeatureConditionRegistry` は全ての評価器を集め、開放単位ごとに `MASTER`・`PARENT`・`SIGN_OFF` の共通の評価器（§4.3）と固有の評価器の結果を並べる。評価器の例外は、その評価器の全ての check を `UNVERIFIABLE`（code `evaluator.unverifiable[<評価器の名前>]`）にする。固有の評価器を要する開放単位（NF01 二者確認・NF02・NF05・NF06・NF07）に評価器が無ければ `evaluator.missing` の `UNVERIFIABLE` にする。

### 4.3 共通の評価器

- `MasterSwitchConditionEvaluator`: 総閘（§10）。
- `ParentConditionEvaluator`: 親の開放状態が `OPEN`（`openAfterChange` を含める）。
- `SignOffConditionEvaluator`: 目録の要る種別ごとに、確認済みの署名があり、`signed_on ≤ 今日 < expires_on`。`validUntil` は `expires_on`。署名日前・失効は `temporal=true`。条件付きの種別は、条件（例: 公開範囲の値）を snapshot から判定する。
- `SecretPresenceConditionEvaluator`: 目録の鍵の有無と形式（既存の部品の判定を呼ぶ。値は読まない・返さない）。

### 4.4 起動時検査の判定の再利用（PR #130 の形）

PR #130 は起動時検査（`ProductionSecurityConfigurationValidator`・`PrivacyProductionConfigurationValidator`）に `AdministratorStartupConditions` を実装させ、`validate` と同じ私有の method を呼ばせた（`PrivacyProductionConfigurationValidator.checkAdministratorConditions` :184-194）。本機能も同じ形を取る:

- 起動時検査の class に `FeatureConditionEvaluator` を実装させ、`validate` が使っている検査の method をそのまま呼ぶ。条件の写しを作らない。
- 起動時の `validate` は、prod でも機能の条件では例外を投げず、評価の結果を log に出すだけにする（起動時の評価と自動閉鎖は `FeatureEnablementStartupEvaluator` が行う。§7.1）。機能と関係しない検査は従来どおり例外にする。
- 検査の class が `@Profile("prod")` の場合（`PrivacyProductionConfigurationValidator`・`ImportAdmissionStartupValidator`・`ImportRowReferenceKeyStartupValidator`・`PrivacyTrustStartupValidator`）、評価器も prod にだけある。dev・test で DB の源を使う試験は、その開放単位を「評価器が無い」として扱うか、試験用の評価器を与える（§16）。判定の method が profile に依らない静的な部品（`ImportAdmissionApproval.evaluate`、`PrivacyDecisionBinding.evaluate`、`LifecycleDualConfirmationReleaseGate.acceptable`、`IntegrationHubProductionEnablementPolicy` の静的な method）にある場合は、profile に依らない評価器からそれを呼ぶ。

### 4.5 条件の指紋

- 申請の時と承認の時に、開放単位ごとに次の正規形の SHA-256 を計算する（UTF-8、LF で終わる行）:
  - 1 行目 `fe-condition-v1`、2 行目 `unit=<key>`、`type=<OPEN|SETTING_CHANGE>`、`value=<提案の値か空>`、要る署名の種別の昇順に `signoff.<kind>=<id>:<version>`、親の `parent=<key>:<state>`。
- 自動検査の結果（人数等）は指紋に入れない（成否は承認の時に評価し直すため。人数の増減で陳腐化させない）。
- 承認の時の指紋が申請の時と違えば、申請を `STALE` で終える（要件 FE-RQ-003）。

## 5. 申請・承認・却下・取下げ・閉鎖

### 5.1 lock の順と transaction

- 全ての書込みは次の順で lock する: (1) 世代の行、(2) 開放状態の行（親→子、同じ階層は key の昇順）、(3) 申請の行、(4) 署名の行（id の昇順）、(5) 設定の行、(6) NF05 outbound の barrier の行（`m_system_config` の `integration.hub.outbound.runtime.v1`）。(1) で tenant の中の変更が直列になるため、本機能の表どうしでは deadlock しない。
- 分離レベルは READ COMMITTED（条件の評価で他の表の最新の commit を読むため。`WriterIsolationPolicyTest` の `READ_COMMITTED_WRITERS` に足す）。
- 監査は同じ transaction の `recordDomainEventRequired`（`AuditLogServiceImpl:160-176`）。失敗すれば遷移ごと rollback する。
- 通知は commit の後に best-effort で送る（失敗しても遷移は戻さない）。

### 5.2 申請（`POST /api/feature-enablement/requests`）

1. 行為者の検査（§11.2。NF07 は §11.3）。総閘（閉なら 409 `error.featureEnablement.masterClosed`）。
2. lock（§5.1）。開放状態が `CLOSED`・`AUTO_CLOSED`・`MANUALLY_CLOSED` でなければ 409。
3. 条件を `Purpose.REQUEST` で評価する。全ての条件（`ADVISORY` を除く）が `SATISFIED`（`NOT_APPLICABLE` を含む）でなければ 409 と理由の code。
4. 申請の行を `PENDING` で作る（`pending_slot` の一意索引で 2 件目は失敗 → 409）。指紋・要約・期限（既定 7 日）を書く。履歴 `REQUESTED`・監査 `FEATURE_OPEN_REQUESTED`。
5. commit の後、申請者以外の有効な管理者（NF07 は privacy-admin）へ通知する。

### 5.3 承認（`POST /requests/{id}/approve`、本文 `expectedVersion`）

1. 行為者の検査。承認者≠申請者（403）。総閘。
2. lock（§5.1）。申請が `PENDING` で期限内、version が一致（違えば 409）。
3. 条件を `Purpose.APPROVAL` で評価し直す（`openAfterChange` に対象を含める）。成り立たなければ 409（申請は未決のまま）。
4. 指紋を計算し、申請の指紋と違えば申請を `STALE` にして 409 `error.featureEnablement.requestStale`。
5. 開放状態を `OPEN`（NF05 は `OPEN_PENDING_RESTART`）にし、`opened_by`・`approved_by`・`open_request_id`・`opened_at` を書く。申請を `APPROVED`。世代を上げる。履歴 `APPROVED`（要約つき）・監査 `FEATURE_OPEN_APPROVED`。
6. commit の後: snapshot の差替え、通知、INFO log。

### 5.4 却下・取下げ・期限切れ

- 却下: 申請者以外の有効な管理者。理由が必須。`REJECTED`。
- 取下げ: 申請者だけ。`WITHDRAWN`。
- 期限切れ: 定期の処理が `expires_at` を過ぎた `PENDING` を `EXPIRED` にする。
- どれも開放状態は変えない。

### 5.5 閉鎖（`POST /units/{key}/close`）

- 有効な管理者 1 名で行える（閉じる方向は安全側。§18 J-19）。理由が必須。break-glass・bootstrap の session は不可（§11.2）。
- 開放状態を `MANUALLY_CLOSED` にし、開いている子を `AUTO_CLOSED`（`PARENT_CLOSED`）にし（§18 J-18）、未決の申請があれば `CANCELLED` にする。NF05 outbound は barrier の行を OFF に書き直す（§9.3）。
- NF01 二者確認の閉鎖では、確認の dialog に「二者確認を含む有効な template の件数」（FS 4.9.5 の (3)）を出す。

### 5.6 設定の変更（`SETTING_CHANGE`）

- NF02 の公開範囲だけ（要件 FE-RQ-010）。申請に値（または未設定）を持たせ、承認で `t_feature_enablement_setting` を書き換える。
- 条件: 確認済みの署名 `NF02_PORTAL_VISIBLE_SINCE_DECISION` の構造化された値 `visibleSince` が申請の値と一致すること。値を設定する場合は `NF02_PORTAL_INVENTORY` も要る。値の形式は `PortalServiceRequestVisibilityPolicy` の解釈と同じ（offset 付き ISO-8601）。
- 指紋に申請の値を含める（§4.5）。

## 6. 署名の登録と確認

### 6.1 文書の保存

- 新しい `FileKind` `FEATURE_SIGN_OFF`（拡張子 pdf だけ、上限 20 MB。`FileKind.java:21` の enum に足す）。PDF の magic byte・末尾の検査は既存の pdf の分岐を使う（`FileKind.java:107-131`）。
- 汎用の upload を拒否する: `FileKind` に `isDedicatedUploadOnly()` を足して `FEATURE_SIGN_OFF` で true を返し、`FileApiController.upload`（:36-44）が `isServerGeneratedOnly()` と同じく 400 にする（現在は server 生成の種別だけを拒否している）。
- 登録の API は multipart（文書と JSON）を受け、`FileStorageService.store(byte[], originalName, FEATURE_SIGN_OFF)`（`FileStorageService.java:21`）で保存する。scan が `CLEAN` でなければ `error.file.scanRejected` で登録を拒否する（署名の行は作らない。文書は quarantine に残り、既存の再 scan の対象になる）。
- SHA-256 は upload の経路が計算しないため（`FileStorageServiceImpl` は hash を持たない）、本機能が bytes から計算して `content_sha256` に保存する。取得は専用の経路（`GET /sign-offs/{id}/document`）で `FileStorageService.load` の後に計算し直し、大きさと hash が一致しなければ 409（先例 `PrivacyExportPackageService.readVerified` :880-901）。取得も監査する。

### 6.2 file の 2 箇所の登録（CLAUDE.md）

1. `FeatureSignOffFileReferenceProvider implements FileReferenceProvider`（`service/FileReferenceProvider.java:18`）: `PURGED` 以外の行の `stored_name` を claim する。
2. `FileScopeValidationService.assertDownloadAllowed`（:255）の前置きの拒否に `assertFeatureSignOffNotAccessibleThroughGenericFilePath` を足す（先例 `assertPrivacyPackageNotAccessibleThroughGenericFilePath` :257・:519）。署名の表に保存名があれば、role に関係なく汎用の経路を 403 にする。専用の経路だけが読む。

### 6.3 種別ごとの検査（登録の時と評価の時）

| 種別 | 参照・構造化された値の検査（現在の検査をそのまま使う） |
|---|---|
| `NF01_DUAL_PRIVACY` | 参照が `LifecycleDualConfirmationReleaseGate.acceptable`（:86-97） |
| `NF07_D01`・`NF07_D02`・`NF07_D03`・`NF07_D05`・`NF07_D06`・`NF07_D09` | 決定 ID・日付の規則（`PrivacyDecisionBinding` の `readDecision` :407-427 等。NF08 と同じ規則）。D-09 の構造化された値: `tenantId`（設定の tenant と一致）・`capabilities`（`retention`/`dsar`）・`approvalDigest`（`PrivacyDecisionBinding.computeDigest` :305-311 の値と一致）。D-06: `dueDays`（1〜366）・`packageMaxAge`（1 時間〜366 日） |
| `NF06_ADMISSION` | 構造化された値（`decisionId`・`tenantId`・`sourceCodes`・`approvalDigest`）と行の日付を `ImportAdmissionApprovalProperties` に写して `ImportAdmissionApproval.evaluate`（:78）。構造的な不足（`structuralViolations` :106）があれば拒否 |
| `NF05_DG05_IN`・`NF05_DG05_OUT` | 決定 ID が `DG-05-IN-[0-9]{8}-[0-9]{2}`・`DG-05-OUT-[0-9]{8}-[0-9]{2}`（`IntegrationHubProductionEnablementPolicy:27,29`）。digest は小文字 hex 64 桁。登録の時は形式だけを見る（内容との一致は、部署の設定の topology と合わせて評価の時に `IntegrationHubApprovalDigest.canonicalInbound`・`canonicalOutbound` で判定する） |
| `NF02_PORTAL_VISIBLE_SINCE_DECISION` | `visibleSince` が空か offset 付き ISO-8601 |
| その他 | 参照が英数字で始まる 4〜128 文字（`._:/#-`）。placeholder の語を含まない（`LifecycleDualConfirmationReleaseGate` の placeholder の規則を共通の部品に移して使う） |

- 署名者の役割は、種別が許す値（例: `NF07_D01` は `PRIVACY_OWNER`）からだけ選ばせる。
- 有効期限の上限を種別ごとに検査する（既定 366 日。§18 J-08）。NF07 の決定は既存の 366 日の規則（`PrivacyDecisionBinding.MAX_VALIDITY_DAYS` :48）。

### 6.4 登録にも二人の確認を求める理由（推奨）

- 署名の登録は、現在の「署名の後に人が環境変数へ転記し、digest を二人がそれぞれ正規形から計算して照合する」（FS 4.7.4 の3、4.8.2 の #1、DG-05 草案 §1.1）の置き換えである。二人の照合を落とさないため。
- 開いている開放単位の署名を差し替える時は、開放の承認を経ない（D-5: 差替えに再起動も開き直しも要らない）。登録を 1 名でできると、1 名で有効期限を延ばしたり内容を変えたりできてしまう。
- 確認者は文書と、system が正規形から計算した digest（画面に先頭 16 文字を出す）を、文書の記載と照合する。

### 6.5 失効・差替え・取消し

- 失効: `expires_on` の当日（JST 0:00）から無効。hot path は snapshot の `openUntil` で即時に閉じ、定期の評価が `AUTO_CLOSED`（`SIGN_OFF_EXPIRED`）にし、署名を `EXPIRED` にする。
- 失効の接近: `feature-enablement.expiry-warning-days`（`m_system_config`。`/system-config` の `SCHEMAS` に足す。既定 30、1〜366）日前から毎日 1 回、管理者へ通知し WARN を出す。`privacy.approval.expiry-warning-days`（`PRIVACY_APPROVAL_EXPIRY_WARNING_DAYS`）の役割を引き継ぎ、旧い変数として廃止する。
- 差替え: 同じ種別の新しい署名を確認すると、旧い行を `SUPERSEDED`、新しい行を `CONFIRMED` にし、それを要する開いている開放単位を `Purpose.CHANGE` で評価し直す。成り立てば開いたまま、成り立たなければ同じ transaction で `AUTO_CLOSED`（`SIGN_OFF_REPLACED_INVALID`）。
- 取消し: 確認済みの署名を有効な管理者 1 名で `REVOKED` にできる（理由が必須）。それを要する開いている開放単位を同じ transaction で `AUTO_CLOSED`（`SIGN_OFF_REVOKED`）。
- 却下: 未確認の署名を登録者以外が `REJECTED` にする。登録者本人は取り下げられる（`REJECTED`、`end_reason=WITHDRAWN_BY_REGISTRANT`）。

### 6.6 個人データと保存期間

- 氏名は任意。一覧の API は氏名を返さず、詳細の API だけが返す。監査・履歴・log に氏名を書かない。
- `SUPERSEDED`・`EXPIRED`・`REVOKED`・`REJECTED` になった時に `purge_after` = その日＋3 年（§18 J-14）を書く。日次の `FeatureSignOffPurgeScheduler`（`@SchedulerLock`、`void`）が期日を過ぎた行の氏名を NULL にし、文書の実体と metadata を削除し、`PURGED` にする（SHA-256・参照・日付・役割は残す）。
- 消去の経路は `PrivacyHoldPathGuard` を通す（CLAUDE.md の「personal data を物理削除・purge する新しい経路は guard に配線する」）。batch は実行ごとに 1 回 gate を照会し、hold の対象の署名者・文書は消さない。RB §4.4 の経路の一覧に D17 として足す。
- data element の登録は §2.8。

## 7. 条件の継続評価

### 7.1 起動時

- `FeatureEnablementStartupEvaluator`（`SmartLifecycle`。web server の lifecycle より前の phase）: snapshot を読み、開いている開放単位と再起動待ちを `Purpose.STARTUP` で評価する。成り立たなければ `AUTO_CLOSED`（理由の code）、総閘が閉なら全てを `AUTO_CLOSED`（`MASTER_SWITCH_CLOSED`）。NF05 の再起動待ちの反映は §9.2。
- DB に書けない（backup の静止の最中の起動等）場合は overlay で閉じ、定期の評価に任せる。
- 起動の log に `[機能の開放] 起動時の評価: NF01=OPEN, NF02=AUTO_CLOSED(sign-off.NF02_DG02.expired), …` を出す（code だけ）。

### 7.2 定期

- `FeatureEnablementConditionScheduler`:
  - `evaluateAll()`: `@Scheduled(fixedDelayString = "${app.feature-enablement.evaluation-interval:PT5M}")`、`@SchedulerLock(name = "featureEnablementEvaluation")`、`void`。開いている開放単位と副入口を評価する。
  - `evaluateAtBusinessDayStart()`: `@Scheduled(cron = "5 0 0 * * *", zone = "Asia/Tokyo")`、`@SchedulerLock(name = "featureEnablementDayStart")`、`void`。失効の反映・申請の期限切れ・失効の接近の通知・開放の時だけの条件（NF02 の暦）の期限の接近と喪失の告警（§7.4）。
  - どちらも `FeatureSwitchRouteCoverageTest` の `UNGATED_SCHEDULERS` に理由つきで登録する（機能スイッチで止めない）。
- scheduler が無効（`app.scheduling.enabled=false`）の間は、期限による閉鎖（hot path）と変更時の評価だけが働く（FS 4.1 に書く）。

### 7.3 照会の数（定期の評価 1 回あたりの目安）

| 開放単位 | 照会 |
|---|---|
| 共通（総閘・親・署名） | snapshot から（0） |
| NF02 | 暦 1（日の始まりの告警だけ。閉鎖の根拠にしない） |
| NF01 二者確認 | 証跡種別 2 |
| NF06 | 権限の行 1、法人 1 |
| NF07（保持・保全・DSAR の両方を開いた場合） | group 1、管理者 2、許可行 1、provider 0（memory）、Journal の権限（10 分に 1 回まで。Journal への 1〜2）、trust（memory の文書） |
| NF05 | barrier の行 1、法人の未解決 1 |

合計は 5 分ごとに約 15 回で、接続の pool（20）への影響は無視できる。画面の表示は最後の評価の結果を出し、利用者の「再評価」で 1 件だけ評価する（全件をその場で評価しない）。

### 7.4 自動閉鎖の処理

```
check = registry.evaluate(f, ctx).filter(scope == CONTINUOUS)   // OPEN_ONLY は閉鎖の根拠にしない（§4.2）
if any UNSATISFIED:
    overlay.close(f, reason)                       // 直ちに実効値を閉じる
    tx { lock; 状態が OPEN/OPEN_PENDING_RESTART なら AUTO_CLOSED(reason); 子も AUTO_CLOSED(PARENT_CLOSED);
         NF05 outbound なら barrier を OFF; 履歴・監査; 世代 }
    commit 後: snapshot 差替え → overlay を外す、通知、WARN errorCode=FEATURE_AUTO_CLOSED
elif any UNVERIFIABLE:
    overlay.close(f, EVALUATION_UNVERIFIABLE)
    連続回数 += 1; 既定 3 回で上と同じ遷移（reason=EVALUATION_UNVERIFIABLE）     // §18 J-10
else:
    overlay.open(f); 連続回数 = 0
```

- 連続回数は memory に持つ（再起動で 0 に戻る。安全側には overlay が働く）。
- `OPEN_ONLY` の check（NF02 の全社共通カレンダー）が開いている間に外れても、NF02 と子は開いたままにする。日の始まりの評価で、暦の有効期間の終わりの既定 30 日前（`feature-enablement.expiry-warning-days` と同じ日数）から毎日 1 回、監査 `FEATURE_OPEN_CONDITION_EXPIRING`（`recordDomainEventRequired`）・管理者への通知・WARN `errorCode=FEATURE_OPEN_CONDITION_EXPIRING` を出し、暦が切れた後も同じく毎日告警する（`FEATURE_OPEN_CONDITION_LOST`）。暦が無い間の SLA の計算は現行どおり（`ServiceSlaCalculator.isNonWorkingDay` :314-329 が WARN `missing_calendar` を出し、土日だけを休日として数える）。
- 副入口は DB の状態を持たない。成否が変わったら履歴 `SUBGATE_CLOSED`・`SUBGATE_OPENED` と通知を出す（要求ごとの gate は今のまま 404・409 を返す）。

### 7.5 通知・metric・log

- 通知（`NotificationService.publishToUser` を有効な管理者全員へ。NF07 は privacy-admin を含める。dedupe key は開放単位・事象・業務日）: 申請（承認の依頼）、承認・却下、自動閉鎖、総閘による閉鎖、失効の接近、開放の時だけの条件の期限の接近と喪失（NF02 の暦）、副入口の閉鎖、再起動待ち。
- metric: `ses_feature_enablement_open{feature}`（0/1。実効値）、`ses_feature_enablement_state{feature,state}`、`ses_feature_enablement_auto_closed_total{feature,reason}`、`ses_feature_enablement_snapshot_age_seconds`、`ses_feature_enablement_master_allowed`、`ses_feature_enablement_sign_off_days_remaining{feature}`。tag に ID・氏名・値を載せない。
- log: WARN `errorCode=FEATURE_AUTO_CLOSED feature=<key> reason=<code>`、ERROR `errorCode=FEATURE_ENABLEMENT_MASTER_CLOSED`、WARN `errorCode=FEATURE_ENABLEMENT_SNAPSHOT_STALE`。CloudWatch の metric filter は SRE-8 と同じ形で置ける（RB に例を書く）。

## 8. 各機能の改修点

### 8.1 容易な 7 件（NF01・NF02・NF02 顧客ポータル・NF03・NF03 資格番号・NF04・NF10）

- 開閉の判定は全て `FeatureSwitches` 経由のため、源の差替え（§3）だけで実行時に開閉できる。各機能の code は変えない。
- 目録の行（署名の種別・親）と、固有の評価器が要るもの:
  - NF02: `WorkCalendarConditionEvaluator`（本日を含む当年度の全社共通の暦。`ServiceSlaCalculator.findCalendarForScope` :366-400 の 4 段目と同じ条件で `WorkCalendarMapper` を 1 回照会。check は `Scope.OPEN_ONLY`: 申請・承認の時だけ必須で、開いた後は継続の条件にせず NF02 を自動閉鎖しない。期限の接近と喪失は §7.4 の告警。§18 J-16 決定済み）。
  - NF10: `SchedulingConditionEvaluator`（`app.scheduling.enabled` が true でなければ `ADVISORY`）。
- 閉じた時の挙動は現在の OFF と同じ（filter の 404、menu の非表示、scheduler の no-op、子の 400。NF04 は `common.js` が SW の登録を解く。FS 4.4）。

### 8.2 NF01 二者確認

- `LifecycleDualConfirmationReleaseGate`（`@Profile("prod")`、`@PostConstruct validate` :68-80）: 例外で起動を止める処理を削除する。`acceptable` は profile に依らない `LifecycleDualConfirmationEnablementConditions`（評価器）から使う。property の `privacy-sign-off-ref` は dev・test の property の源でだけ使う。
- 評価器: (a) 署名 `NF01_DUAL_PRIVACY` の参照が `acceptable`。(b) 証跡種別の許可値の外の行が 0 件（FS 4.9.5 の (1)(2)）。`m_lifecycle_template_task`・`t_lifecycle_task` の mapper に件数の method を足す。照合順序で揺れを同一視しないよう、MySQL では `CAST(... AS BINARY)` と同じ比較を行う。H2 で同じ SQL が動かない場合は、`HEX` の値の一覧を Java で許可値と照合する（実装の着手時に両方で確かめる）。
- 閉鎖の dialog の警告（§5.5）のため、有効な DUAL template の件数（FS 4.9.5 の (3)）を詳細の API で返す。
- 実行時の gate（`LifecycleDualConfirmationCoordinator:660`、`LifecycleTemplateServiceImpl:316`）は変えない。

### 8.3 NF06

- `ImportAdmissionApprovalSource`（新設）: 現在の `ImportAdmissionApprovalProperties`（`@ConfigurationProperties("app.import-center.admission")`）を返す property の実装と、確認済みの署名 `NF06_ADMISSION` から `ImportAdmissionApprovalProperties` の値を作る DB の実装。`ApprovedSourceImportAdmissionPort`（:121）が毎回この源を読む。
- `ImportAdmissionStartupValidator`（:52-77）・`ImportRowReferenceKeyStartupValidator`（:38-55）: 例外で起動を止める処理を削除し、`ImportCenterEnablementConditions`（profile に依らない評価器）が同じ判定を呼ぶ: `ImportAdmissionApproval.evaluate(source.current(), businessDate)` の構造的な不足と期間、`ImportSourceRowReference.state()`（:96）、V193 の権限の行（`PermissionGroupActionMapper` に RB §3.4 の V193 の照会と同じ件数の method を足す）、単一法人（`LegalEntityContextService` と同じ条件の件数）。
- `ImportJdbcReconnectGuard` は変えない（常時）。
- 期間の外の扱いが変わる: 現在は「起動して upload だけ 403」、本機能の後は「NF06 を自動閉鎖（404）」（D-4。§18 J-09。決定済み（2026-11-22、利用者））。

### 8.4 NF02 の公開範囲

- `PortalServiceRequestVisibilityPolicy`: `final` の `visibleSince`（:45-52）をやめ、`FeatureEnablementStateSource.setting("nf02-customer-portal.visible-since")` を毎回解釈する（解釈の結果は値の文字列を key に 1 件だけ cache する）。構築時の解釈の失敗で起動を止めるのは property の源の時だけ（dev・test の現状を保つ）。DB の源では不正な値は申請の時に拒否され、DB の値が壊れていれば portal 起票だけを出す（fail-closed）。
- `PORTAL_ORIGINATED_ONLY` の既定（:41-43）はそのまま。呼出し元（`ServiceRequestServiceImpl`・`ServiceRequestAttachmentService`・`FileScopeValidationService`・`ServiceRequestDto`）は変えない。

### 8.5 NF07（保持・保全・DSAR・副入口）

1. **承認の値の源** `PrivacyApprovalSource`（新設）: `tenantId()`・`capabilities()`・`approvalDigest()`・`reference(Decision)`・`dsarDueDays()`・`dsarPackageMaxAge()`。property の実装は現在の `PrivacyApprovalProperties`（`@ConfigurationProperties("privacy.approval")` :21-93）と `PrivacyFeatureProperties.Dsar`（:59-70）を返し、DB の実装は確認済みの署名 `NF07_D01`〜`NF07_D09` から返す。`PrivacyDecisionBinding.evaluate`（:145）・`evaluateStandalone`（:202）に源を受ける overload を足し、呼出し元（`PrivacyProductionConfigurationValidator`、`PrivacyDecisionWindowMonitor`、`PrivacyHoldDecisions.evaluate` :47-63・`releaseReadiness` :69-83、`PrivacyDsarDecisions` :49-88、`PrivacyPolicyRegistrationService.readiness` :134-150）を源へ切り替える。どれも呼出しごとに読むため、署名の差替えは再起動なしに効く。
2. **5 箇所の直接の読取り**:
   - `PrivacyProductionConfigurationValidator.enabledCapabilities`（:303-315）と `PrivacyDecisionWindowMonitor.enabledCapabilities`（:211-223）: `FeatureEnablementStateSource.isOpen(NF07_*)` から作る（`FeatureSwitches.isEnabled` を呼ぶと Monitor 自身の実行時条件で再帰するため、源を直接読む）。評価の時は `openAfterChange` を使う。
   - `PrivacyTrustStartupValidator.validate`（:60-61）: flag を見ずに、KMS の source を設定した prod では常に `inspect` する（下の 4）。
   - `PrivacyJournalStartupVerifier.holdStartRequired`（:142-146）: 下の 3。
   - `PrivacyProductionConfiguration.retentionSourceEnabled`（:77-83）: `FeatureSwitches.isEnabled(NF07_RETENTION)` に置き換える（現在は生の flag と Monitor を自前で掛けており、`FeatureSwitches` と同じ判定の写しになっている）。`PrivacyPolicyEnforcementConfiguration:47`（NF08 の LINK）も同じ supplier を使うため意味は変わらない。
   - 静的な試験 `PrivacyFeatureFlagReadGuardTest` で、`getRetention().isEnabled()`・`getDsar().isEnabled()` を呼べるのを property の源だけに固定する。
3. **Journal**（`PrivacyJournalStartupVerifier`）: prod で Journal を設定した場合は flag に関係なく appender の権限を要求して検査する（`verify` :148-165 の `appenderRequired` を prod では true に固定）。結果を `volatile` の現在値に置き、`reverify()` を足す（承認の時と定期の評価で呼ぶ。間隔の下限 `app.feature-enablement.journal-reverify-interval`、既定 10 分）。`enforce`（:171-192）は prod でも例外にせず WARN にし、結果を保持・保全の条件（要件 §6.11 の (e)）に使う。`PrivacyHoldJournal`（:108・:180-200）は起動時の結果ではなく現在値を読む。Journal の接続の設定は起動時に要る（`PrivacyJournalConnections:93`）ため、設定の追加は部署の設定の変更と再起動を要する（基盤の準備。要件 §6.11 の鍵）。
4. **trust**（`PrivacyTrustStartupValidator`）: KMS の source（`PrivacyTrustConfiguration:29-36`）を設定した prod では、flag に関係なく起動時に `kms.inspect()` を行い、構造的な不足を例外にせず `TrustStructureState` に置いて ERROR を出す。副入口 REL・REG の判定（`PrivacyHoldReleaseService.isOpen` :131-137、`PrivacyPolicyRegistrationService.readiness` :134-150）に「構造に不足が無い」を加える。実行中の文書の状態（鮮度・anchor）は今のまま判定のたびに読む（`KmsPrivacyTrustSource.refresh` :328-362）。
5. **Monitor の cache**（`PrivacyDecisionWindowMonitor.currentStatus` :156-165）: key を（JST の業務日、snapshot の世代、開いている capability の集合）にする。DB の源では有効期限の閉鎖は snapshot の `openUntil` でも効くが、metric（`ses_privacy_decision_binding_state`）と WARN の日ごとの出力のために Monitor は残す。
6. **評価器**: `PrivacyProductionConfigurationValidator` に `FeatureConditionEvaluator`（NF07 の 2 件）を実装させ、`validate`（:130-173）が呼ぶ method（`PrivacyDecisionBinding.evaluate` の呼出し :139-141、`validateDsarPrerequisites` :201-219、`validatePrivacyAdminGroup` :221-233、`validatePrivacyAdministrators` :240-282、`validateNoPrivacyAllowOutsidePrivacyAdmin` :284-301）をそのまま呼ぶ。`validate` は prod でも機能の条件では例外にしない（log だけ）。時間要因の code（`*.signedOn.future`・`*.expiresOn.expired`）は `temporal=true`。F2 provider の件数（`providerRegistry.providers()`）は memory。Journal と trust は 3・4 の結果を読む。
7. **DSAR の値**: `PRIVACY_DSAR_DUE_DAYS`・`PRIVACY_DSAR_PACKAGE_MAX_AGE` は D-06 の署名の構造化された値に移す（§18 J-15）。approval digest には入れない（正規形 v1 を変えない。FS 4.7.2 の人手の確認 (a) は、画面に出す D-06 の値と決定書の照合に置き換わる）。
8. **行為者**: NF07 の 2 件は申請・承認・閉鎖の行為者と、NF07 の署名の種別の確認者を privacy-admin に限る（§11.3）。
9. **PR #130 の guard**: §14。

### 8.6 NF05

§9。

## 9. NF05: 開けるのは次の起動、閉じるのは即時

### 9.1 起動の前の読取り（`IntegrationHubEnablementBootResolver`）

NF05 の bean の有無は `@Conditional`（要件 §1）が bean の定義の登録の時に決め、その時には DataSource の bean がまだ無い。そのため、Spring の `EnvironmentPostProcessor`（`META-INF/spring.factories` に登録）で context の refresh の前に DB を読み、NF05 の property を決める。

1. 対象: 有効な profile に `prod` を含むか、`app.feature-enablement.source=database`。それ以外（dev・test の property の源）は何もしない。
2. 総閘（`app.feature-enablement.allowed`）を読む。閉なら両方向を閉にする。
3. `spring.datasource.url`・`username`・`password` で JDBC の接続を 1 本だけ開く（`DriverManager`、接続と socket の timeout 5 秒。pool を作らない）。読むもの: tenant（`app.security.oidc.tenant-id`、未設定なら `default`）の NF05 の 2 行の開放状態、NF05 の署名の種別の確認済みの行、barrier の行（`integration.hub.outbound.runtime.v1`）。読取りだけで、書かない。
4. `Binder` で `IntegrationHubExternalApiProperties` を bind し（`@PostConstruct` は動かない）、§9.5 の純粋な検査の関数に、DB から読んだ決定 ID・digest を渡して違反を求める。
5. 方向ごとに、開放状態が `OPEN`・`OPEN_PENDING_RESTART`、要る署名が確認済みで今日が期間内、違反が無い、の全てが成り立つ時だけ開。
6. 最も優先する `MapPropertySource`（名前 `featureEnablementNf05Boot`）に `integration.hub.public-api.enabled`・`integration.hub.external-transport.enabled` を書く。決定の結果も同じ property source に `app.feature-enablement.nf05-boot.inbound.result`（`OPENED` / `CLOSED:<code>` / `READ_FAILED`）等として置く（static な holder を使わない）。
7. 接続の失敗・例外は `READ_FAILED` で両方向を閉にする（fail-closed）。log は `DeferredLogFactory` で出す（code だけ）。

既知の制約: DB の account を context の前に使う（同じ account・同じ secret。新しい権限は要らない）。DBA・Security の受理を RB §1.2 に追記する。

### 9.2 起動の後の反映（`IntegrationHubBootDecisionRecorder`）

- `SmartLifecycle`（web server より前。§7.1 の評価器より前の phase）で、property source の結果を DB に反映する:
  - `OPEN_PENDING_RESTART` かつ `OPENED` → `OPEN`（`restart_applied_at`、履歴 `RESTART_APPLIED`、監査）。
  - `OPEN`・`OPEN_PENDING_RESTART` かつ `CLOSED:<code>` → `AUTO_CLOSED(<code>)`。
  - `READ_FAILED` → 状態は変えず、履歴 `BOOT_READ_FAILED` を残し、画面に「この起動では閉」と出す（runtime の判定は起動時の値で閉）。
- `LegalEntityReadinessStartupValidator`（`ApplicationReadyEvent` :21-27）: NF05 のどちらかが起動時の値で開なら、`readinessService` の非例外の判定を呼び、未解決の行があれば例外にせず両方向を `AUTO_CLOSED(LEGAL_ENTITY_UNRESOLVED)` にする（overlay で直ちに閉じる）。management copilot の部分（`aiConfig.isManagementCopilotEnabled()`）は従来どおり例外。

### 9.3 閉じる（即時）

- `IntegrationHubRuntimeSwitch`（新設）: `inboundOpen() = 起動時の値 && featureSwitches.isEnabled(NF05_INBOUND)`、outbound も同じ。実行時の全ての読取りをこれに置き換える: `ExternalApiDisabledFilter:29-30`、`ExternalApiAuthenticationFilter:61-62`、`ExternalApiAuditBoundary.publicApiEnabled` :158-162、`BackupWriteGateFilter.publicApiEnabled` :87-91、`IntegrationHubResourceChangedProducer.changed` :37-39、`IntegrationHubWebhookDeliveryWorker` の :79-85・:111-114・:193-194。静的な試験 `IntegrationHubFlagReadGuardTest` で、`getPublicApi().getEnabled()`・`getExternalTransport().getEnabled()` を呼べるのを `@Conditional` の条件・起動時の検査・runtime switch・boot resolver だけに固定する。
- `IntegrationHubOutboundStartupBarrier`: 起動時の経路（`start` → `initializeSafely` :55・:72-101。phase `Integer.MIN_VALUE`）は起動時の値（`bootEnabled()`）で分岐し、実行時の `isReady` :103・`continueRecovery` :62-70・`quarantineBatch` :107-135 は runtime switch で判定する（現在は共に `enabled()` :137）。
- outbound を閉じる transaction（手動・自動・総閘）は、barrier の行を `selectByIdForUpdate` で lock し（§5.1 の (6)）、`v1|OFF|0|<sha256(bound-tenant-id)>` に書き直し、`NF05_OUTBOUND_OFF` を `recordDomainEventRequired` で監査する（新しい method `recordRuntimeOff`。現在の kill switch は行を `ON` のまま残すため、次に開く起動が隔離を飛ばす）。commit の後、memory の `ready` を false にする。
- 閉じた後の挙動は現在の kill switch と同じ: inbound は 404 `DISABLED_DENY`、outbound は producer が積まず、worker は送信しない（未送信は PENDING のまま、claim 済みは DLQ `KILL_SWITCH`）。bean は次の起動まで残る（runtime switch で眠る）。

### 9.4 outbound の barrier と RB の関係

- 規則は変えない: 初回の起動から全ての起動で同じ `INTEGRATION_HUB_BOUND_TENANT_ID` を設定する（RB §1.7 の「ON の起動までの順序と準備」の 1）。承認の前の process は outbound が閉のため、起動のたびに barrier が OFF の記録を書く。
- RB §1.7 で DBA・SRE が SQL で確かめていた「状態の行 `v1|OFF|0|<指紋>` と監査 `NF05_OUTBOUND_OFF`」は、outbound の承認の条件（自動検査）に置き換わる。現在の process の `INTEGRATION_HUB_BOUND_TENANT_ID` の指紋と行の指紋を比べるため、承認の後に値を変えて再起動すると、boot resolver の検査（同じ比較）で閉じたまま起動し、`AUTO_CLOSED(TENANT_STATE_MISMATCH)` になる。
- 承認から再起動までの間に OFF の起動が何度あっても、同じ値なら行は変わらない。
- 開く起動の後は、現在どおり QUARANTINING → READY（`NF05_OUTBOUND_REOPEN`・`NF05_OUTBOUND_READY`）。

### 9.5 `IntegrationHubProductionEnablementPolicy` の改修

- 承認の値（決定 ID・digest）を `properties.publicApi.approval`・`properties.externalTransport.approval` から読むのをやめ、引数（`IntegrationHubApprovals`）で受ける。property の源（dev・test）では property から作り、DB の源では署名から作る。
- 検査する方向を引数で受ける（現在は flag を :49-50 で読む）。承認の時の評価は、閉じている process でも開いた場合の違反を求める。
- truststore の検査（:138-146）は bytes を読むだけの純粋な関数にし、`recordApprovedTruststore`（:140）の副作用は起動時の値が開の時の `validateBoundaries` だけで行う。
- `validateBoundaries`（:35-125）の flag に依る検査（public-id-key :42-46、DEDICATED_DATABASE と bound-tenant-id :47-50、HTTPS の truststore :92-96）を静的な関数に切り出し、boot resolver と評価器から呼ぶ。DB の源の prod では、起動時の値が開でも違反があれば例外にせず runtime を閉じて `AUTO_CLOSED` にする（boot resolver と同じ関数を使うため、通常は起きない安全網）。
- flag に依らない検査（`provider.mode` が STUB・LOOPBACK でない :51-54、loopback の port が空 :55-57）は残す。outbound が閉の時の mode の規則（:58-60）は MOCK か HTTPS を許す（承認の前に HTTPS と承認の内容を部署の設定に入れるため）。

## 10. 緊急の総閘と旧い変数

- `FEATURE_ENABLEMENT_ALLOWED`（`app.feature-enablement.allowed`、`boolean`、既定 `true`）。`application.yml`・`application-prod.yml` に `${FEATURE_ENABLEMENT_ALLOWED:true}`。真偽値として解釈できない値は bind で失敗して起動しない（設定の誤り。機能の条件ではない）。
- 閉の時（要件 FE-RQ-007）: snapshot の `masterAllowed=false`（最初の要求から閉）、boot resolver は NF05 を閉、`FeatureEnablementStartupEvaluator` が全てを `AUTO_CLOSED(MASTER_SWITCH_CLOSED)`・未決を `CANCELLED`、申請・承認は 409、署名の登録・確認・閲覧は可。ERROR log と gauge `ses_feature_enablement_master_allowed=0`。開に戻しても自動では開かない（§18 J-11）。
- 閉じる方向の操作のため、ticket を要求しない（`BACKUP_QUIESCE_DISABLED_TICKET` は保護を外す方向のため ticket を要求している。向きが逆）。配備の記録に理由を残す運用を RB に書く。
- 「即時に閉じる」は、部署の設定の変更が再起動を伴うため、再起動の後の最初の要求から閉じることを指す。実行中に 1 つの機能を即時に閉じるのは画面の閉鎖（管理者 1 名）で行う。

**旧い変数の拒否**（`FeatureEnablementProductionValidator`。`@Component`、prod の判定は `Environment.acceptsProfiles(Profiles.of("prod"))`。CLAUDE.md の規則）:

- `System.getenv()` の名前を、`validate-task-definition.ps1` の `NameKey`（`_` を除き大文字）と同じ正規化で照合する: `FEATURENF*ENABLED`、`APPFEATURES*`、`PRIVACYRETENTIONENABLED`、`PRIVACYDSARENABLED`、`PRIVACYDSARDUEDAYS`、`PRIVACYDSARPACKAGEMAXAGE`、`PRIVACYAPPROVAL*`、`IMPORTADMISSION*`・`APPIMPORTCENTERADMISSION*`、`INTEGRATIONHUB(PUBLICAPI|EXTERNALTRANSPORT)ENABLED`、`INTEGRATIONHUB(INBOUND|OUTBOUND)(DECISIONID|APPROVALDIGEST)`、`INTEGRATIONHUBPUBLICAPIAPPROVAL(DECISIONID|APPROVALDIGEST)` 等の正規名、`FEATURENF01DUALCONFIRMATIONPRIVACYSIGNOFFREF`、`FEATURENF02CUSTOMERPORTALVISIBLESINCE`。`SPRING_APPLICATION_JSON` に `app.features`・`privacy.retention`・`privacy.dsar.enabled`・`privacy.approval`・`app.import-center.admission`・`integration.hub.*.enabled`・`integration.hub.*.approval` があれば同じく拒否する。
- 1 つでもあれば `IllegalStateException`（変数名の一覧だけ。値を出さない）で起動を止める（§18 J-12）。
- 同じ validator が、prod の `app.feature-enablement.source` が `database` でなければ起動を止める。
- 段階 A〜D の間は、移行済みの開放単位の変数だけを拒否する（§3.1 の印）。段階 E で全てを拒否する。
- RB §1.0 に行を足し、`infra/ecs/task-definition.prod.json` の雛形に `FEATURE_ENABLEMENT_ALLOWED=true` を置く（CLAUDE.md: prod の fail-fast を足す change で同時に行う）。

## 11. 安全

### 11.1 二人の承認

- service の拒否（403）と DB の CHECK（§2.4・§2.3・§2.6）の二重。CHECK は同じ利用者 ID を拒否するだけで、同じ人が 2 つの口座を持つことは防げない（口座の管理と OIDC の IdP に任せる。Security の既知の限界として RB §5.6 に書く）。
- 承認者が申請の時と違う内容を承認しないよう指紋で守る（§4.5）。
- 開放には少なくとも 2 名の有効な管理者が要る。prod は起動時検査で OIDC 管理者 3 名以上（`ProductionSecurityConfigurationValidator.MIN_OIDC_ADMINISTRATORS` :59）を保証し、PR #130 の guard が人数を割る変更を拒否するため、常に 2 名を確保できる。

### 11.2 session の種別（推奨: break-glass と bootstrap は不可）

- 判定: OIDC の session（`OAuth2AuthenticationToken`＋`OidcLoginUser`）で、MFA 済み（`MfaEnforcementFilter.isVerifiedFor` :32-36）、break-glass の incident の属性が無い（`BreakGlassService.INCIDENT_ID_ATTRIBUTE`）、bootstrap の session でない（`ProductionBootstrapMode.isBootstrapSession` :328）、username が break-glass の名前でない（`OidcSecurityProperties.isBreakGlassUsername` :67）。先例は `PwaRestoreAuthorizationService.supportsPasswordReauthentication` :91-99。prod 以外は local の form login（`LoginUser`）も許す。
- 有効な管理者の判定は要求ごとに DB から読み直す（`SecurityContextCallerContextProvider.isCurrentActiveAccount` :130-139 と同じ: role・`status`・未削除・`locked_until`・tenant）。
- **break-glass を不可にする理由**: break-glass は IdP が使えない時の限られた操作のための口座で、incident の許可 action は 1〜20 の既知の action に限られ、`user.*`・`permission.manage`・`privacy.*` を既に拒否している（`BreakGlassServiceImpl.validateAndSerializeActions` :316-331）。機能の開放は緊急の操作ではなく（閉じる方向は通常の管理者 1 名でできる）、break-glass の口座で開けると、IdP の障害の最中に監視の少ない口座で公開範囲を広げられる。incident の許可 action で `feature-enablement.*` を拒否し、session の判定でも拒否する（二重）。
- **bootstrap を不可にする理由**: bootstrap は初回の IdP・管理者の登録のための期限付きの local の session で、使える入口は `user`・`identity-provider` と `permission` の参照だけ（`BootstrapSessionFilter` :64・:73・:135-156）。bootstrap の最中は OIDC の管理者が揃っておらず、承認者の独立性も無い。本機能の入口を許可の一覧に足さない（bootstrap の filter が 403 にする）。
- 閉鎖も同じ session の規則とする（break-glass の口座で閉じる必要がある場面は、部署の設定の総閘で代える）。

### 11.3 敏感な機能の追加の条件

- **NF07 保持・保全・DSAR**: 申請者・承認者・閉鎖者と、NF07 の署名の種別の確認者を、privacy-admin に所属する有効な管理者（`UserPermissionGroupMapper#selectEffectivePrivacyAdminUsernames` と同じ条件。FS 4.7.4 の2）に限る（§18 J-05）。理由: privacy の操作は privacy-admin に分離されており（`'*'` を持つ他の group は `privacy.*` を拒否される。FS 4.7.1）、それ以外の管理者が privacy の入口の公開を決めると分離が崩れる。
- **DSAR**: 承認の時に D-06 の構造化された値（`due-days`・`package-max-age`）を承認者の画面に出し、決定書との照合を確認の checkbox で求める（FS 4.7.2 の人手の確認 (a) の置き換え）。
- **REL（hold 解除）・REG（policy の登録・有効化）**: 副入口の条件の署名（`NF07_D02`・`NF07_D03`・`NF07_D05`・`NF07_TRUST_*`）の確認者を privacy-admin に限る。要求ごとの既存の分離（解除の `error.privacy.holdReleaseSeparation`、有効化の `policyActivationSeparation`）は変えない。
- **NF02 顧客ポータル・公開範囲**: 顧客への公開を広げるため、公開範囲の変更も二人（§5.6）。
- **NF05**: 開ける変更は再起動を伴うため、再起動の前に「再起動待ち」を画面・通知・metric で示し、SRE が再起動の時刻を決める（自動の再起動はしない）。

### 11.4 監査

- `method`: `FEAT_EN`（7 文字。`t_audit_log.method` は `VARCHAR(10)`。`AuditLogMethodLengthGuardTest`）。
- `application_code`（`VARCHAR(64)`）: `FEATURE_OPEN_REQUESTED`・`FEATURE_OPEN_APPROVED`・`FEATURE_OPEN_REJECTED`・`FEATURE_OPEN_WITHDRAWN`・`FEATURE_OPEN_EXPIRED`・`FEATURE_OPEN_STALE`・`FEATURE_CLOSED`・`FEATURE_AUTO_CLOSED`・`FEATURE_MASTER_CLOSED`・`FEATURE_RESTART_APPLIED`・`FEATURE_BOOT_READ_FAILED`・`FEATURE_SETTING_REQUESTED`・`FEATURE_SETTING_CHANGED`・`FEATURE_SIGNOFF_REGISTERED`・`FEATURE_SIGNOFF_CONFIRMED`・`FEATURE_SIGNOFF_REJECTED`・`FEATURE_SIGNOFF_REVOKED`・`FEATURE_SIGNOFF_SUPERSEDED`・`FEATURE_SIGNOFF_EXPIRED`・`FEATURE_SIGNOFF_PURGED`・`FEATURE_SIGNOFF_DOWNLOADED`・`FEATURE_SUBGATE_CHANGED`・`FEATURE_OPEN_CONDITION_EXPIRING`・`FEATURE_OPEN_CONDITION_LOST`。
- 本文（`before_state`・`after_state`）: 開放単位・状態・理由の code・申請 ID・署名 ID だけ。
- 自動の遷移は actor SYSTEM、`confirmationSource` は `STARTUP` / `SCHEDULER_POLL`（既存の値を使う。無ければ足す）。
- 履歴（§2.7）と監査は同じ transaction で書く。

### 11.5 総閘

§10。部署の設定にあるため、DB を書き換える権限を持つ者でも総閘は開けられない（閉じる方向の最後の手段）。

## 12. 失敗の形と fail-closed

| 失敗 | 挙動 | DB の状態 |
|---|---|---|
| 起動時に snapshot を読めない | 全ての DB 管理の開放単位を閉として起動する（他の起動時検査で止まらない限り）。poll で読めれば戻る | 変えない |
| snapshot が古い（> `max-staleness`） | 全て閉。WARN と metric | 変えない |
| commit の後の差替えに失敗 | 変更した開放単位を overlay で閉。次の poll | 変わっている（正） |
| 評価器の例外・照会の失敗 | その開放単位を overlay で閉。3 回続けば自動閉鎖（J-10）。承認・申請の時は 409 | 3 回で `AUTO_CLOSED` |
| 評価器が無い | 申請・承認は 409。開いていれば起動時の評価で `AUTO_CLOSED(evaluator.missing)` | 自動閉鎖 |
| 監査の書込みの失敗 | 遷移ごと rollback。自動閉鎖は overlay のまま次の周期で再試行 | 変えない |
| backup の静止の最中 | 書込みは静止の解除まで待つ（scheduler）か 503（API）。overlay は memory で効く | 解除の後に書く |
| 通知の失敗 | log だけ | 変わっている |
| NF05 の起動前の読取りの失敗 | NF05 を閉で起動。`BOOT_READ_FAILED` | 変えない |
| 総閘の値が解釈できない | 起動しない（設定の誤り） | ― |
| 旧い変数がある（prod） | 起動しない（§10） | ― |
| 時計の巻戻り | 期間は JST の業務日で判定し、署名日前は不成立（開く方向へ倒れない） | ― |

## 13. 移行と過渡

- **fresh install**: prod は空の DB への新規導入（共通規則 6 (l)）。V203 の表は空で、全ての開放単位は `CLOSED`。既存の値を移す data migration を作らない。
- **旧い変数**: prod では拒否（§10）。dev・test の property の源では従来どおり読む。
- **dev・test の既定**: `app.feature-enablement.source=properties`。test profile の全て開（`application-test.yml:208-239`）は変えない。本機能の H2 の試験は `@TestPropertySource(properties = "app.feature-enablement.source=database")` で DB の源を使い、`@Transactional` で rollback し、自分が入れた行だけを読む（`TestIsolationAuditTest`）。snapshot の refresher は試験では起動しない（試験が明示的に読み直す）。
- **local の Demo**: `dev` profile で `APP_FEATURE_ENABLEMENT_SOURCE=database` を付けて起動する（`application-dev.yml` に placeholder を置く）。prod 専用の評価器を持つ開放単位（NF01 二者確認以外の NF06・NF07・NF05）は dev では「評価器が無い」として申請できない（§4.4）。
- **段階の間**: §3.1 の印。go-live は段階 E の後。

## 14. PR #130（管理者の継続性 guard）との関係

- PR #130 の `AdministratorContinuityGuard` は、prod で起動時検査（OIDC 管理者 3 名以上・break-glass 2 名・privacy-admin 2 名以上）を割る変更を拒否する。privacy-admin の条件は、現在「NF07 の flag に関係なく」判定している（`PrivacyProductionConfigurationValidator.checkAdministratorConditions` の javadoc :176-183。理由は「OFF の間に人数を割ると ON にする起動が止まるため」）。
- 本機能の後、privacy-admin の人数は起動を止める条件ではなく、NF07 の開放の条件（割れば NF07 だけが自動閉鎖）になる。そこで guard の privacy-admin の条件は、NF07 保持・保全か DSAR が開いている・再起動待ち・未決の申請がある時だけ判定する（`evaluated`）ように改める（2026-11-22 の決定は「NF07 が ON の時に privacy-admin 2 名以上」を守る対象にしており、これに合う。§18 J-17）。閉じている間は `notEvaluated`。
- guard は開放状態を snapshot ではなく DB から読む（guard の transaction の中で最新を見るため。lock は取らない）。申請の作成と guard の並行は、承認の時の評価し直し（§5.3）と定期の評価で閉じる。
- OIDC 管理者 3 名以上・break-glass 2 名の条件は機能の開放と関係しないため、変えない（起動時検査も guard も現状のまま）。二人の承認が常に可能なのはこの条件による（§11.1）。

## 15. 画面と API

### 15.1 API（全て `ApiResult`。`/api/feature-enablement`）

| method・path | 内容 | 行為者 |
|---|---|---|
| `GET /units` | 一覧（状態・申請中・再起動待ち・`openUntil`・最後の評価の時刻と成否の件数） | 管理者 |
| `GET /units/{key}` | 詳細（条件の一覧、要る署名と状態、鍵の有無、子、未決の申請、DUAL template の件数） | 管理者 |
| `POST /units/{key}/evaluate` | その場で 1 件を評価（1 分に 1 回まで） | 管理者 |
| `POST /units/{key}/close` | 閉鎖（理由） | 管理者（NF07 は privacy-admin） |
| `POST /requests` | 申請（`type`・`target`・`value`・理由） | 同上 |
| `POST /requests/{id}/approve`・`/reject`・`/withdraw` | 承認・却下・取下げ（`expectedVersion`） | 同上 |
| `GET /requests` | 未決と最近の申請（`PageUtils.safePage`） | 管理者 |
| `GET /sign-offs`・`GET /sign-offs/{id}` | 署名の一覧（氏名なし）・詳細（氏名あり） | 管理者 |
| `POST /sign-offs`（multipart） | 登録 | 管理者（NF07 の種別は privacy-admin） |
| `POST /sign-offs/{id}/confirm`・`/reject`・`/revoke` | 確認・却下・取消し | 同上 |
| `GET /sign-offs/{id}/document` | 文書（hash を照合して返す） | 管理者 |
| `GET /events` | 履歴（`PageUtils.safePage`。subject・期間で絞る） | 管理者 |
| `GET /catalog` | 目録（種別・役割・上限。画面の選択肢） | 管理者 |

- action の key は `ActionPermissionResolver` の規則どおり（`GET` → `feature-enablement.view`、`POST` → `feature-enablement.create`）。行為者の細かな規則（§11）は service 層で判定する。
- 誤りの code は 4 つの bundle の `error.featureEnablement.*`。

### 15.2 画面

- `templates/feature-enablement/list.html`（`layout:fragment="content"`・`page-js`）と `static/js/modules/feature-enablement.js`。tab: 「開放単位」「申請」「署名・受理」「設定」「履歴」。
- 開放単位の tab: 状態の badge（未開放・申請中・開放中・再起動待ち・自動閉鎖（理由）・手動閉鎖・この起動では閉）、条件の ✓/✗（種類ごと）、`openUntil`、操作の button（申請・閉鎖・再評価）。詳細は modal。
- 申請の tab: 未決の申請（申請者・時刻・条件の要約・期限）と承認・却下・取下げ。自分の申請には承認の button を出さない（API も 403）。
- 署名・受理の tab: 種別ごとの現在の署名（状態・期間・残り日数・登録者・確認者）、登録の modal（種別を選ぶと役割の選択肢と構造化された値の入力欄が変わる。digest を持つ種別は system の計算値の先頭 16 文字を出し、文書の値と照合させる）、確認・却下・取消し。
- 履歴の tab: `.card > .card-body > form#searchForm` の構造で絞込みを置く（`SES.filterPanel` が phone で畳む）。
- 規約: `SES.csrf.header()`、`res.code === 200`、`Toast`、閉鎖・取消しは `Swal.fire` で確認、`/lib/...` の同梱の library だけ。

## 16. 試験の方針

| 区分 | 試験 | 内容 |
|---|---|---|
| unit | `FeatureEnablementSnapshotTest`・`DatabaseFeatureEnablementStateSourceTest` | 総閘・状態・`openUntil`（JST 0:00 の境）・overlay・古さ、照会 0 回 |
| unit | `FeatureConditionRegistryTest`・`SignOffConditionEvaluatorTest`・`FeatureConditionFingerprintTest` | 共通の評価器、例外の `UNVERIFIABLE`、評価器なし、指紋の正規形（golden vector） |
| unit | 各評価器の試験（`LifecycleDualConfirmationEnablementConditionsTest`・`ImportCenterEnablementConditionsTest`・`PrivacyProductionConfigurationValidatorTest` の追加・`IntegrationHubProductionEnablementPolicyTest` の追加） | 起動時検査と同じ入力で同じ code になること（判定の写しが無いこと） |
| H2 | `FeatureEnablementServiceH2Test` | 申請・承認・却下・取下げ・期限切れ・陳腐化・閉鎖・cascade、行為者の規則、監査と履歴の行、CHECK・一意索引（H2） |
| H2 | `FeatureSignOffServiceH2Test` | upload の scan・hash・照合、二人の確認、差替え・取消しでの自動閉鎖、汎用の経路の拒否、消去と hold の gate |
| H2 | `FeatureEnablementConditionSchedulerH2Test` | 定期・日の始まりの評価、`void`、自動閉鎖と通知、`OPEN_ONLY`（NF02 の暦）が外れても閉じず監査と通知だけ |
| MVC | `FeatureEnablementApiControllerTest` | 権限（管理者以外 403、group の有無）、`ApiResult`、CSRF |
| 既存 | `FeatureSwitchFilterTest`・`FeatureSwitchMenuAndViewTest`・`FeatureSwitchSchedulerGateTest`・`FeatureSwitchRouteCoverageTest`・`FeatureSwitchRuntimeConditionTest`・`ActionPermissionMatrixTest`・`MessageBundleConsistencyTest`・`StaticAssetLocalityTest`・`AuditLogMethodLengthGuardTest`・`ScheduledMethodsHaveSchedulerLockTest`・`SchedulerLockProxyInvocationTest`・`SchedulingDisabledDrainTest`・`TestIsolationAuditTest`・`MigrationScriptIntegrityTest`・`DeployRunbookMigrationCountTest` | 変えずに（または一覧の追加だけで）通る。DB の源でも同じ OFF の応答になる変種を足す |
| 静的 | `FeatureEnablementCatalogCoverageTest`・`PrivacyFeatureFlagReadGuardTest`・`IntegrationHubFlagReadGuardTest`・`FeatureEnablementLegacySettingTest` | 目録の網羅、直接の読取りの禁止、旧い変数の一覧と ps1 の一致 |
| MySQL（`@Tag("mysql")`。shard は統括が指定） | `FeatureEnablementSchemaMySqlTest` | V203 の CHECK（3819 は `MySqlConstraintRejections.assertRejectedBy`）・一意索引・trigger（更新・削除の拒否） |
| MySQL | `FeatureEnablementConcurrencyMySqlTest` | 同じ申請の承認の並行（1 件だけ成功）、承認と閉鎖の並行、世代の lock の順、guard と申請の並行 |
| MySQL | `IntegrationHubEnablementRestartMySqlTest` | boot resolver の読取り、再起動での `OPEN` 化、閉鎖での barrier の書き直しと次の起動の隔離 |
| MySQL | `FlywayProdFreshInstallMySqlTest`（期待値の更新） | V203 の表・trigger 数 |
| 実 Tomcat | `FeatureSwitchServletContainerIntegrationTest` の DB の源の変種 | 閉じた機能の 404 が property の源と同じ |
| browser（`-Pbrowser-tests`。統括が実行） | `FeatureEnablementScreenBrowserTest` | 一覧・登録・確認・申請・承認・閉鎖の画面の流れ、phone 幅の絞込み |
| ps1 | `infra/ecs/offline/test-validate-task-definition.ps1` の追加 | 旧い変数の FAIL（両 phase）、総閘の置き場所と値 |

## 17. 文書・運用の更新箇所

- FS: 1 の一覧（環境変数の列を「部署の設定には無い。画面で開く」に、test profile の列は残す）、1 の「値は起動時に1回だけ読む」等の記述（:32・:131 の「必ず再起動」）を改める、3 の手順を画面の手順へ（3.1〜3.4）、4 の各機能の「ONの条件」「ONにする手順」を「開放の条件（画面の表示）と署名の種別」へ、4.1 に scheduler 無効の時の制約、5 の「新しい入口を追加するとき」に目録の登録を足す、6 の試験の表。
- RB: 冒頭の NO-GO の条件（機能の ON を go-live の前提から外す）、§1.0（総閘・`FEATURE_ENABLEMENT_*` の運用値・旧い変数の「設定してはいけない値」・NF05 の起動前の DB の読取り）、「ONにした時だけ必須になる設定」を鍵と基盤の設定だけに、§1.2（起動前の読取りの受理）、§1.7（go-live の前提は基盤と中核、署名表は「system の中で開く前提」、ON の起動の準備のうち自動検査に置き換わる行）、§1.7.1（二者確認の手動確認は署名 `NF01_DUAL_MANUAL_CHECK` として登録）、§3.4（V203 の確認と trigger 数）、§4.4（D17: 署名の消去）、§4.2（bootstrap の間は本画面を使えない）、§5.5 の後（開放の手順）、§5.6（二人の承認と同一人物の複数口座の限界）。
- `infra/ecs/task-definition.prod.json`: `FEATURE_ENABLEMENT_ALLOWED=true`、注記の機能スイッチの記述を改める。
- `infra/ecs/offline/validate-task-definition.ps1`: :316-321 の機能スイッチの名前の照合を旧い変数の拒否（両 phase）に、:576・:589 以降の Initial phase の機能スイッチの検査を削除、`FEATURE_ENABLEMENT_ALLOWED` を `mustBeVisible` に。
- 各 spec の「ONにする手順」を参照している文書（`engineer-lifecycle-workflow/runbook.md`、`privacy-retention-dsar/production-bridge-phased-plan.md`、`integration-hub-public-api/dg-05-enablement-decision-draft.md`、`ops/security/runbooks/integration-hub-public-api.md`、`data-migration-import-center/a1a-ingress-operations.md`）は段階 E で参照先を本 spec と FS に改める。
- CLAUDE.md（NF01・NF07 の起動を止める gate の記述、Roadmap の表の記述）: worker は変えない。統括が段階 E の後に改める。

## 18. 判断が要る点

| # | 論点 | 選択肢 | 決定または推奨と理由 |
|---|---|---|---|
| J-01 | D-5「部署の設定には鍵と総閘だけ」の範囲 | (a) 機能スイッチと署名の値だけを移し、基盤の設定（NF05 の bound-tenant-id・CIDR・送信先・trusted proxies・mode・truststore の path、NF07 の Journal の接続・trust 文書の path・鮮度の上限、DSAR の通知日数）は残す。(b) 基盤の設定も DB に移す | **決定済み（2026-11-22、利用者）: (a)**。機能スイッチと署名の値だけを system へ移し、基盤の設定は部署の設定に残す（内容は署名の digest で決定に束縛する）。理由: 基盤の設定は bean・Tomcat・接続 pool の構成に起動時に使い、SRE が持つ値で、DB に移しても再起動は残る。NF05 の承認の内容（CIDR 等）は digest で決定に束縛されるため、部署の設定に残しても決定と食い違えば開かない |
| J-02 | dev・test の源 | (a) property のまま、DB は opt-in。(b) 全 profile で DB | (a)。既存の試験の意味を保ち（test は全て開）、H2 の schema の 2 系統の負担を増やさない |
| J-03 | 副入口（hold 開始・REL・REG）を独立の開放単位にするか | (a) しない（署名の二人の確認で開く。条件が外れれば副入口だけ閉じて告警）。(b) NF07 保持・保全の子の開放単位にして、開放の申請と承認を別に要する | (a)。依頼の分け方（保持・保全に REL・REG を含める）に合い、副入口の署名は privacy-admin の二人の確認を経る。(b) は二人の操作が二重になる |
| J-04 | break-glass・bootstrap の session で申請・承認できるか | (a) どちらも不可。(b) break-glass は可 | (a)。§11.2 |
| J-05 | NF07 の行為者を privacy-admin に限るか | (a) 限る。(b) 管理者なら可 | (a)。§11.3 |
| J-06 | 署名の登録に二人の確認を求めるか | (a) 求める。(b) 1 名で登録、開放の承認でだけ二人 | (a)。§6.4（差替えは開放の承認を経ないため） |
| J-07 | 未決の申請の期限 | 7 日 / 30 日 / 無期限 | 7 日。古い条件で承認させない |
| J-08 | 署名の有効期限の上限 | (a) 全種別 366 日。(b) 種別ごと（受理・実測は 3 年等） | (a)。既存の NF07・NF06・NF08 の規則と同じで単純。運用の負担が大きければ (b) |
| J-09 | 時間要因の扱いの変更 | 2026-11-14 の「失効は OFF 扱いで起動、署名日前は署名日に自動で開く」を D-4 で置き換え、自動では開かない。NF06 の期間外は「upload だけ 403」から「NF06 を閉じる（404）」に変わる | **決定済み（2026-11-22、利用者）: 新しい規則に置き換える**（D-4 の帰結）。署名の失効でその開放単位を自動閉鎖して告警し、NF06 の期間外は NF06 全体を閉じる（404）。署名日前の署名では開かず、署名日以降に管理者の申請と承認で開く。2026-11-14 の時間要因の規則（失効・署名日前は起動を止めず OFF 扱い、署名日に自動で開く。FS 4.7.2.1・`ImportAdmissionStartupValidator:75`）をこれで置き換える |
| J-10 | 評価できない時 | (a) 直ちに overlay、3 回続けば自動閉鎖。(b) 直ちに自動閉鎖。(c) overlay のまま | (a)。一時の DB の障害で再承認を強いず、続けば D-4 どおり閉じる |
| J-11 | 総閘を開に戻した後 | (a) 自動では開かない（申請と承認）。(b) 戻せば元どおり | (a)。D-4 と同じ扱いで、緊急停止の後に各機能を確かめ直す |
| J-12 | prod の旧い変数 | (a) 起動を止める。(b) WARN で無視 | (a)。無視すると運用者が開閉を誤解する。新規構築のため移行の負担は無い |
| J-13 | 画面の閲覧者 | (a) 管理者だけ。(b) マネージャーにも参照 | (a)。条件と署名の参照は安全上の構成を表す。必要になれば `feature-enablement.view` の seed を足す |
| J-14 | 署名者の氏名と文書の保存期間 | 失効・取消し・差替えから 3 年 / 5 年 / 無期限 | 3 年（暫定）。法務・Privacy の確認が要る |
| J-15 | DSAR の `due-days`・`package-max-age` | (a) D-06 の署名の構造化された値（digest v1 には入れない）。(b) 運用の値として部署の設定に残す。(c) digest v2 に入れる | (a)。D-06 の値で、差替えに再起動を要しないため。(c) は決定書の digest の手順が変わるため別の判断 |
| J-16 | NF02 の暦の条件 | (a) 継続の条件（暦が切れれば NF02 を閉じる。30 日前から警告）。(b) 開放の時だけの条件 | **決定済み（2026-11-22、利用者）: (b)**。申請・承認の時に当年度の全社共通カレンダーを必須にし、その後は継続の条件にせず NF02 を自動閉鎖しない（`Scope.OPEN_ONLY`。§4.2）。暦の有効期間の終わりの 30 日前から毎日、監査と管理者への通知で告警する（§7.4）。暦が無い間は現行の WARN `missing_calendar` と、土日だけを休日とする計算を続ける。worker の推奨は (a) だった |
| J-17 | PR #130 の guard の privacy-admin の条件 | (a) NF07 が開いている・再起動待ち・未決の申請の時だけ判定。(b) 現在どおり常に判定 | (a)。§14 |
| J-18 | 親の閉鎖で子を閉じるか | (a) 子も自動閉鎖（再承認が要る）。(b) 子の状態は残し、実効値だけ閉 | (a)。D-4（条件「親が開」が外れたら閉じる）に合う |
| J-19 | 閉鎖の人数 | (a) 管理者 1 名。(b) 二人 | (a)。閉じる方向は安全側で、緊急に遅らせない |
| J-20 | NF05 の起動前の DB の読取り | (a) `EnvironmentPostProcessor` で生の JDBC 接続 1 本。(b) NF05 の bean を常に作り実行時に開閉する大改修 | (a)。D-2（NF05 は再起動で反映）の範囲で最小。DBA・Security の受理を RB §1.2 に足す |
