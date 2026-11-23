# 機能の開放を system 内で行う 設計

- 状態: **草案（第 1 段階: spec のみ。第 3 版: 独立 review `rb-feature-enablement-spec-96be3af4-review.md` の P1 2・P2 7・P3 21、`85840936` の delta review の P2 3・P3 10 と、統括の決定（J-08・J-10・J-20 と delta の修正方針）を反映）**。要件は `requirements.md`（以下「要件」）、手順は `tasks.md`。review の指摘と本書の対応箇所は §19。
- 行番号は `88b31d45` の行番号である。実装の着手時に再確認する。
- 判断が要る点は §18 に J-01〜J-20 として一覧する。J-01・J-09・J-16 は決定済み（2026-11-22、利用者）で、決まった値で各節を書いた。他は推奨の状態で、各節は推奨で書く（独立 review の後に統括が決める）。

## 0. 位置付け

機能の開閉を、配備の設定（環境変数）から DB の開放状態へ移す。開閉の判定窓口（`FeatureSwitches`）と、それを呼ぶ filter・ControllerAdvice・scheduler・service 層の gate は変えず、`FeatureSwitches` が読む源だけを差し替える。prod の起動時検査のうち機能の条件を見ていたものは、同じ判定を「条件の評価器」として再利用し、起動を止める代わりにその機能を閉じる。NF05 だけは bean の構成が起動時に決まるため、開ける変更を次の起動で反映する。

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
  緊急:   FEATURE_ENABLEMENT_ALLOWED（配備の設定）
```

package は `com.ses.featureenablement`（service・mapper 以外の部品）とし、entity・mapper は既存の規約（`com.ses.entity`・`com.ses.mapper`）に置く。追記専用の表の mapper も `com.ses.mapper` に置いて `AppendOnlyMapper` を継承し `appendOnlyTable()` を実装する（`@MapperScan` の範囲の外にあるのは `AppendOnlyMapper` の interface だけ。`AppendOnlyMapper.java:24-26`、先例 `LifecycleDualEventMapper`）。

## 2. データモデル（V203）

### 2.1 表の一覧

| 表 | 役割 | 更新 |
|---|---|---|
| `t_feature_enablement_generation` | tenant ごとの世代（変更の通し番号）。全ての書込みの最初に lock する | 更新あり |
| `t_feature_enablement_state` | 開放単位の現在の開放状態 | 更新あり（version の CAS） |
| `t_feature_enablement_request` | 申請（開放・設定の変更）。未決から終了へ 1 回だけ遷移する | 更新あり（version の CAS） |
| `t_feature_enablement_setting` | 承認で変える設定（現在は NF02 の公開範囲だけ） | 更新あり |
| `t_feature_sign_off` | 署名の登録・確認・失効・取消し・差替え・消去 | 更新あり（version の CAS） |
| `t_feature_sign_off_extension` | 証拠の類の署名の延長の記録（申請者・確認者・旧い期限・新しい期限）。延長は署名の行の期限を同じ行で更新し、この表に 1 行を足す | **追記専用**（`AppendOnlyMapper`＋trigger） |
| `t_feature_sign_off_bundle` | 署名の組（互いに束縛し合う署名の集合。現在は NF07 の決定の束縛）。登録・確認・差替えの単位 | 更新あり（version の CAS） |
| `t_feature_sign_off_bundle_member` | 組と署名の対応（引き継いだ署名は旧い組と新しい組の両方に属する） | **追記専用**（`AppendOnlyMapper`＋trigger） |
| `t_feature_enablement_event` | 全ての遷移の履歴 | **追記専用**（`AppendOnlyMapper`＋trigger） |

- 版番号は V203 の 1 件とする（統括の指定）。後の段階で DDL が要れば V204 以降を統括に申請する。
- V203 の中の作成の順は FK の向きに合わせて generation → request → state → setting → sign_off → sign_off_extension → sign_off_bundle → sign_off_bundle_member → event、その後に trigger（6 件）・menu・data element の seed とする（本節の説明の順とは違う）。
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

- 行は最初の書込みで作る（no-op の upsert `INSERT … ON DUPLICATE KEY UPDATE generation = generation` の後に `SELECT … FOR UPDATE`。`INSERT IGNORE` は重複の時に共有 lock を取り、`FOR UPDATE` での排他 lock への昇格で二つの書き手が deadlock（1213）するため使わない。実装 review rb-fe-a1 P1-1。repo の先例は `PrivacyHoldProjectionStore.ensureStateLocked` 等）。全ての書込みの transaction はこの行を最初に lock し、`generation` を 1 つ上げる。tenant の中の変更は直列になる（量が少ないため直列で足りる。§5.1 の lock の順）。
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
  unverifiable_since DATETIME(6)  NULL,   -- 評価できない状態の始まり（全 instance が snapshot で共有する一時の閉鎖。§7.4）
  unverifiable_reason VARCHAR(64) NULL,
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
  CONSTRAINT fk_fe_state_request FOREIGN KEY (open_request_id, opened_by, approved_by)
      REFERENCES t_feature_enablement_request (id, requested_by, decided_by)
);
```

- 行が無い開放単位は `CLOSED` として扱う（行は最初の申請で作る）。
- 開放単位の key は全て既知のため CHECK で固定する（綴りの誤りを DB でも拒否する）。FK に参照動作（`ON DELETE` 等）を付けない（MySQL 8 は参照動作の列に CHECK を付けられない）。
- 「申請中」は `t_feature_enablement_request` の未決の行があることで表し、この表の値にしない（未決の申請は開放状態を変えない）。
- 複合 FK（`fk_fe_state_request`）で、開放状態の行の申請者・承認者が、参照する申請の行の申請者・決定者と同じであることを DB で強制する（review P3-12。いずれかの列が NULL の行（未開放）は FK の検査の対象外で、`OPEN` の行は `ck_fe_state_open` が非 NULL を強制する）。参照する申請が `APPROVED` であることは DB では強制できない（状態の列は FK にしない）ため、service と試験で固定する。
- 閉鎖・自動閉鎖・再開で `closed_*`・`unverifiable_*` を NULL に戻す更新は、全体の `update-strategy: not_null` のため `UpdateWrapper.set(col, null)` で列を名指しする（`FieldStrategy.ALWAYS` を使わない。CLAUDE.md）。

### 2.4 `t_feature_enablement_request`

```sql
CREATE TABLE t_feature_enablement_request (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100)  NOT NULL,
  request_type          VARCHAR(20)   NOT NULL,   -- OPEN / SETTING_CHANGE / SIGN_OFF_EXTENSION
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
  CONSTRAINT ck_fe_request_type CHECK (request_type IN ('OPEN','SETTING_CHANGE','SIGN_OFF_EXTENSION')),
  CONSTRAINT ck_fe_request_status CHECK (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN','EXPIRED','STALE','CANCELLED')),
  CONSTRAINT ck_fe_request_decision CHECK (
         (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
      OR (status IN ('APPROVED','REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL
          AND decided_by <> requested_by)
      OR (status = 'WITHDRAWN' AND decided_by IS NOT NULL AND decided_by = requested_by AND decided_at IS NOT NULL)
      OR (status IN ('EXPIRED','STALE','CANCELLED') AND decided_by IS NULL AND decided_at IS NOT NULL)),
  CONSTRAINT uk_fe_request_actors UNIQUE (id, requested_by, decided_by),   -- 開放状態・設定の複合 FK の参照先
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
  requested_by  BIGINT       NOT NULL,  -- 申請者（承認者と別人）
  approved_by   BIGINT       NOT NULL,
  approved_at   DATETIME(6)  NOT NULL,
  CONSTRAINT uk_fe_setting UNIQUE (tenant_id, setting_key),
  CONSTRAINT ck_fe_setting_key CHECK (setting_key IN ('nf02-customer-portal.visible-since')),
  CONSTRAINT ck_fe_setting_separation CHECK (requested_by <> approved_by),
  CONSTRAINT fk_fe_setting_request FOREIGN KEY (request_id, requested_by, approved_by)
      REFERENCES t_feature_enablement_request (id, requested_by, decided_by)
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
  original_name     VARCHAR(255) NULL,       -- 氏名を含み得るため消去の対象（§6.6）
  kind_class        VARCHAR(10)  NOT NULL,   -- DECISION（決定の類）/ EVIDENCE（証拠の類）。上限の日数の検査に使う（§6.5）
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
  CONSTRAINT uk_fe_sign_off_kind UNIQUE (id, kind_code, tenant_id),        -- 組の member の複合 FK の参照先（review P3-N6）
  CONSTRAINT uk_fe_sign_off_stored_name UNIQUE (stored_name),
  CONSTRAINT ck_fe_sign_off_status CHECK (status IN ('REGISTERED','CONFIRMED','REJECTED','REVOKED','SUPERSEDED','EXPIRED','PURGED')),
  CONSTRAINT ck_fe_sign_off_separation CHECK (confirmed_by IS NULL OR confirmed_by <> registered_by),
  CONSTRAINT ck_fe_sign_off_confirmed CHECK (status NOT IN ('CONFIRMED','SUPERSEDED','EXPIRED')
      OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)),
  CONSTRAINT ck_fe_sign_off_dates CHECK (expires_on > signed_on),
  CONSTRAINT ck_fe_sign_off_purged CHECK (status <> 'PURGED'
      OR (stored_name IS NULL AND signer_name IS NULL AND original_name IS NULL AND purged_at IS NOT NULL)),
  CONSTRAINT ck_fe_sign_off_class CHECK (kind_class IN ('DECISION','EVIDENCE')),
  INDEX idx_fe_sign_off_kind (tenant_id, kind_code, status),
  INDEX idx_fe_sign_off_purge (status, purge_after)
);
```

- 種別ごとに確認済み（`CONFIRMED`）は 1 件だけ（`active_slot`）。差替えは同じ transaction で旧い行を `SUPERSEDED` にしてから新しい行を `CONFIRMED` にする（順を逆にすると一意索引で失敗する）。
- `REVOKED` は確認済みの取消し、`REJECTED` は未確認の却下。`EXPIRED` は失効日を過ぎた後に定期の処理が付ける（有効かどうかの判定は `expires_on` で行い、`EXPIRED` の付与を待たない）。
- 決定の種別（NF07 の D-xx、NF06 の admission、NF05 の DG-05）は、行の `signed_on`・`expires_on` を決定の署名日・失効日とし、`structured_values` に残りの値を置く（二重に持たない）。
- 署名日が今日より後の行は登録できるが、確認（`CONFIRMED` への遷移）は署名日の当日からに限る（review P2-6。JST の業務日。DB の CHECK は非決定の関数を使えないため service と試験で固定する）。
- 組に属する種別（§2.6.1）は、行ごとの確認を受け付けず、組の確認でだけ `CONFIRMED` になる。

### 2.6.1 `t_feature_sign_off_bundle`・`t_feature_sign_off_bundle_member`（署名の組。review P1-2）

```sql
CREATE TABLE t_feature_sign_off_bundle (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id     VARCHAR(100) NOT NULL,
  bundle_kind   VARCHAR(64)  NOT NULL,   -- 目録の組の種類（現在は NF07_DECISION_BINDING）
  status        VARCHAR(20)  NOT NULL,   -- REGISTERED / CONFIRMED / REJECTED / SUPERSEDED / REVOKED
  active_slot   TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'CONFIRMED' THEN 1 ELSE NULL END) STORED,
  registered_by BIGINT       NOT NULL,
  registered_at DATETIME(6)  NOT NULL,
  confirmed_by  BIGINT       NULL,
  confirmed_at  DATETIME(6)  NULL,
  ended_by      BIGINT       NULL,
  ended_at      DATETIME(6)  NULL,
  end_reason    VARCHAR(64)  NULL,
  version       INT          NOT NULL DEFAULT 0,
  CONSTRAINT uk_fe_bundle_active UNIQUE (tenant_id, bundle_kind, active_slot),
  CONSTRAINT uk_fe_bundle_tenant UNIQUE (id, tenant_id),
  CONSTRAINT ck_fe_bundle_status CHECK (status IN ('REGISTERED','CONFIRMED','REJECTED','SUPERSEDED','REVOKED')),
  CONSTRAINT ck_fe_bundle_separation CHECK (confirmed_by IS NULL OR confirmed_by <> registered_by),
  CONSTRAINT ck_fe_bundle_confirmed CHECK (status NOT IN ('CONFIRMED','SUPERSEDED')
      OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL))
);
CREATE TABLE t_feature_sign_off_bundle_member (
  bundle_id     BIGINT       NOT NULL,
  tenant_id     VARCHAR(100) NOT NULL,
  kind_code     VARCHAR(64)  NOT NULL,
  sign_off_id   BIGINT       NOT NULL,
  carried_over  TINYINT      NOT NULL,   -- 1: 旧い組から引き継いだ確認済みの署名
  PRIMARY KEY (bundle_id, kind_code),
  CONSTRAINT fk_fe_bundle_member_bundle FOREIGN KEY (bundle_id, tenant_id)
      REFERENCES t_feature_sign_off_bundle (id, tenant_id),
  CONSTRAINT fk_fe_bundle_member_sign_off FOREIGN KEY (sign_off_id, kind_code, tenant_id)
      REFERENCES t_feature_sign_off (id, kind_code, tenant_id)   -- member の種別と tenant を署名の行と一致させる（review P3-N6）
);
-- member は追記専用（BEFORE UPDATE / DELETE の trigger 2 件。event・延長の記録と合わせて V203 の trigger は 6 件）
```

- 組の種類は目録が決める。`NF07_DECISION_BINDING` の member は `NF07_D01`・`NF07_D09` と、D-09 が `dsar` を承認する時は `NF07_D06`（D-09 の digest の正規形が含む決定。`PrivacyDecisionBinding.canonicalContent` :318-333）。
- 登録: 新しい署名（文書つき）と引き継ぐ確認済みの署名を組として 1 回の要求で登録する。新しい署名は `REGISTERED`、組も `REGISTERED`。
- **決定の束縛（digest と構造）の入力**（review P2-N1）: 組の種類の digest・構造の検査は、**現在 `CONFIRMED` の組の全ての member** の参照・日付・構造化された値を入力にし、member の署名の行の状態（`EXPIRED`・`REVOKED`、`PURGED` の後も残る参照と日付）を見ない。member の有効性（署名日・失効日・取消し）は、その決定を要する開放単位の `SignOffConditionEvaluator` だけが判定する（P1-2 の「開放単位が要る決定だけ」）。そのため D-06 の行が `EXPIRED`・`REVOKED` になっても D-09 の digest は合ったままで、保持・保全は閉じず、D-06 を要する DSAR だけが閉じる。
- 組の member の署名を 1 件だけ取り消しても（`REVOKED`）組は `CONFIRMED` のままにし、その種別を要する開いている開放単位だけを同じ transaction で `AUTO_CLOSED(SIGN_OFF_REVOKED)` にする。組の取消し（組を `REVOKED`）は、組の種類を要する全ての開放単位を閉じる。取り消した member を新しい署名に替えるには、新しい組を登録・確認する（組に属する種別は 1 件ずつ確認できない）。
- 確認（組ごとに 1 回、登録者と別の privacy-admin）: §6.5 の手順。組の中の digest の整合（D-09 の `approvalDigest` = 組の D-01・(D-06)・D-09 から `PrivacyDecisionBinding.computeDigest` :305-311 で計算した値）を確かめ、合わなければ 400。全ての member の署名日が今日以前であることも確かめる（§2.6）。 組の種類が整合の検査を要する（`BundleKind.consistencyCheckRequired`。`NF07_DECISION_BINDING` は要する）のに検査の実装が無ければ、確認は 409 `error.featureEnablement.bundleCheckMissing`（`evaluator.missing` と同じく fail-closed。実装 review rb-fe-a1 P3-3）。
- 組に属さない種別（NF07 の D-02・D-03・D-05、NF06 の admission、NF05 の DG-05 等）は、従来どおり 1 件ずつ登録・確認する（どれも digest の内容が自分の中で閉じるか、配備の設定と照合する）。

### 2.6.2 `t_feature_sign_off_extension`（証拠の類の延長の記録。review P2-N2）

```sql
CREATE TABLE t_feature_sign_off_extension (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id       VARCHAR(100) NOT NULL,
  sign_off_id     BIGINT       NOT NULL,
  request_id      BIGINT       NOT NULL,   -- 延長の申請（request_type = SIGN_OFF_EXTENSION）
  old_expires_on  DATE         NOT NULL,
  new_expires_on  DATE         NOT NULL,
  requested_by    BIGINT       NOT NULL,
  confirmed_by    BIGINT       NOT NULL,
  confirmed_at    DATETIME(6)  NOT NULL,
  CONSTRAINT ck_fe_extension_separation CHECK (confirmed_by <> requested_by),
  CONSTRAINT ck_fe_extension_dates CHECK (new_expires_on > old_expires_on),
  CONSTRAINT fk_fe_extension_sign_off FOREIGN KEY (sign_off_id) REFERENCES t_feature_sign_off (id),
  CONSTRAINT fk_fe_extension_request FOREIGN KEY (request_id, requested_by, confirmed_by)
      REFERENCES t_feature_enablement_request (id, requested_by, decided_by),
  INDEX idx_fe_extension_sign_off (sign_off_id, id)
);
-- 追記専用（BEFORE UPDATE / DELETE の trigger 2 件）
```

- 延長は新しい署名の行を作らない。署名の行の `expires_on` を同じ行で更新し（`stored_name`・SHA-256・署名日は変えない。`uk_fe_sign_off_stored_name` はそのまま）、同じ transaction でこの表に 1 行を足す。延長の申請は `t_feature_enablement_request`（`request_type='SIGN_OFF_EXTENSION'`、`target_key='signoff:<id>'`、`proposed_value` は新しい失効日）を使い、申請者以外の有効な管理者の承認（`decided_by`）で確定する。同じ署名に未決の延長は 1 件だけ（`pending_slot`）。
- 対象は `kind_class=EVIDENCE` で `CONFIRMED` の行だけ。新しい失効日の上限は承認の日から 1,095 日。
- 申請と承認の時の指紋は、対象の署名の `id:version`・状態・類・失効日と新しい失効日から計算する（版 `fe-extension-v1`）。承認の時に、まだ `CONFIRMED` で証拠の類であること、指紋が申請の時と同じことを確かめ、違えば申請を `STALE` で終える（spec の micro review 88630af1 の P3-M1）。NF07 の種別の延長は申請者・承認者とも privacy-admin に限る（J-05。段階 C で有効にする）。
- 消去（§6.6）はその行の最後の状態と失効日で判定する（延長した行は文書を共有しないため、旧い期限で文書が消えることは無い）。

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
- trigger は event・組の member・延長の記録で計 6 件増える。RB の trigger と表の件数が出る全ての箇所（§1.2・§1.7 の DBA-1・W-1 の行・§3.4 等。grep で探す）、`dba-prelaunch-draft.md`、`FlywayProdFreshInstallMySqlTest` の期待値を同じ change で直す。
- 同じ内容（ID・開放単位・遷移・理由の code・世代・時刻。氏名と自由記述を含めない）を INFO log `FEATURE_ENABLEMENT_EVENT` にも出す（DB の外に残し、PITR の後の照合に使う。§13.1）。
- 保存期間: 監査の記録として削除しない（`t_audit_log` と同じ扱い）。`reason`・`decision_reason` は自由記述のため、画面で「個人名・連絡先を書かない」と案内し、500 字に制限する。

### 2.8 menu・権限・data element の seed（V203 の後半）

- `m_menu`: `feature-enablement`（名称「機能の開放」、`path_prefix` `/feature-enablement`、`api_prefix` `/api/feature-enablement`）。`t_role_menu`: `管理者` だけ。**`t_permission_group_action` に行を足さない**（管理者は `role-admin` の `*`。管理者以外の group に `feature-enablement.*` を seed しない。CLAUDE.md）。
- `m_pii_data_element`: `FEATURE_SIGN_OFF_SIGNER_NAME`（`t_feature_sign_off.signer_name`）と `FEATURE_SIGN_OFF_DOCUMENT`（署名の文書）の 2 件を V198（:25-28）と同じ列で登録する（分類・owner・inventory 状態は UNKNOWN、保持期間は持たない。D-02 の署名で確定する）。`subject_attribution` だけは、消去の経路（§6.6 の D17）を hold の gate が判定できるよう `NOT_ATTRIBUTABLE` にする（署名者は F2 provider で本人を導出できず、DATA_ELEMENT hold がある時だけ消去を止める。UNKNOWN のままだと、hold 機構を使った tenant で消去が止まり続け、`PrivacyHoldPathClassificationTest` の「接続した経路の data element は分類済み」の規則にも反する。FE-A01 の実装の判断）。署名者が SUBJECT hold の本人でもこの2件は SUBJECT hold で守られない残余があり、D-05 owner の受理事項・Plan Review で Privacy が確認する項目として扱う（privacy plan の R-18・RB §4.5。実装 review rb-fe-a1 P3-12）。DATA_ELEMENT hold の保存名は `HoldSubjectResolver` が `t_feature_sign_off.stored_name` から解決する（D10。同 P2-1）。`privacy-retention-dsar/pii-inventory.md` に DB-1xx の行を足す。

### 2.9 条件の評価結果は永続化しない（推奨）

- 毎回の評価の結果は memory（最後の評価の結果と時刻）にだけ置く。DB に残すのは、申請・承認の時の要約（`condition_summary`）と、自動閉鎖・副入口の閉鎖の理由の code（履歴）と、評価できない状態の始まり（`unverifiable_since`。成否が変わった時だけ書く。全 instance で閉鎖を一致させるため。§7.4）である。
- 理由: 定期の評価は 13 件×5 分ごとで、毎回書くと履歴が意味の無い行で埋まり、backup の静止中は書けない（静止の間も評価は動く）。監査で要るのは「何を根拠に開いたか」と「なぜ閉じたか」で、それは遷移の行に残る。

### 2.10 H2 の同期と版番号の連動

- `src/test/resources/sql/schema-feature-enablement-h2.sql`（新規）: V203 の表を `CREATE TABLE IF NOT EXISTS`・COMMENT なし・同じ生成式（H2 は `STORED` を付けない。先例 `schema-backup-quiesce-h2.sql:16`）・CHECK・一意索引・FK（複合 FK を含む）で作り、menu と data element の seed も入れる（trigger は作らない）。`application-test.yml` の `schema-locations` の V202 の変種の後へ足す。
- `src/test/resources/sql/engineer-schema-h2.sql`: event → sign_off_bundle_member → sign_off_bundle → sign_off_extension → sign_off → setting → state → request → generation の順に DROP して作る。新しい mapper の全てに H2 の表があること（`AllMappersSchemaSweepTest`）。
- 契約試験 `FeatureEnablementSchemaContractTest`: V203 と H2 の変種の列・生成式・CHECK の一致を固定する（先例 `BackupQuiesceSchemaContractTest`）。
- 版番号に連動するもの（共通規則の 5）: `LatestMigrationVersion`（`LATEST = "203"`）、`ProductionSchemaVersionGuardTest`、RB の件数（versioned・SQL・trigger）、`DeployRunbookMigrationCountTest`、`FlywayProdFreshInstallMySqlTest`。grep `202` で全てを探す。

## 3. 判定層

### 3.1 源の切替え

```java
/** 開放状態の源。hot path から呼ばれ、DB に往復しない。 */
public interface FeatureEnablementStateSource {
    /** 開放状態・総スイッチ・有効期限・一時の閉鎖だけで見た開閉（親と実行時条件は FeatureSwitches が掛ける）。 */
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

- **親が DB の源・子や設定がまだ property の源の時**（実装 review rb-fe-a2 P2-1・P2-2）: 親は運転中に開くため、子や設定の起動時の検査・開放の条件の判定は親の実効値や DB の設定に頼れない（親の閉じた起動では子の検査が働かず、後から親を開くと検査を経ずに子が有効になる。条件の判定が DB の設定（空）を見ても、実際の挙動は property の値で動く）。段階 A では該当する2件（NF01 二者確認の ON、NF02 顧客ポータルの `visible-since`）を、子・設定が DB 管理になるまで prod の起動時検査で拒否する（`FeatureEnablementTransitionalRejections`。照合・RB §1.0 と同じ。FE-B01・FE-B03 で外して旧い変数の正式の拒否に替える）。後の段階で同じ形（親だけを DB 管理にする）が生じたら、子の起動時検査を親の実効値と無関係にするか、同じく暫定で拒否する。

### 3.2 snapshot

```java
record FeatureEnablementSnapshot(long generation, Instant loadedAt, Instant verifiedAt, boolean masterAllowed,
        Map<Feature, UnitView> units,               // 開放状態と openUntil
        Map<String, String> settings,               // 設定
        Map<String, ConfirmedSignOff> signOffs,     // 確認済みの署名（種別 → 参照・期間・構造化された値）
        Map<String, BundleView> bundles) {}         // 確認済みの組（種類 → 全ての member の参照・日付・値と各 member の状態。§2.6.1）
record UnitView(EnablementState state, LocalDate openUntil, boolean restartPending, Instant unverifiableSince) {}
```

- `openUntil` は、その開放単位が要する署名（目録。NF07 は開放単位ごとに要る決定だけ: 保持・保全は D-01・D-09、DSAR は D-01・D-06・D-09）の最も早い `expires_on`（排他）。**要る署名が 1 つでも無ければ `LocalDate.MIN`（閉）**（review P2-3）。hot path はこれと JST の今日を比べるだけで、署名の失効を即時に反映する（scheduler が止まっていても効く）。
- `verifiedAt` は最後に DB を読めた時刻で、世代が変わらず読み直さなかった poll でも更新する。古さはこれで判定する（review P3-6）。世代の行が無い（最初の書込みの前）は世代 0 として読めたことにする。
- `unverifiableSince` が非 NULL の開放単位は閉（評価できない状態の共有。§7.4）。
- `FeatureEnablementSnapshotLoader.load(tenant)` は 4 回の照会（世代、開放状態、設定、確認済みの署名）で作る。申請・承認・閉鎖・署名の確認等の書込みの transaction も、世代の行を lock した後にこの loader で同じ transaction の中から読み直し、条件の評価と指紋はその読直しで行う（memory の snapshot を使わない。READ COMMITTED で最新の commit を見る。review P2-3）。
- **最初の読込み**: `FeatureEnablementSnapshotHolder` の初期化（`@PostConstruct`。DataSource の後）で同期して 1 回読む。`FeatureSwitches` はこの holder に依存するため、`FeatureSwitches.logStates`（`@PostConstruct`）と起動時検査（`SmartInitializingSingleton`）は読込みの後の状態を見る。読めなければ空の snapshot（全て閉）で WARN を出し、refresher が読み直す。RB が起動の状態を確かめる log は `FeatureEnablementStartupEvaluator` の `[機能の開放] 起動時の評価` を正とする（review P3-6）。
- `FeatureEnablementSnapshotHolder` は `AtomicReference` で持つ。読取りは lock を取らない（`SystemConfigServiceImpl.getString` の `synchronized` を使わない理由）。

`DatabaseFeatureEnablementStateSource.isOpen(f)`:

```
s = holder.get()
if s == null or now - s.verifiedAt > maxStaleness: return false        // fail-closed（§12）
if !s.masterAllowed: return false
u = s.units[f]; if u == null or u.state != OPEN: return false          // OPEN_PENDING_RESTART は閉
if !(today(JST) < u.openUntil): return false
if u.unverifiableSince != null: return false                          // 評価できない（全 instance で共有）
if overlay.contains(f): return false                                   // この instance の一時の閉鎖（書込み待ち等）
return true
```

### 3.3 反映（同じ instance・他の instance・古さ）

- **同じ instance**: 書込みの transaction に `TransactionSynchronization.afterCommit` を登録し、commit の後に snapshot を読み直して差し替える（要求の応答より前）。読み直しに失敗したら、変更した開放単位を一時の閉鎖にし、次の poll に任せる。
- **他の instance**: `FeatureEnablementSnapshotRefresher` が専用の thread で `app.feature-enablement.refresh-interval`（既定 5 秒）ごとに世代の 1 行を読み、変わっていれば snapshot を読み直す。`@Scheduled` を使わない（`app.scheduling.enabled=false` で scheduler を一括で止めた時に、全ての開放単位が古さで閉じないため。読むだけで書かない）。`SchedulingDisabledDrainTest` の既知の一覧に理由つきで足す（RB-QUIESCE の `BackupQuiesceTicker` と同じ扱い）。
- **古さ**: 最後に読めた時刻（`verifiedAt`）から `app.feature-enablement.max-staleness`（既定 60 秒、10 秒〜300 秒）を超えたら、全ての開放単位を閉とする。DB の状態は変えず、読めれば戻る。`refresh-interval`（既定 5 秒、1 秒〜30 秒）は `max-staleness` の半分より短いことを bind の時に検査する（`@Validated`。review P3-13）。
- **差替えの順**: 読込みは読み始めの順番を採り、後に読み始めた snapshot を差し替えた後は、それより前に読み始めた snapshot で上書きしない（poll の読込みが commit の前に始まり、commit の後の読直しより後に終わった時に旧い世代へ戻さない。実装 review rb-fe-a1 P2-3）。世代の値でなく読み始めの順で比べるのは、PITR の後（§13.1）や試験の rollback で DB の世代が小さくなっても読み直せるようにするため（DB の世代は commit の順に増えるため、後に読み始めた snapshot の世代は前のものより小さくならない）。
- **読めなかった読込みも障壁にする**（実装 review rb-fe-a1 1a4fd48d 差分 P3-N2）: 読込みが失敗したら、その読み始めの順番を「最後に差し替えた順番」として記録し、それより前に読み始めた読込みが後で成功しても差し替えない（commit の後の読直しが失敗した時に、commit の前に読み始めた poll の読込みが旧い状態を差し替えて一時の閉鎖 `REFRESH_PENDING` を外す二重故障の窓を閉じる）。一時の閉鎖は、失敗した読込みより後に読み始めた読込みがまだ差し替えていない時だけ付け、外すのは差し替えた時だけにする（順番の判定と付け外しは同じ lock の中）。失敗より後に読み始めた読込みは commit の後に照会したため、その commit を含む。
- 多 instance の版の失効は世代で行う（TTL ではなく版。TTL は古さの上限としてだけ使う）。現在は単一 instance（SRE-5）。

### 3.4 `FeatureSwitches` の変更

- `isConfiguredOn(feature)`（:90-97）を `source.isOpen(feature)` に置き換える。`isEnabled`（:61-69）の親との論理積と実行時条件はそのまま。
- `new FeatureSwitches(FeatureSwitchProperties)` は property の源で包んで残す（`new` で作る既存の試験のため）。
- `logStates`（:40-55）を、起動時に加えて snapshot の差替えで実効値が変わったたびに出す（`[機能スイッチ] 状態: …`。並びと形式は現行のまま。NF05 の 2 件を足す）。
- `Feature` に `NF05_INBOUND`・`NF05_OUTBOUND` を足す（prefix・menu は持たない。`FeatureSwitchFilter` は何も遮断せず、inbound の 404 は既存の `ExternalApiDisabledFilter` が行う）。`propertyName()` は `integration.hub.public-api.enabled`・`integration.hub.external-transport.enabled`、`environmentVariable()` は現在の名前を返す（旧い変数の拒否に使う）。`FeatureSwitchProperties.toggle` は NF05 の property を返す。`FeatureSwitchRouteCoverageTest`・`FeatureSwitchDefaultsTest` の一覧を直す。

### 3.5 `FeatureRuntimeCondition` の拡張

- 現在の `satisfied()` に加えて、既定の method `Optional<String> unsatisfiedReason()` を足す（画面と log の理由の code）。既存の実装（`PrivacyDecisionWindowMonitor`・`PrivacyDsarDecisionWindowCondition`）はそのまま動く。
- 一時の閉鎖は 2 層にする（review P2-2）:
  - **共有**: 開放状態の行の `unverifiable_since`（DB）。評価できない状態を全ての instance が snapshot で同じに閉じる。始まりの時刻から自動閉鎖までの時間を数えるため、再起動と ShedLock の持ち主の移動で数え直しにならない。
  - **instance の memory**（`FeatureEnablementOverlay`。`FeatureRuntimeCondition` の実装。開放単位ごとの理由の集合）: `unverifiable_since` を DB に書けない間の評価できない状態と、**自動閉鎖の書込み待ち**（`AUTO_CLOSE_PENDING`。sticky: 条件の成否に関係なく、DB に `AUTO_CLOSED` を書けるまで外さない）。後者は DB に書けない間だけの状態で、同じ DB に書けない他の instance も開く遷移はできない。
- NF05 の法人の未解決は、port を開く前の `FeatureEnablementStartupEvaluator` で判定し、`AUTO_CLOSED` にする（`ApplicationReadyEvent` を使わない。review P3-5。§9.2）。

### 3.6 hot path の性能

- filter・ControllerAdvice の 1 回の判定は、`AtomicReference` の読取り・Map の参照・日付の比較・overlay の集合の参照だけで、DB の照会は 0 回（要件 FE-AC-006-02 を照会の数で固定する）。
- JST の今日は `LocalDate.now(clock.withZone(Asia/Tokyo))`。

## 4. 条件の評価器

### 4.1 目録（`FeatureEnablementCatalog`）

code の正本。開放単位ごとに次を持つ（要件 §6）:

- 親、`restartRequired`（NF05 だけ true）、`privacyUnit`（NF07 の 2 件。§11.3）。
- 要る署名の種別の一覧。種別ごとに: 署名者の役割（許す値）、類（決定 `DECISION` / 証拠 `EVIDENCE`）と有効期限の上限（決定 366 日・証拠 1,095 日。§6.5）、構造化された値の schema と検査（§6.3）、他の開放単位との共用、条件付き（例: `NF02_PORTAL_INVENTORY` は公開範囲を設定する時だけ）、属する署名の組（§2.6.1）。
- 条件ごとの scope（§4.2）と、code ごとの「評価できない類」の分類（§4.2）。
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
    /**
     * CONTINUOUS: 開いている間も評価し、外れたら自動閉鎖する。
     * OPEN_ONLY: 申請・承認の時だけ開閉を左右し、その後は警告だけ（業務の条件。D-9 の NF02 の暦）。
     * OPEN_TRANSITION: 開く遷移（申請・承認と、OPEN_PENDING_RESTART を反映する起動）でだけ判定する技術上の前提
     *                  （NF05 outbound の barrier の OFF の記録）。その後は判定も警告もしない。
     */
    public enum Scope { CONTINUOUS, OPEN_ONLY, OPEN_TRANSITION }
    public enum Status { SATISFIED, UNSATISFIED, UNVERIFIABLE, NOT_APPLICABLE }
}

public record FeatureEvaluationContext(String tenantId, LocalDate businessDate, Clock clock,
        FeatureEnablementSnapshot snapshot, Purpose purpose, Set<Feature> openAfterChange,
        boolean remoteChecksAllowed) {
    /** BOOT_PENDING: NF05 の OPEN_PENDING_RESTART を反映する起動（OPEN_TRANSITION を判定する）。 */
    public enum Purpose { STARTUP, BOOT_PENDING, PERIODIC, CHANGE, REQUEST, APPROVAL, DISPLAY }
}
```

- `code` と `detailCodes` は固定の文字列（既存の違反 code をそのまま使う。例: `decisionBinding.d01.expiresOn.expired`、`privacyAdmins.insufficient[current=1;required=2]`、`dsar.dueDaysUnset`、`state=MISSING`）。値・digest・username・例外の本文を含めない（`PrivacyProductionConfigurationValidator` の message の規則と同じ）。
- `ADVISORY` は警告だけで、開閉を左右しない（NF10 の scheduler 無効）。NF02 の暦は `ADVISORY` ではなく `OPEN_ONLY` の check で、期限の接近と喪失は §7.4 の警告になる。
- `Scope.OPEN_ONLY` の check は、`Purpose.REQUEST`・`APPROVAL` の時だけ成否を開閉の根拠にする。`STARTUP`・`PERIODIC`・`CHANGE` の評価で `UNSATISFIED`・`UNVERIFIABLE` になっても自動閉鎖・一時の閉鎖にせず、警告（§7.4）として扱う。現在 `OPEN_ONLY` は NF02 の全社共通カレンダーだけ（§18 J-16。決定済み）。
- `Scope.OPEN_TRANSITION` の check は、`Purpose.REQUEST`・`APPROVAL`・`BOOT_PENDING` の時だけ判定し、それ以外では評価もしない（review P1-1）。現在は NF05 outbound の「barrier の行の phase が `OFF` で `NF05_OUTBOUND_OFF` の監査がある」だけ。開いた後に barrier が `QUARANTINING`・`ON` へ進むのは正常のため、これを継続の条件にすると開いた直後に自動閉鎖してしまう。D-9 は業務の条件についての決定で、この技術上の前提を妨げない。他の check は全て `CONTINUOUS`。
- **評価できない類の分類**（review P2-1）: 目録が code の型（例: `*.unverifiable`、`*.unverifiable[...]`、`journal.unreachable`、`journal.timeout`、`evaluator.*`、`state=STATE_IO_ERROR`）を「評価できない類」として持ち、registry は評価器が `UNSATISFIED` で返した check でも、code がこの類なら `UNVERIFIABLE` に直す。既存の検査が照会の失敗を例外にせず違反 code で返す（`validatePrivacyAdministrators` の `privacyAdmins.unverifiable`、`PrivacyJournalStartupVerifier.inspect` の `journal.unreachable` 等）ため、例外だけを見ると一度の通信の失敗で自動閉鎖してしまう。分類の漏れは `FeatureConditionCodeClassificationTest` が、各評価器の試験で出し得る code の一覧と突き合わせて検出する。
- `remoteChecksAllowed=false`（世代の lock を持つ transaction の中の評価。`Purpose.CHANGE`・`REQUEST`・`APPROVAL`）では、Journal の権限の再検査・truststore の読取り等の外部への検査を行わず、最後の結果（memory）を使う（lock を持ったまま外部を待たない。review P3-20）。申請と承認は、世代の行の lock を取る前に対象の開放単位の `FeatureConditionEvaluator.refreshRemote(Feature)`（既定は何もしない）を呼んで外部への検査を新しくし、lock の中では `remoteChecksAllowed=false` で最後の結果を読む（段階 C の review rb-fe-c P2-6。NF07 は `PrivacyJournalStartupVerifier.reverify()`。間隔の下限は再検査の側が守る）。`remoteChecksAllowed=true` は lock を持たない評価（`STARTUP`・`PERIODIC`・`DISPLAY`）だけ。**外部への検査を持つ評価器は、段階 D（NF05 の truststore 等）も含めて `refreshRemote` で行い、`remoteChecksAllowed=true` の時だけ外部へ出る**。`refreshRemote` の失敗は WARN だけで、lock の中の評価が最後の結果で判定する。application DB の接続は transaction の始めから持つが、世代の行の lock は持たない。
- `openAfterChange` は、承認しようとしている開放単位を含めた「変更の後に開いている開放単位」。NF07 の capability の判定（保持・保全が開いている日に DSAR を足す等）に使う。
- `FeatureConditionRegistry` は全ての評価器を集め、開放単位ごとに `MASTER`・`PARENT`・`SIGN_OFF` の共通の評価器（§4.3）と固有の評価器の結果を並べる。評価器の例外は、その評価器の全ての check を `UNVERIFIABLE`（code `evaluator.unverifiable[<評価器の名前>]`）にする。固有の評価器を要する開放単位（NF01 二者確認・NF02・NF05・NF06・NF07）に評価器が無ければ `evaluator.missing` の `UNVERIFIABLE` にする。

### 4.3 共通の評価器

- `MasterSwitchConditionEvaluator`: 総スイッチ（§10）。
- `ParentConditionEvaluator`: 親の開放状態が `OPEN`（`openAfterChange` を含める）。
- `SignOffConditionEvaluator`: 目録の要る種別ごとに、確認済みの署名があり、`signed_on ≤ 今日 < expires_on`。`validUntil` は `expires_on`。署名日前・失効は `temporal=true`。条件付きの種別は、条件（例: 公開範囲の値）を snapshot から判定する。組に属する種別は、確認済みの組の member であることも要する。
- `SecretPresenceConditionEvaluator`: 目録の鍵の有無と形式（既存の部品の判定を呼ぶ。値は読まない・返さない）。

### 4.4 起動時検査の判定の再利用（PR #130 の形）

PR #130 は起動時検査（`ProductionSecurityConfigurationValidator`・`PrivacyProductionConfigurationValidator`）に `AdministratorStartupConditions` を実装させ、`validate` と同じ私有の method を呼ばせた（`PrivacyProductionConfigurationValidator.checkAdministratorConditions` :184-194）。本機能も同じ形を取る:

- 起動時検査の class に `FeatureConditionEvaluator` を実装させ、`validate` が使っている検査の method をそのまま呼ぶ。条件の写しを作らない。
- 起動時の `validate` は、prod でも機能の条件では例外を投げず、評価の結果を log に出すだけにする（起動時の評価と自動閉鎖は `FeatureEnablementStartupEvaluator` が行う。§7.1）。機能と関係しない検査は従来どおり例外にする。
- 検査の class が `@Profile("prod")` の場合（`PrivacyProductionConfigurationValidator`・`ImportAdmissionStartupValidator`・`ImportRowReferenceKeyStartupValidator`・`PrivacyTrustStartupValidator`）、評価器も prod にだけある。dev・test で DB の源を使う試験は、その開放単位を「評価器が無い」として扱うか、試験用の評価器を与える（§16）。判定の method が profile に依らない静的な部品（`ImportAdmissionApproval.evaluate`、`PrivacyDecisionBinding.evaluate`、`LifecycleDualConfirmationReleaseGate.acceptable`、`IntegrationHubProductionEnablementPolicy` の静的な method）にある場合は、profile に依らない評価器からそれを呼ぶ。

### 4.5 条件の指紋

- 申請の時と承認の時に、開放単位ごとに次の正規形の SHA-256 を計算する（UTF-8、LF で終わる行）:
  - 1 行目 `fe-condition-v1`、2 行目 `unit=<key>`、`type=<OPEN|SETTING_CHANGE>`、`value=<提案の値か空>`、要る署名の種別の昇順に `signoff.<kind>=<id>:<version>`、要る組の種類の昇順に `bundle.<kind>=<id>:<version>`、親の `parent=<key>:<state>`。
- 自動検査の結果（人数等）は指紋に入れない（成否は承認の時に評価し直すため。人数の増減で陳腐化させない）。
- 承認の時の指紋が申請の時と違えば、申請を `STALE` で終える（要件 FE-RQ-003）。

## 5. 申請・承認・却下・取下げ・閉鎖

### 5.1 lock の順と transaction

- 全ての書込みは次の順で lock する: (1) 世代の行、(2) 開放状態の行（親→子、同じ階層は key の昇順）、(3) 申請の行、(4) 署名の行（id の昇順）、(5) 設定の行、(6) NF05 outbound の barrier の行（`m_system_config` の `integration.hub.outbound.runtime.v1`）。(1) で tenant の中の変更が直列になるため、本機能の表どうしでは deadlock しない。(1) は最初から排他 lock で取る（§2.2 の no-op の upsert。共有 lock を経て昇格させると (1) 自身が deadlock する）。実 MySQL では `FeatureEnablementConcurrencyMySqlTest` が、二つ目の書き手が一つ目の commit まで排他 lock を待ち、deadlock しないことを lock の待ちの観測で確かめる。
- 分離レベルは READ COMMITTED（条件の評価で他の表の最新の commit を読むため。`WriterIsolationPolicyTest` の `READ_COMMITTED_WRITERS` に足す）。
- 監査は同じ transaction の `recordDomainEventRequired`（`AuditLogServiceImpl:160-176`）。失敗すれば遷移ごと rollback する。
- 世代の行を lock した直後に、同じ transaction の中で snapshot を読み直す（§3.2）。続けて、読み直した状態で `openUntil` を過ぎた開いている開放単位（scheduler が止まっていて自動閉鎖が書かれていないもの）を先に `AUTO_CLOSED`（`SIGN_OFF_EXPIRED`。要る署名が欠けて `openUntil` が無い時は `SIGN_OFF_MISSING`。FE-A07 で区別した）にする。その後に要求の遷移を行う（署名の差替えで、失効した開放単位を承認なしに戻さない。review P2-2 (b)）。
- 通知は commit の後に best-effort で送る（失敗しても遷移は戻さない）。

### 5.2 申請（`POST /api/feature-enablement/requests`）

1. 行為者の検査（§11.2。NF07 は §11.3）。総スイッチ（閉なら 409 `error.featureEnablement.masterClosed`）。
2. lock（§5.1）と読直し。同じ対象の `expires_at` を過ぎた未決を `EXPIRED` にする（scheduler が止まっていても未決の枠が塞がらない。review P3-7）。開放状態が `CLOSED`・`AUTO_CLOSED`・`MANUALLY_CLOSED` でなければ 409。
3. 条件を `Purpose.REQUEST` で評価する（全ての scope を含む）。全ての条件（`ADVISORY` を除く）が `SATISFIED`（`NOT_APPLICABLE` を含む）でなければ 409 と理由の code。
4. 申請の行を `PENDING` で作る（`pending_slot` の一意索引で 2 件目は失敗 → 409）。指紋・要約・期限（既定 7 日）を書く。履歴 `REQUESTED`・監査 `FEATURE_OPEN_REQUESTED`。
5. commit の後、申請者以外の有効な管理者（NF07 は privacy-admin）へ通知する。

### 5.3 承認（`POST /requests/{id}/approve`、本文 `expectedVersion`）

1. 行為者の検査。承認者≠申請者（403）。総スイッチ。
2. lock（§5.1）と読直し。申請が `PENDING` で期限内、version が一致（違えば 409）。
3. 条件を、読み直した状態で `Purpose.APPROVAL` で評価し直す（全ての scope を含む。`openAfterChange` に対象を含める）。成り立たなければ 409（申請は未決のまま）。
4. 指紋を読み直した状態で計算し、申請の指紋と違えば申請を `STALE` にし、履歴と監査を書いて **commit してから** 409 `error.featureEnablement.requestStale` を返す（例外で返すと `STALE` と監査も rollback するため、service は結果の値を返し、controller が 409 にする。review P3-8）。
5. **先に**申請を `APPROVED`（`decided_by`・`decided_at`）にし、その後で開放状態を `OPEN`（NF05 は `OPEN_PENDING_RESTART`）にし、`opened_by`・`approved_by`・`open_request_id`・`opened_at` を書く（複合 FK `fk_fe_state_request` は文ごとに検査されるため、この順でないと失敗する。設定の変更と延長も同じく、申請を先に `APPROVED` にしてから設定の行・延長の記録を書く。review P3-N7）。世代を上げる。履歴 `APPROVED`（要約つき）・監査 `FEATURE_OPEN_APPROVED`。
6. commit の後: snapshot の差替え、通知、INFO log。

### 5.4 却下・取下げ・期限切れ

- 却下: 申請者以外の有効な管理者。理由が必須。`REJECTED`。
- 取下げ: 申請者だけ。`WITHDRAWN`。
- 期限切れ: 日の始まりの処理と、新しい申請の transaction（§5.2 の 2）が `expires_at` を過ぎた `PENDING` を `EXPIRED` にする。
- どれも開放状態は変えない。

### 5.5 閉鎖（`POST /units/{key}/close`）
 理由は `closed_reason`（code の列）に入る区分で、目録の `MANUAL_CLOSE_REASONS`（`MANUAL_OWNER_DECISION`・`MANUAL_SECURITY_INCIDENT`・`MANUAL_CONDITION_DOUBT`・`MANUAL_OPERATION_ERROR`・`MANUAL_VERIFICATION`・`MANUAL_OTHER`）だけを受ける（system の理由の code と混ぜない。自由記述は受けない。画面は選択肢で出す。実装 review rb-fe-a1 P3-4）。
- 有効な管理者 1 名で行える（閉じる方向は安全側。§18 J-19）。理由が必須。break-glass・bootstrap の session は不可（§11.2）。
- 開放状態を `MANUALLY_CLOSED` にし、開いている子を `AUTO_CLOSED`（`PARENT_CLOSED`）にし（§18 J-18）、未決の申請があれば `CANCELLED` にする。NF05 outbound は barrier の行を OFF に書き直す（§9.3）。
- NF01 二者確認の閉鎖では、確認の dialog に「二者確認を含む有効な template の件数」（FS 4.9.5 の (3)）を出す。
- 手動の閉鎖は、有効な管理者全員へ通知する（1 名でできる操作のため、気づけるようにする。review P3-9）。

### 5.6 設定の変更（`SETTING_CHANGE`）

- NF02 の公開範囲だけ（要件 FE-RQ-010）。申請に値（または未設定）を持たせ、承認で `t_feature_enablement_setting` を書き換える。
- 条件: 確認済みの署名 `NF02_PORTAL_VISIBLE_SINCE_DECISION` の構造化された値 `visibleSince` が申請の値と一致すること。値を設定する場合は `NF02_PORTAL_INVENTORY` も要る。値の形式は `PortalServiceRequestVisibilityPolicy` の解釈と同じ（offset 付き ISO-8601）。
- 指紋に申請の値を含める（§4.5）。
- 実装で決めた細部（FE-B03）:
  - 申請は `FeatureEnablementSettingChangeService.request`（READ COMMITTED。`WriterIsolationPolicyTest` に登録）、承認は申請の種類の拡張点 `FeatureEnablementSettingChangeApprover`（`FeatureEnablementServiceImpl.approve` の transaction の中で呼ばれる）。却下・取下げは共通の処理のまま。`FeatureEnablementServiceImpl` は変えていない（段階 D の閉鎖の hook と衝突しない）。
  - 値の正規化: 前後の空白を除き、空・null は未設定（`proposed_value`・`setting_value` は NULL）。offset 付き ISO-8601 で 100 文字以内でなければ 400 `settingValueInvalid`。今の値と同じ値は 409 `settingUnchanged`。値の同一は解釈した `OffsetDateTime` の `equals`（同じ日時と offset。秒の `:00` の有無の表記の違いは同じとみなす）。
  - 条件の code（固定の文字列。値を含めない）: `setting.visibleSince.decision.{valid|missing|signedOn.future|expiresOn.expired|valueMismatch}`・`setting.visibleSince.inventory.{valid|missing|signedOn.future|expiresOn.expired}`。決定の構造化された値 `visibleSince` が空・無しは「未設定の決定」。申請の時に成り立たなければ 409 `conditionsUnsatisfied`、承認の時は `CONDITIONS_UNSATISFIED`（申請は未決のまま）。
  - 指紋は §4.5 の正規形を `unit=<設定の key>`・`type=SETTING_CHANGE`・`value=<値か空>`・要る署名（決定、値を設定する時は棚卸し）の `id:version` で計算する（親は含めない。NF02 の開閉で陳腐化させない）。
  - 承認は申請を先に `APPROVED` にしてから設定の行を lock（§5.1 の (5)）し、無ければ挿入・あれば版の CAS で `setting_value`（NULL は `set(col, null)`）・`request_id`・`requested_by`・`approved_by`・`approved_at` を書く。履歴 `SETTING`/`CHANGED`、監査 `FEATURE_SETTING_REQUESTED`・`FEATURE_SETTING_CHANGED`（本文は key・設定の有無・申請 ID だけで値を含めない。§11.4。値は設定の行と申請の行にある）、通知 `settingChangeRequested`・`settingChanged`。世代を上げ、commit の後に snapshot を読み直す。
  - 画面: 「設定」tab（今の値・決定の署名の値・棚卸しの有無・未決の申請・「変更を申請」）。承認・却下は「申請」tab の既存の行で行う。読取りは `GET /api/feature-enablement/settings`。各行の `effective` は承認した値が portal の判定に効く源か（DB の源で NF02 顧客ポータルが DB 管理）で、property の源（dev の既定）では tab に「この源では効かない」と出す（復審 rb-fe-b-656d71b8 の P3-5）。
- **継続の条件（§18 J-21。段階 B の復審 rb-fe-b-656d71b8 の P3-1、統括の決定）**: NF02 顧客ポータルの固有の評価器 `PortalVisibleSinceConditionEvaluator`（`Scope.CONTINUOUS`）が、snapshot の今の設定と確認済みの決定の `visibleSince` を比べる。未設定は成立（detail `setting.unset`）、設定の日時が決定の日時と同じか後なら成立（`decision.covers`）、設定の日時が決定より前（`decision.narrower`）・決定が未設定（`decision.unset`）・決定が無い（`decision.missing`）・値を読めない（`decision.unreadable`・`setting.unreadable`）は不成立（code `setting.visibleSince.outsideDecision`）。決定の差替えの確認（§6.5）は依存する開放単位を同じ transaction で評価し直すため、より狭い決定を確認すると顧客ポータルだけが `AUTO_CLOSED(SIGN_OFF_REPLACED_INVALID)` になり、監査 `FEATURE_AUTO_CLOSED`・WARN・管理者への通知が出る（D-4。親の NF02 は開いたまま）。DB を直接変えた場合は次の定期の評価で閉じる。開き直す申請・承認も同じ check を通るため、設定を新しい決定の値へ変える（二人の承認）までは 409。照会は snapshot だけ（0 回）。目録の `specificEvaluatorRequired` は変えない（評価器が無くても共通の条件で判定できる開放単位のまま）。

## 6. 署名の登録と確認

### 6.1 文書の保存

- 新しい `FileKind` `FEATURE_SIGN_OFF`（拡張子 pdf だけ、上限 10 MB。multipart の上限 `max-file-size: 10MB`・`max-request-size: 12MB`（`application.yml:117-118`）の中に収める。review P3-2。`FileKind.java:21` の enum に足す）。PDF の magic byte・末尾の検査は既存の pdf の分岐を使う（`FileKind.java:107-131`）。
- 汎用の経路を種別で拒否する（review P3-3）: `FileKind` に `isDedicatedPathOnly()` を足して `FEATURE_SIGN_OFF`（と server 生成の種別）で true を返す。(1) `FileApiController.upload`（:36-44）が 400 にする（現在は server 生成の種別だけを拒否している）。(2) 汎用の `load`・`rescan` が metadata の `file_kind` で 403 にする（CLAUDE.md の server 生成の規則 1 と同じ形の多重の防御）。専用の経路は `loadDedicated(name, kind)`（`loadServerGenerated` と同じく種別の一致を要する）で読む。
- 登録の API は multipart（文書と JSON）を受け、`FileStorageService.store(byte[], originalName, FEATURE_SIGN_OFF)`（`FileStorageService.java:21`）で保存する。scan が `CLEAN` でなければ `error.file.scanRejected` で登録を拒否し、quarantine の実体と published の写しを消し metadata を論理削除する（署名の行は作らない。誰も claim しない file を残さず、汎用の再 scan で持ち主の無い file が公開されないため。server 生成の規則 3 と同じ。文書は upload し直せる）。
- 登録の transaction が rollback した時は、保存した文書の実体を `FileStorageService.discardUnregistered` で消す（metadata も rollback するため `delete` は使えない。RB §4.4 の D18）。誤って現存の原本の保存名を渡されても消さないよう、呼ぶ側（`FeatureSignOffRegistrar`）が持ち主の署名の行が無いことを、実装が metadata の行がどの tenant にも（論理削除済みを含め）無いことを確かめ（あれば 409 で消さない）、backup の書込み静止の最中は `store`・`delete` と同じく拒否する（実装 review rb-fe-a1 1a4fd48d 差分 P3-N1）。拒んだ実体は孤児の清掃が消す。
- SHA-256 は upload の経路が計算しないため（`FileStorageServiceImpl` は hash を持たない）、本機能が bytes から計算して `content_sha256` に保存する。取得は専用の経路（`GET /sign-offs/{id}/document`）で `FileStorageService.load` の後に計算し直し、大きさと hash が一致しなければ 409（先例 `PrivacyExportPackageService.readVerified` :880-901）。取得も監査する。

### 6.2 file の 2 箇所の登録（CLAUDE.md）

1. `FeatureSignOffFileReferenceProvider implements FileReferenceProvider`（`service/FileReferenceProvider.java:18`）: `PURGED` 以外の行の `stored_name` を claim する。
2. `FileScopeValidationService.assertDownloadAllowed`（:255）と `assertRescanAllowed`（:504-506）の前置きの拒否に `assertFeatureSignOffNotAccessibleThroughGenericFilePath` を足す（先例 `assertPrivacyPackageNotAccessibleThroughGenericFilePath` :257・:519）。署名の表に保存名があれば、role に関係なく汎用の取得と再 scan を 403 にする。専用の経路だけが読む。

### 6.3 種別ごとの検査（登録の時と評価の時）

| 種別 | 参照・構造化された値の検査（現在の検査をそのまま使う） |
|---|---|
| `NF01_DUAL_PRIVACY` | 参照が `LifecycleDualConfirmationReleaseGate.acceptable`（:86-97） |
| `NF07_D01`・`NF07_D02`・`NF07_D03`・`NF07_D05`・`NF07_D06`・`NF07_D09` | 決定 ID・日付の規則（`PrivacyDecisionBinding` の `readDecision` :407-427 等。NF08 と同じ規則）。D-09 の構造化された値: `tenantId`（設定の tenant と一致）・`capabilities`（`retention`/`dsar`）・`approvalDigest`（`PrivacyDecisionBinding.computeDigest` :305-311 の値と一致）。D-06: `dueDays`（1〜366）・`packageMaxAge`（1 時間〜366 日） |
| `NF06_ADMISSION` | 構造化された値（`decisionId`・`tenantId`・`sourceCodes`・`approvalDigest`）と行の日付を `ImportAdmissionApprovalProperties` に写して `ImportAdmissionApproval.evaluate`（:78）。構造的な不足（`structuralViolations` :106）があれば拒否 |
| `NF05_DG05_IN`・`NF05_DG05_OUT` | 決定 ID が `DG-05-IN-[0-9]{8}-[0-9]{2}`・`DG-05-OUT-[0-9]{8}-[0-9]{2}`（`IntegrationHubProductionEnablementPolicy:27,29`）。digest は小文字 hex 64 桁。登録・確認の時は形式だけを検査し、現在の配備の設定の topology と登録の値から `IntegrationHubApprovalDigest.canonicalInbound`・`canonicalOutbound` で計算した値との一致・不一致を画面に出す（配備の設定を承認の後・再起動の前に変える運用を妨げないため。一致の判定は評価の時。要件 FE-RQ-004。review P3-18） |
| `NF02_PORTAL_VISIBLE_SINCE_DECISION` | `visibleSince` が空か offset 付き ISO-8601 |
| その他 | 参照が英数字で始まる 4〜128 文字（`._:/#-`）。placeholder の語を含まない（`LifecycleDualConfirmationReleaseGate` の placeholder の規則を共通の部品に移して使う） |

- 署名者の役割は、種別が許す値（例: `NF07_D01` は `PRIVACY_OWNER`）からだけ選ばせる。
- 有効期限の上限を種別の類で検査する（§18 J-08。統括の決定で (b)。暫定で、最終は各 owner と Privacy が確認する）: 決定の類（`kind_class=DECISION`）は 366 日（NF07 の決定は既存の規則 `PrivacyDecisionBinding.MAX_VALIDITY_DAYS` :48 と同じ）、証拠の類（`EVIDENCE`。受理・実測・review の PASS・手動の確認）は 1,095 日。

### 6.4 登録にも二人の確認を求める理由（推奨）

- 署名の登録は、現在の「署名の後に人が環境変数へ転記し、digest を二人がそれぞれ正規形から計算して照合する」（FS 4.7.4 の3、4.8.2 の #1、DG-05 草案 §1.1）の置き換えである。二人の照合を落とさないため。
- 開いている開放単位の署名を差し替える時は、開放の承認を経ない（D-5: 差替えに再起動も開き直しも要らない）。登録を 1 名でできると、1 名で有効期限を延ばしたり内容を変えたりできてしまう。
- 確認者は文書と、system が正規形から計算した digest（画面に先頭 16 文字を出す）を、文書の記載と照合する。
- 互いに束縛し合う署名（NF07 の D-01・D-06・D-09）は組で確認する（§2.6.1）。1 件ずつの差替えでは、途中の状態で digest が必ず食い違い、開いている NF07 が自動閉鎖して再承認が要る（review P1-2）。組なら二人の確認は 1 回で、差替えは原子的になる。

### 6.5 失効・差替え・取消し

- 失効: `expires_on` の当日（JST 0:00）から無効。hot path は snapshot の `openUntil` で即時に閉じ、定期の評価が `AUTO_CLOSED`（`SIGN_OFF_EXPIRED`）にし、署名を `EXPIRED` にする。
- 失効の接近: `feature-enablement.expiry-warning-days`（`m_system_config`。`/system-config` の `SCHEMAS` に足す。既定 30、1〜366）日前から毎日 1 回、管理者へ通知し WARN を出す。`privacy.approval.expiry-warning-days`（`PRIVACY_APPROVAL_EXPIRY_WARNING_DAYS`）の役割を引き継ぎ、旧い変数として廃止する。
- 確認の時の検査（1 件・組の共通）: (1) 確認者は登録者以外（NF07 の種別は privacy-admin）。(2) **署名日が今日以前**（JST）。今日より後なら 400 `error.featureEnablement.signOffNotYetSigned`（文言で D-8 により署名日に自動で開かないこと、署名日の当日に確認することを案内する。review P2-6）。(3) 上限の日数。(4) 種別ごとの検査（§6.3）。組は組の中の digest の整合も。 (5) 失効日が今日より後（失効日を迎えた署名は登録・確認とも 400 `error.featureEnablement.signOffAlreadyExpired`、延長の申請は 409、延長の承認は条件の不成立。日の始まりの `EXPIRED` の付与の前でも同じ。確認済みの有効な署名を失効した署名で差し替えて開いている開放単位を閉じる誤操作を防ぐ。実装 review rb-fe-a1 P3-2）。
- 差替え（1 件）: 同じ transaction で §5.1 の読直しと失効の先行の閉鎖を行い（review P2-2 (b)）、旧い行を `SUPERSEDED`、新しい行を `CONFIRMED` にし、それを要する開いている開放単位を `Purpose.CHANGE`（`remoteChecksAllowed=false`）で評価し直す。成り立てば開いたまま、成り立たなければ同じ transaction で `AUTO_CLOSED`（`SIGN_OFF_REPLACED_INVALID`）。
- 差替え（組）: 同じ transaction で、旧い組を `SUPERSEDED`、旧い組の member のうち新しい組で置き換わる署名を `SUPERSEDED` にし（引き継ぐ署名は `CONFIRMED` のまま）、新しい組の新しい署名を `CONFIRMED`、新しい組を `CONFIRMED` にしてから、それを要する開いている開放単位を評価し直す（review P1-2）。組の一部だけを `CONFIRMED` にする経路は無い。
- 延長（証拠の類だけ。review P2-N2）: 新しい行を作らず、延長の申請（`SIGN_OFF_EXTENSION`）を申請者と別の管理者が承認すると、同じ行の `expires_on` を更新し、追記専用の `t_feature_sign_off_extension` に申請者・確認者・旧い期限・新しい期限を残す（§2.6.2）。文書・保存名・署名日は変えない。新しい期限の上限は承認の日から 1,095 日。
- 取消し: 確認済みの署名（または組）を有効な管理者 1 名で `REVOKED` にできる（理由が必須）。それを要する開いている開放単位を同じ transaction で `AUTO_CLOSED`（`SIGN_OFF_REVOKED`）。組の member を 1 件だけ取り消した時は組を `CONFIRMED` のままにし、その種別を要する開放単位だけを閉じる（§2.6.1）。取消しは有効な管理者全員へ通知する（review P3-9）。
- 却下: 未確認の署名を登録者以外が `REJECTED` にする。登録者本人は取り下げられる（`REJECTED`、`end_reason=WITHDRAWN_BY_REGISTRANT`）。

### 6.6 個人データと保存期間

- 氏名は任意。一覧の API は氏名を返さず、詳細の API だけが返す。監査・履歴・log に氏名を書かない。
- `SUPERSEDED`・`EXPIRED`・`REVOKED`・`REJECTED` になった時に `purge_after` = その日＋3 年（§18 J-14）を書く（その行の最後の状態と失効日で決める。延長した行は延長の後の失効日を使う）。日次の `FeatureSignOffPurgeScheduler`（`@SchedulerLock`、`void`）が期日を過ぎた行の氏名と元の file 名（`original_name`。氏名を含み得る。review P3-4）を NULL にし、文書の実体と metadata を削除し、`PURGED` にする（SHA-256・参照・日付・役割は残す）。NULL への更新は `UpdateWrapper.set(col, null)` で列を名指しする（review P3-20）。開放の根拠の証拠に 3 年を超える法定の保存が要るかは、J-14 の確認の時に合わせて確かめる。
- 消去の経路は `PrivacyHoldPathGuard` を通す（CLAUDE.md の「personal data を物理削除・purge する新しい経路は guard に配線する」）。batch は実行ごとに 1 回 gate を照会し、hold の対象の署名者・文書は消さない。RB §4.4 の経路の一覧に D17 として足す。
- data element の登録は §2.8。

## 7. 条件の継続評価

### 7.1 起動時

- `FeatureEnablementStartupEvaluator`（`SmartLifecycle`。web server の lifecycle より前の phase）: snapshot を読み、開いている開放単位と再起動待ちを `Purpose.STARTUP` で評価する（`OPEN_TRANSITION` は判定しない。NF05 の `OPEN_PENDING_RESTART` は boot resolver が `BOOT_PENDING` で判定した結果を §9.2 で反映する）。成り立たなければ `AUTO_CLOSED`（理由の code）、総スイッチが閉なら全てを `AUTO_CLOSED`（`MASTER_SWITCH_CLOSED`）。NF05 の法人の未解決もここで判定する（port を開く前。review P3-5）。
- DB に書けない（backup の静止の最中の起動等）場合は、自動閉鎖の書込み待ち（sticky。§3.5）で閉じ、定期の評価が書込みを再試行する。
- 起動の log に `[機能の開放] 起動時の評価: NF01=OPEN, NF02=AUTO_CLOSED(sign-off.NF02_DG02.expired), …` を出す（code だけ）。

### 7.2 定期

- `FeatureEnablementConditionScheduler`:
  - `evaluateAll()`: `@Scheduled(fixedDelayString = "${app.feature-enablement.evaluation-interval:PT5M}")`（1 分〜60 分）、`@SchedulerLock(name = "featureEnablementEvaluation")`、`void`。開いている開放単位と副入口を評価し、自動閉鎖の書込み待ちを再試行する。初回の遅延は `app.feature-enablement.evaluation-initial-delay`（既定 1 分、0〜60 分。起動時の評価は §7.1 が web server の前に行うため重ねない。実装 review rb-fe-a1 P3-5）。署名の消去（§6.6）の cron は `app.feature-enablement.sign-off-purge-cron`（既定 `0 40 3 * * *`、Asia/Tokyo）。どちらも `FeatureEnablementProperties` の範囲・式の検査に入れ、A08 の prod の上書きの拒否の対象にする。
  - `evaluateAtBusinessDayStart()`: `@Scheduled(cron = "5 0 0 * * *", zone = "Asia/Tokyo")`、`@SchedulerLock(name = "featureEnablementDayStart")`、`void`。失効の反映・申請の期限切れ・失効の接近の通知・開放の時だけの条件（NF02 の暦）の期限の接近と喪失の警告（§7.4）。
  - どちらも `FeatureSwitchRouteCoverageTest` の `UNGATED_SCHEDULERS` に理由つきで登録する（機能スイッチで止めない）。
- scheduler が無効（`app.scheduling.enabled=false`）の間は、期限による閉鎖（hot path）と変更時の評価だけが働く（FS 4.1 に書く）。

### 7.3 照会の数（定期の評価 1 回あたりの目安）

| 開放単位 | 照会 |
|---|---|
| 共通（総スイッチ・親・署名） | snapshot から（0） |
| NF02 | 暦 1（日の始まりの警告だけ。閉鎖の根拠にしない） |
| NF02 顧客ポータル | 公開範囲の設定が決定の範囲の内か（snapshot から 0。復審 rb-fe-b-656d71b8 の P3-1） |
| NF01 二者確認 | 証跡種別 2 |
| NF06 | 権限の行 1、法人 1 |
| NF07（保持・保全・DSAR の両方を開いた場合） | group 1、管理者 2、許可行 1、provider 0（memory）、Journal の権限（10 分に 1 回まで。Journal への 1〜2）、trust（memory の文書） |
| NF05 | barrier の行 1（tenant の指紋だけ。OFF の記録は見ない）、法人の未解決 1 |

合計は 5 分ごとに約 15 回で、接続の pool（20）への影響は無視できる。画面の表示は最後の評価の結果を出し、利用者の「再評価」で 1 件だけ評価する（全件をその場で評価しない）。

- NF01 二者確認の証跡種別の2回（FE-B01）は、バイト列の比較（`CAST(CONCAT(...)) NOT IN (...)`）のため索引を使えず、`m_lifecycle_template_task` と `t_lifecycle_task`（論理削除を除く全ての行）を毎回走査する。`EXISTS`・`LIMIT 1` に替えても、揺れの無い通常の状態では全ての行を見て「無い」ことを確かめるため費用は同じで、件数（画面の detail）を残すため `COUNT(*)` のままにした（復審 rb-fe-b-656d71b8 の P3-6）。この照会は定期の評価（5 分ごと）と、署名の確認・差替え等の `Purpose.CHANGE` の評価で世代の行の排他 lock を持ったまま走る。走査の時間は `t_lifecycle_task` の行数に比例し、その間は他の機能の開放の書込みが lock を待つ。初回リリースの規模（タスクは数千〜数万行）では問題にならない見込みで、行数が 10 万を超える、または評価の時間が SRE の負荷の実測（NF05 と共用の `NF05_SRE_LOAD` の範囲外のため別に要る）で目立つ場合は、(1) `Purpose.CHANGE`（`remoteChecksAllowed=false`）では最後の定期の評価の結果を使う、(2) 証跡種別の正規化した列と索引を足す（DDL）、のどちらかで直す（段階 E の後の backlog の候補）。

### 7.4 自動閉鎖の処理

```
if overlay.has(f, AUTO_CLOSE_PENDING):                // sticky（review P2-2 (a)）: 条件を見ずに書込みを再試行する
    writeAutoClose(f, pendingReason); return
check = registry.evaluate(f, ctx)                     // 評価できない類の code は UNVERIFIABLE に直す（§4.2）
        .filter(scope == CONTINUOUS)                  // OPEN_ONLY・OPEN_TRANSITION は閉鎖の根拠にしない
if any UNSATISFIED:
    overlay.close(f, AUTO_CLOSE_PENDING(reason))       // 直ちに実効値を閉じる（書けるまで外さない）
    writeAutoClose(f, reason)
elif any UNVERIFIABLE:
    tx { lock; unverifiable_since が NULL なら now を書く（理由の code）; 履歴 EVALUATION_UNVERIFIABLE; 世代 }
       // 書けなければ overlay.close(f, EVALUATION_UNVERIFIABLE)（この instance だけ）
    if now - unverifiable_since >= 3 × evaluation-interval:  writeAutoClose(f, EVALUATION_UNVERIFIABLE)  // §18 J-10
else:
    unverifiable_since が非 NULL なら tx { NULL に戻す; 履歴 EVALUATION_RECOVERED; 世代 }
    overlay.open(f, EVALUATION_UNVERIFIABLE)            // AUTO_CLOSE_PENDING は外さない

writeAutoClose(f, reason):
    tx { lock; 読直し; 状態が OPEN/OPEN_PENDING_RESTART なら AUTO_CLOSED(reason); 子も AUTO_CLOSED(PARENT_CLOSED);
         NF05 outbound なら barrier を OFF; unverifiable_since を NULL; 履歴・監査; 世代 }
    成功: commit 後に snapshot 差替え → overlay の AUTO_CLOSE_PENDING を外す、通知、WARN errorCode=FEATURE_AUTO_CLOSED
    失敗: AUTO_CLOSE_PENDING を残し、次の周期で再試行
```

- 評価できない状態の始まりは DB の `unverifiable_since` に持ち、全ての instance が snapshot で同じに閉じる（review P2-2 (c)。J-10 の 3 項目）。
- 自動閉鎖の書込み待ち（`AUTO_CLOSE_PENDING`）は instance の memory にあり、再起動で失われ、他の instance へも伝わらない（review P3-N1）。そのため書込み待ちの間は毎周期 WARN `errorCode=FEATURE_AUTO_CLOSE_PENDING feature=<key> reason=<code>` を出す。RB に「DB に書けない間は再起動しない。再起動した場合は、起動の後に、この WARN が出ていて `FEATURE_AUTO_CLOSED` が出ていない開放単位を手動で閉じる」を書く。NF05 outbound では書込み待ちを確定の閉鎖として扱う（§9.3）。自動閉鎖までは「3 回」を時間（評価の間隔の 3 倍）で数えるため、再起動や ShedLock の持ち主の移動で数え直しにならない。
- `OPEN_ONLY` の check（NF02 の全社共通カレンダー）が開いている間に外れても、NF02 と子は開いたままにする。日の始まりの評価で、暦の有効期間の終わりの既定 30 日前（`feature-enablement.expiry-warning-days` と同じ日数）から毎日 1 回、監査 `FEATURE_OPEN_CONDITION_EXPIRING`（`recordDomainEventRequired`）・管理者への通知・WARN `errorCode=FEATURE_OPEN_CONDITION_EXPIRING` を出し、暦が切れた後も同じく毎日警告する（`FEATURE_OPEN_CONDITION_LOST`）。暦を照会できない日（評価できない）は外れたとは分け、同じく毎日 `FEATURE_OPEN_CONDITION_UNVERIFIABLE`（監査・通知 `openConditionUnverifiable`・WARN）を出し、開いたままにする（実装 review rb-fe-a2 P3-2）。暦が無い間の SLA の計算は現行どおり（`ServiceSlaCalculator.isNonWorkingDay` :314-329 が WARN `missing_calendar` を出し、土日だけを休日として数える）。
- 副入口は DB の状態を持たない。成否が変わったら履歴 `SUBGATE_CLOSED`・`SUBGATE_OPENED` と通知を出す（要求ごとの gate は今のまま 404・409 を返す）。

### 7.5 通知・metric・log

- 通知（`NotificationService.publishToUser` を有効な管理者全員へ。NF07 は privacy-admin を含める。dedupe key は開放単位・事象・業務日）: 申請（承認の依頼）、承認・却下、手動の閉鎖、署名の取消し、自動閉鎖、総スイッチによる閉鎖、失効の接近、開放の時だけの条件の期限の接近と喪失（NF02 の暦）、副入口の閉鎖と再開、再起動待ち、利用者が管理者の role を得たこと（§11.1）。
- DSAR が閉じている（自動閉鎖を含む）間に未完了の請求があれば、日の始まりに privacy-admin へ件数と最も近い期限日だけを通知する（法定の期限は受付の時に `due_date` へ書かれて進み続ける一方、期限の通知の scheduler `PrivacyDsarDeadlineScheduler` は閉じている間止まるため。review P3-21）。通知の link は画面「機能の開放」（`/feature-enablement`。menu `feature-enablement`）で、本文は「画面『機能の開放』で開き直すか、期限の管理を手で行ってください。」（閉じている間の `/privacy/dsar` は 404 のため。段階 C の review rb-fe-c P2-5）。
- metric: `ses_feature_enablement_open{feature}`（0/1。実効値）、`ses_feature_enablement_state{feature,state}`、`ses_feature_enablement_auto_closed_total{feature,reason}`、`ses_feature_enablement_snapshot_age_seconds`、`ses_feature_enablement_master_allowed`、`ses_feature_enablement_sign_off_days_remaining{feature}`。tag に ID・氏名・値を載せない。
- log: WARN `errorCode=FEATURE_AUTO_CLOSED feature=<key> reason=<code>`、ERROR `errorCode=FEATURE_ENABLEMENT_MASTER_CLOSED`、WARN `errorCode=FEATURE_ENABLEMENT_SNAPSHOT_STALE`、INFO `FEATURE_ENABLEMENT_EVENT`（全ての遷移。§2.7。PITR の後の照合に使う）。CloudWatch の metric filter は SRE-8 と同じ形で置ける（RB に例を書く）。

## 8. 各機能の改修点

### 8.1 容易な 7 件（NF01・NF02・NF02 顧客ポータル・NF03・NF03 資格番号・NF04・NF10）

- 開閉の判定は全て `FeatureSwitches` 経由のため、源の差替え（§3）だけで実行時に開閉できる。各機能の code は変えない。
- 目録の行（署名の種別・親）と、固有の評価器が要るもの:
  - NF02: `WorkCalendarConditionEvaluator`（本日を含む当年度の全社共通の暦。`ServiceSlaCalculator.findCalendarForScope` :366-400 の 4 段目と同じ条件で `WorkCalendarMapper` を 1 回照会。check は `Scope.OPEN_ONLY`: 申請・承認の時だけ必須で、開いた後は継続の条件にせず NF02 を自動閉鎖しない。期限の接近と喪失は §7.4 の警告。§18 J-16 決定済み）。
    - 実装（FE-A11）: 照会は `WorkCalendarMapper.selectCompanyWideNotEndedBefore(本日)`（全社共通の有効な暦のうち本日より前に終わっていないもの）の 1 回。check の `validUntil` は暦が成り立つ最後の日の翌日で、今の暦の終わりにすぐ続く次の全社共通の暦があれば、その終わりまで延ばす（次年度の暦を登録済みなら期限の接近を警告しない。終わりの無い暦なら期限なし）。
    - 照会に失敗した時は例外を投げず、code `calendar.companyWide.unverifiable`（評価できない類）の `UNVERIFIABLE` を返す。目録はこの code も開放の時だけの条件と宣言する（registry の例外の扱い `evaluator.unverifiable[...]` は継続の条件になり、照会の失敗で NF02 を自動閉鎖してしまうため。D-9）。申請・承認は評価できない条件として 409。
  - NF10: `SchedulingConditionEvaluator`（`app.scheduling.enabled` が true でなければ `ADVISORY`。property が無ければ有効とみなす）。
- 目録は段階 A でこの7件だけに DB 管理の印を付ける（`FeatureEnablementCatalog.STAGE_A_DATABASE_MANAGED`）。prod の起動時検査は、この7件の旧い開閉の変数（`FEATURE_NF01_ENABLED` 等と property の全ての綴り）を拒否し（§10）、`application-prod.yml` からこの7件の placeholder を削除した（NF02 顧客ポータルの `visible-since` は FE-B03 まで残す）。
- 閉じた時の挙動は現在の OFF と同じ（filter の 404、menu の非表示、scheduler の no-op、子の 400。NF04 は `common.js` が SW の登録を解く。FS 4.4）。

### 8.2 NF01 二者確認

- `LifecycleDualConfirmationReleaseGate`（`@Profile("prod")`、`@PostConstruct validate` :68-80）: 例外で起動を止める処理を削除する。`acceptable` は profile に依らない `LifecycleDualConfirmationEnablementConditions`（評価器）から使う。property の `privacy-sign-off-ref` は dev・test の property の源でだけ使う。
- 評価器: (a) 署名 `NF01_DUAL_PRIVACY` の参照が `acceptable`。(b) 証跡種別の許可値の外の行が 0 件（FS 4.9.5 の (1)(2)）。`m_lifecycle_template_task`・`t_lifecycle_task` の mapper に件数の method を足す。照合順序で揺れを同一視しないよう、MySQL では `CAST(... AS BINARY)` と同じ比較を行う。H2 で同じ SQL が動かない場合は、`HEX` の値の一覧を Java で許可値と照合する（実装の着手時に両方で確かめる）。
- 閉鎖の dialog の警告（§5.5）のため、有効な DUAL template の件数（FS 4.9.5 の (3)）を詳細の API で返す。
- 実行時の gate（`LifecycleDualConfirmationCoordinator:660`、`LifecycleTemplateServiceImpl:316`）は変えない。
- 実装で決めた細部（FE-B01）:
  - バイト列の比較は `CAST(CONCAT(evidence_type, '|') AS BINARY(128)) NOT IN (CAST('NONE|' AS BINARY(128)), …)` で、MySQL と H2 の同じ SQL で動く（H2 に `HEX` は無く、長さの無い `CAST(... AS BINARY)` は H2 で `BINARY(1)` になるため長さを付ける。`BINARY(N)` は短い値を 0x00 で埋めるため、末尾に区切りの `'|'` を付けて末尾の NUL も検出する。`VARCHAR(30)` の utf8mb4 は最大 120 byte）。条件の文は `LifecycleTemplateTaskMapper.EVIDENCE_TYPE_OUTSIDE_ALLOWED` の1箇所で、`LifecycleTaskMapper` も同じ文を使う。許可値は `LifecycleTemplateServiceImpl.EVIDENCE_TYPES`（public にした）と同じで、`LifecycleDualConfirmationEnablementConditionsTest` が全ての許可値で0件を確かめる。照会の失敗は `dualConfirmation.evidenceTypes.unverifiable`（評価できない類）。
  - `LifecycleDualConfirmationReleaseGate` は bean でなくなり（`@Component`・`@Profile("prod")`・`@PostConstruct` を外した）、`acceptable` だけを持つ。起動時の判定は `FeatureEnablementStartupEvaluator` が評価器で行う。参照の検査は、DB の源で DB 管理の時は評価に使う snapshot の確認済みの `NF01_DUAL_PRIVACY` の参照、それ以外（property の源）は `privacy-sign-off-ref` を見る。
  - 閉鎖の警告は拡張点 `FeatureEnablementCloseWarningSource`（開放単位ごとの code と件数）で、`FeatureEnablementQueryService.UnitDetail.closeWarnings` が返し、画面の閉鎖の dialog が `featureEnablement.closeWarning.<code>` の文言で出す。読めない時は警告なしで閉鎖を続ける。
  - 開閉以外の旧い変数（署名の値・設定）は `FeatureEnablementProductionValidator.LEGACY_SETTING_NAMES`（開放単位ごと。正規化した名前の前方一致）に置き、DB 管理の印のある開放単位の分を拒否する。FE-B01 は `FEATURE_NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_REF` と property の綴り（開閉の `FEATURE_NF01_DUAL_CONFIRMATION_ENABLED` は印から自動で拒否する）。`application-prod.yml` の NF01 二者確認の placeholder を削除した。

### 8.3 NF06

- `ImportAdmissionApprovalSource`（新設）: 現在の `ImportAdmissionApprovalProperties`（`@ConfigurationProperties("app.import-center.admission")`）を返す property の実装と、確認済みの署名 `NF06_ADMISSION` から `ImportAdmissionApprovalProperties` の値を作る DB の実装。`ApprovedSourceImportAdmissionPort`（:121）が毎回この源を読む。
- `ImportAdmissionStartupValidator`（:52-77）・`ImportRowReferenceKeyStartupValidator`（:38-55）: 例外で起動を止める処理を削除し、`ImportCenterEnablementConditions`（profile に依らない評価器）が同じ判定を呼ぶ: `ImportAdmissionApproval.evaluate(source.current(), businessDate)` の構造的な不足と期間、`ImportSourceRowReference.state()`（:96）、`import.metadata.manage` の実効の許可（`role-admin`・`privacy-admin` が許可し、それ以外の有効な group には許可・拒否を評価した結果として許可が無い。`AuthorizationServiceImpl` の group の評価と同じ規則を group ごとに当てる。拒否行の有無で判定すると、今後の migration で group が増えた時に拒否行の付け忘れで NF06 が自動閉鎖するため。review P3-19）、単一法人（`LegalEntityContextService` と同じ条件の件数）。
- `ImportJdbcReconnectGuard` は変えない（常時）。
- 期間の外の扱いが変わる: 現在は「起動して upload だけ 403」、本機能の後は「NF06 を自動閉鎖（404）」（D-4。§18 J-09。決定済み（2026-11-22、利用者））。
- 実装で決めた細部（FE-B02）:
  - `ImportAdmissionApprovalSource` は interface で、実装は `Property`（`app.import-center.admission.*`）と `Database`（memory の snapshot の確認済みの `NF06_ADMISSION`。目録の印が無ければ `Property` へ委ねる）。選択は `ImportAdmissionApprovalSourceConfiguration`（`FeatureEnablementStateSourceConfiguration` と同じく `app.feature-enablement.source`）。署名から値への写しは、構造化された値の `decisionId`・`tenantId`・`sourceCodes`（カンマ区切り）・`approvalDigest` と、署名の行の `signed_on`・`expires_on`（admission の署名日・失効日は署名の行の日付を使い、二重に持たない）。署名が無い・構造化された値を読めない時は未設定の値（admission は拒否）。参照（reference）は決定書の ID で、`decisionId` との一致は求めない。
  - 評価器は評価に使う snapshot（書込みの transaction では lock の後の読直し）から `fromSnapshot` で読み、`ApprovedSourceImportAdmissionPort` と起動の log は `current()`（holder の snapshot）を読む。評価器は承認 tenant と設定の tenant の一致も見る（`importCenter.admission.tenant.notApproved`）。
  - **profile**: design §4.4・本節のとおり評価器は profile に依らない（tasks FE-B02 の Demo の「dev では評価器が prod 専用のため申請できない」とは食い違うため、本節に従い、Demo の文を「新規の dev の DB は単一法人の条件（法人 ID を持つ組織が無い）で申請できない」に直した）。`ImportAdmissionStartupValidator`・`ImportRowReferenceKeyStartupValidator` は `@Profile("prod")` の起動の log（INFO・WARN）だけになった。
  - (c) の判定: 有効な group（設定の tenant・`enabled=1`・未削除）ごとに、`import.metadata.manage` の候補（action・`import.*`・`*`）の行を `AuthorizationServiceImpl.groupAllows`（`evaluate` の group 部分と同じ規則の静的な関数。拒否行が先、privacy は `*` を数えない）で評価する。`role-admin` は必須で、`role-admin`・`privacy-admin` は実効の許可に加えて明示の許可行（`import.metadata.manage`・`deny_flag=0`）を要する（service 層 `ImportMetadataAuthorizationService` は `*` を許可根拠に数えないため）。`privacy-admin` の group が無ければ問わない。それ以外の group で実効の許可があれば件数を detail に出して不成立。
  - (d) は `LegalEntityContextService.administratorLegalEntityCount()`（新設。管理者の法人 context と同じ `selectAllLegalEntityIds` の種類の数。security context を読まない）。
  - 署名の登録・確認の時の検査（FE-AC-004-06）: `FeatureSignOffKindValidator.validateAdmission` が構造化された値と署名日・失効日を写して `ImportAdmissionApproval.evaluate` し、構造的な不足（digest の不一致を含む）は 400 `error.featureEnablement.admissionInvalid`（違反の code だけ）。
  - prod の旧い変数: `FEATURE_NF06_ENABLED`（印から自動）と `IMPORT_ADMISSION_*`・`app.import-center.admission.*`（`LEGACY_SETTING_NAMES` の前方一致。list の添字の綴りも拾う）。行参照の鍵 `IMPORT_ROW_REFERENCE_HMAC_KEY` は鍵として配備の設定に残る（D-5）。`application-prod.yml` の NF06 の placeholder を削除した（admission の placeholder は dev・test の property の源のため `application.yml` に残る）。

### 8.4 NF02 の公開範囲

- `PortalServiceRequestVisibilityPolicy`: `final` の `visibleSince`（:45-52）をやめ、`FeatureEnablementStateSource.setting("nf02-customer-portal.visible-since")` を毎回解釈する（解釈の結果は値の文字列を key に 1 件だけ cache する）。構築時の解釈の失敗で起動を止めるのは property の源の時だけ（dev・test の現状を保つ）。DB の源では不正な値は申請の時に拒否され、DB の値が壊れていれば portal 起票だけを出す（fail-closed）。
- `PORTAL_ORIGINATED_ONLY` の既定（:41-43）はそのまま。呼出し元（`ServiceRequestServiceImpl`・`ServiceRequestAttachmentService`・`FileScopeValidationService`・`ServiceRequestDto`）は変えない。
- 実装で決めた細部（FE-B03）: Spring の構築子は `FeatureEnablementStateSource` を `ObjectProvider` で受け、源が DB（`database()`）なら判定のたびに `setting(...)` を読み（構築時に解釈しない）、それ以外（property の源・源が無い slice）は従来どおり構築時に解釈する。DB の源の `setting` は目録の印（NF02 顧客ポータル。段階 A で付与済み）に従い snapshot を読むため、hot path は memory だけ。源を読めない・値を解釈できない時は portal 起票だけ（WARN は値ごとに1回）。`FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE` と property の綴りは prod で拒否し（`LEGACY_SETTING_NAMES`）、`application-prod.yml` の placeholder を削除した（`app.features.*` の prod の placeholder は段階 B の後に無くなった）。

### 8.5 NF07（保持・保全・DSAR・副入口）

1. **承認の値の源** `PrivacyApprovalSource`（新設）: `tenantId()`・`capabilities()`・`approvalDigest()`・`reference(Decision)`・`dsarDueDays()`・`dsarPackageMaxAge()`。property の実装は現在の `PrivacyApprovalProperties`（`@ConfigurationProperties("privacy.approval")` :21-93）と `PrivacyFeatureProperties.Dsar`（:59-70）を返し、DB の実装は確認済みの組「NF07 の決定の束縛」（D-01・D-09・D-06）と確認済みの署名 `NF07_D02`・`NF07_D03`・`NF07_D05` から返す。組の決定（`reference(D01/D06/D09)`・`tenantId`・`capabilities`・`approvalDigest`）は、現在 `CONFIRMED` の組の全ての member から、member の行の状態に関係なく返す（§2.6.1。review P2-N1）。member の行の状態は `PrivacyDecisionBinding.evaluate` の構造の検査に渡さず、期間・取消しは開放単位ごとの署名の条件（`SignOffConditionEvaluator`）が見る。`PrivacyDecisionBinding.evaluate`（:145）・`evaluateStandalone`（:202）に源を受ける overload を足し、呼出し元（`PrivacyProductionConfigurationValidator`、`PrivacyDecisionWindowMonitor`、`PrivacyHoldDecisions.evaluate` :47-63・`releaseReadiness` :69-83、`PrivacyDsarDecisions` :49-88、`PrivacyPolicyRegistrationService.readiness` :134-150、**`ThirdPartyRedactor`（:48-53。D-06 の決定 ID を読む）**）を源へ切り替える。どれも呼出しごとに読むため、署名の差替えは再起動なしに効く。静的な試験 `ApprovalValueReadGuardTest` で、`PrivacyApprovalProperties`・`ImportAdmissionApprovalProperties`・NF05 の approval の `decision-id`・`approval-digest` の getter を呼べるのを property の源だけに固定する（DB の源で空の値を読む漏れを検出する。review P3-15）。
   - **期間による閉鎖は開放単位が要る決定だけ**（review P1-2）: `PrivacyDecisionBinding.evaluate` に「期間を判定する capability」の引数を足す。構造（形式・tenant・承認 capability・digest）は D-09 が承認した capability の決定の全てで判定し（digest の内容は変えない）、署名日前・失効は評価している開放単位の capability が要る決定だけで判定する（保持・保全は D-01・D-09、DSAR は D-01・D-06・D-09）。D-06 が失効しても保持・保全は閉じない（D-4）。`openUntil`（§3.2）も同じ範囲で計算する。
2. **5 箇所の直接の読取り**:
   - `PrivacyProductionConfigurationValidator.enabledCapabilities`（:303-315）と `PrivacyDecisionWindowMonitor.enabledCapabilities`（:211-223）: `FeatureEnablementStateSource.isOpen(NF07_*)` から作る（`FeatureSwitches.isEnabled` を呼ぶと Monitor 自身の実行時条件で再帰するため、源を直接読む）。評価の時は `openAfterChange` を使う。
   - `PrivacyTrustStartupValidator.validate`（:60-61）: flag を見ずに、KMS の source を設定した prod では常に `inspect` する（下の 4）。
   - `PrivacyJournalStartupVerifier.holdStartRequired`（:142-146）: 下の 3。
   - `PrivacyProductionConfiguration.retentionSourceEnabled`（:77-83）: `FeatureSwitches.isEnabled(NF07_RETENTION)` に置き換える（現在は生の flag と Monitor を自前で掛けており、`FeatureSwitches` と同じ判定の写しになっている）。`PrivacyPolicyEnforcementConfiguration:47`（NF08 の LINK）も同じ supplier を使うため意味は変わらない。
   - 静的な試験 `PrivacyFeatureFlagReadGuardTest` で、`getRetention().isEnabled()`・`getDsar().isEnabled()` を呼べるのを property の源だけに固定する。
3. **Journal**（`PrivacyJournalStartupVerifier`。review P2-4）: 結果を 2 つに分ける。
   - **hold の効力の結果**（`protectionResult`）: 現在と同じく起動時に検査し、reader（`nf07_journal_reader`）・appender のどちらの principal でも AVAILABLE とする（開閉に関係なく、`appenderRequired=false` の判定）。`reverify()` は成功した時だけこの値を更新し、失敗では下げない。hold gate（`PrivacyHoldJournal.availability` :116-127 の `baseAvailability` :180-200）はこれを読む。したがって開閉・principal の種類・再検査の一時の失敗で hold gate が UNAVAILABLE になり、文書の処分・retention の purge・孤児 file の掃除（既存の hold を守るために止まる経路）が止まることは無い。現行の reader 方式（`journal-witness-grant-design.md:47,108`、`PrivacyJournalStartupVerifier` の javadoc :41-42）はそのまま使える。
   - **開放の条件の結果**（`enablementResult`）: appender の権限を要求して検査する（`appenderRequired=true`）。`reverify()`（申請・承認の世代の lock の前の `refreshRemote`（§4.2）・定期の評価・reconciler の実行ごとに呼ぶ。間隔の下限 `app.feature-enablement.journal-reverify-interval`、既定 10 分、1 分〜60 分）は成否のどちらでもこの値を更新し、届かない（`journal.unreachable`）は評価できない類（§4.2）。保持・保全の開放の条件（要件 §6.11 の (e)）と、hold の開始（append）の判定がこれを読む。
   - `enforce`（:171-192）は prod でも例外にせず WARN にする。Journal の接続の設定は起動時に要る（`PrivacyJournalConnections:93`）ため、設定の追加は配備の設定の変更と再起動を要する（基盤の準備。要件 §6.11 の鍵）。
4. **trust**（`PrivacyTrustStartupValidator`）: KMS の source（`PrivacyTrustConfiguration:29-36`）を設定した prod では、flag に関係なく起動時に `kms.inspect()` を行い、構造的な不足を例外にせず `TrustStructureState` に置いて ERROR を出す。副入口 REL・REG の判定（`PrivacyHoldReleaseService.isOpen` :131-137、`PrivacyPolicyRegistrationService.readiness` :134-150）に「構造に不足が無い」を加える。実行中の文書の状態（鮮度・anchor）は今のまま判定のたびに読む（`KmsPrivacyTrustSource.refresh` :328-362）。
5. **Monitor の cache**（`PrivacyDecisionWindowMonitor.currentStatus` :156-165）: key を（JST の業務日、snapshot の世代、開いている capability の集合）にし、状態を capability ごとに持つ（`NF07_RETENTION` の実行時条件は retention の決定だけ、`PrivacyDsarDecisionWindowCondition` は DSAR の決定で判定する。現在は両方が同じ状態を共有し、D-06 の失効で保持・保全も閉じる）。DB の源では有効期限の閉鎖は snapshot の `openUntil` でも効くが、metric（`ses_privacy_decision_binding_state`）と WARN の日ごとの出力のために Monitor は残す。
6. **評価器**: `PrivacyProductionConfigurationValidator` に `FeatureConditionEvaluator`（NF07 の 2 件）を実装させ、`validate`（:130-173）が呼ぶ method（`PrivacyDecisionBinding.evaluate` の呼出し :139-141、`validateDsarPrerequisites` :201-219、`validatePrivacyAdminGroup` :221-233、`validatePrivacyAdministrators` :240-282、`validateNoPrivacyAllowOutsidePrivacyAdmin` :284-301）をそのまま呼ぶ。`validate` は prod でも機能の条件では例外にしない（log だけ）。時間要因の code（`*.signedOn.future`・`*.expiresOn.expired`）は `temporal=true`。F2 provider の件数（`providerRegistry.providers()`）は memory。Journal と trust は 3・4 の結果を読む。
7. **DSAR の値**: `PRIVACY_DSAR_DUE_DAYS`・`PRIVACY_DSAR_PACKAGE_MAX_AGE` は D-06 の署名の構造化された値に移す（§18 J-15）。approval digest には入れない（正規形 v1 を変えない。FS 4.7.2 の人手の確認 (a) は、画面に出す D-06 の値と決定書の照合に置き換わる）。
8. **行為者**: NF07 の 2 件は申請・承認・閉鎖の行為者と、NF07 の署名の種別・組の確認者を privacy-admin に限る（§11.3）。
9. **副入口の自動の再開**: 副入口は DB の状態を持たず、条件が戻れば自動で開く（D-4 の例外。J-03 の結果。理由は要件 FE-RQ-005）。`NF07_D05` と `NF07_D05_OWNER_ACCEPTANCE` は副入口（hold 開始・REL）の条件で、開放単位の条件ではない（D-05 の失効で保持・保全を閉じない。review P3-16）。**REL・REG についてこの例外は利用者が確認した（2026-11-23。要件 §2 の D-10）**: REL・REG は技術の条件（trust 文書の鮮度・anchor・verifier）が戻れば自動で開く。hold 開始の自動の再開（技術の条件は Journal の appender）は J-03 の推奨（独立 review で同意）で、**利用者の確認の範囲外**。どの副入口も、署名に依る回復（D-05・D-03・D-02 の更新、証拠の類の再登録）は新しい署名の privacy-admin の二人の確認を要し、一人で副入口を開き直す経路は無い。副入口の目録の署名（決定の類と証拠の類。`NF07_D05_OWNER_ACCEPTANCE`・`NF07_TRUST_KMS`・`NF07_TRUST_STAGING` を含む）は、監視（`SubgateMonitor`）だけでなく各入口の gate も要求ごとに読む（`PrivacySubgateSignOffs`。取消し・失効で副入口だけを閉じる。段階 C の review rb-fe-c P2-1）。
10. **PR #130 の guard**: §14。

**実装で決めた細部（段階 C。FE-C01〜C06 の実装）**:

- 承認の値の源の選択は `PrivacyApprovalSourceConfiguration`（開放状態の源が `database` で NF07 の2件が DB 管理なら DB の源）。DB の源は1回の評価の値を `resolve()` で snapshot から固定し、評価器は評価に渡された snapshot（書込みの transaction の中では読み直したもの）から `forSnapshot` で読む（§3.2 の P2-3）。組の整合の検査は `PrivacyDecisionBindingConsistencyCheck`（D-09 の `capabilities` が `dsar` を含む時だけ D-06 を member に要し、含まない時は D-06 を拒む）。署名の登録の時の検査（`FeatureSignOffKindValidator`）は NF07 の決定 ID を `PrivacyDecisionBinding` の規則で受け、D-09 の3項目・D-06 の2項目を必須にした。
- 評価器は開放単位ごとに自分の capability だけを開く束縛として評価し、期間も自分の capability で判定する（`openAfterChange` は使わない。DSAR の不足で保持・保全を閉じない D-4 のため）。Journal の検査は `remoteChecksAllowed` のとき `reverify()`、そうでなければ最後の開放の条件の結果を読む。
- Monitor の状態は capability ごと（`currentStatus(Capability)`）と、metric・WARN 用の全体（開いている capability の全て）を持つ。
- 副入口は `SubgateMonitor`（`FeatureEnablementEvaluationService` が定期の評価のたびに呼ぶ）が、親の開放単位・署名・固有の条件（`SubgateMonitor.Condition`。NF07 は `PrivacySubgateConfiguration` の3件）で判定する。trust の構造の不足は `PrivacyTrustStructureState`（prod の起動時に flag と関係なく検査した結果）で REL・REG の gate と副入口の条件に加える。
- hold 解除（REL）の入口は Journal の開放の条件の結果を見ない（要件 §6.11 の REL の条件に無いため。append の権限が無ければ DB が拒む）。hold の開始だけが `PrivacyHoldJournal.appendAvailability()` を読む。adapter は Journal の権限の拒否（MySQL 1142・1143・1044・1227・1370）を ACK 不明ではなく確定の拒否（`JournalAccessDeniedException`）にし、hold の開始・解除は最初の試行の拒否なら pending を戻して 503 `error.privacy.journalUnavailable` を返す（gate を `UNAVAILABLE` にしない。監査の理由 `JOURNAL_ACCESS_DENIED`。段階 C の review rb-fe-c P3-1・判断点 4）。ACK 不明の後の再送が権限で拒否された時は、`pj_head` の更新が command の再生の照合より先のため先の試行が効いたか分からない。確定の拒否にせず ACK 不明として pending を残し（gate は fail-closed のまま、reconciler が Journal から判定する）、監査は ACK 不明に理由 `ACCESS_DENIED_AFTER_ACK_UNKNOWN` を添えて拒否と区別する（review rb-fe-c P3-N1）。
- guard の NF07 の開放状態の読取りは `PrivacyEnablementStateReader`（状態の行が `openLike` か、NF07 の2件のどちらかに未決の申請があれば判定。未決の申請は tenant の全件を読んで対象で絞る）。hold の効力の結果（`protectionResult`）は reconciler の実行ごとにも再検査し、保持・保全が閉じていても Journal が直れば回復する（review rb-fe-c P3-2）。
- DSAR が閉じている間の通知は `PrivacyDsarClosedNotifier`（`FeatureEnablementDayStartHook`。件数と最も近い期限日だけ）。
- NF07 の開閉以外の旧い変数（`PRIVACY_APPROVAL_*`（`PRIVACY_APPROVAL_EXPIRY_WARNING_DAYS` を含む）・`PRIVACY_DSAR_DUE_DAYS`・`PRIVACY_DSAR_PACKAGE_MAX_AGE`）の拒否は `FeatureEnablementProductionValidator.ADDITIONAL_LEGACY_RULES`（段階ごとに行を足す。各行は開放単位の集合と接頭辞で、集合のどれかが DB 管理なら拒否する。review rb-fe-c P3-8）。DB の源の失効の警告の日数は §6.5 の `feature-enablement.expiry-warning-days`（`m_system_config`）を読み、`PRIVACY_APPROVAL_EXPIRY_WARNING_DAYS` は旧い変数として拒否する（review rb-fe-c P3-4）。NF07 の行為者の privacy-admin は、`privacy.` の拒否行を持つ group の所属者を除く（開放の条件の数え方と揃える。review rb-fe-c P3-3）。`application-prod.yml` には NF07 の placeholder が無かった（`application.yml` のものは dev・test の property の源が使う）。

### 8.6 NF05

§9。

## 9. NF05: 開けるのは次の起動、閉じるのは即時

### 9.1 起動の前の読取り（`IntegrationHubEnablementBootResolver`）

NF05 の bean の有無は `@Conditional`（要件 §1）が bean の定義の登録の時に決め、その時には DataSource の bean がまだ無い。そのため、Spring の `EnvironmentPostProcessor`（`META-INF/spring.factories` に登録）で context の refresh の前に DB を読み、NF05 の property を決める。

0. **実装の条件**（review P3-14。J-20 の条件）:
   - 順序: `Ordered` で `ConfigDataEnvironmentPostProcessor.ORDER + 1` より後にする（`application*.yml` と環境変数を読み込んだ後に動く）。
   - `validateBoundaries`（:36-38）は enabled の値が明示されていることを要求するため、boot resolver は両方向の値を常に（閉でも `false` を）書く。
   - 読取りは app の起動時の schema の検査（`ProductionSchemaVersionGuard`）より前に行う。照会は V203 の表と既存の列だけを使い、表・列が無い（schema が artifact と違う）時は `READ_FAILED`（閉）にする（app はその後 schema の検査で止まる）。
   - test・H2: property の源の profile では何もしない。`source=database` の H2 の試験では、context の前の時点で schema が無いため `READ_FAILED`（NF05 は閉）になる。NF05 の DB の源の試験は Testcontainers の MySQL で schema を先に作ってから context を起動する。
   - 判定の写しを持たない: 開閉の判定は `IntegrationHubEnablementDecision.decide(BootInputs)`（普通の値（状態・署名の値と期間・barrier の行・監査の有無・bind した properties・総スイッチ・今日）を入力にする純粋な関数）に置き、boot resolver（JDBC で入力を作る）と評価器（mapper と snapshot で入力を作る）が共用する。
1. 対象: 有効な profile に `prod` を含むか、`app.feature-enablement.source=database`。それ以外（dev・test の property の源）は何もしない。
2. 総スイッチ（`app.feature-enablement.allowed`）を読む。閉なら両方向を閉にする。
3. `spring.datasource.url`・`username`・`password` で JDBC の接続を 1 本だけ開く（`DriverManager`、接続と socket の timeout 5 秒。pool を作らない）。読むもの: tenant（`app.security.oidc.tenant-id`、未設定なら `default`）の NF05 の 2 行の開放状態（`opened_at` を含む）、NF05 の署名の種別の確認済みの行、barrier の行（`integration.hub.outbound.runtime.v1`）、`t_audit_log` の `NF05_OUTBOUND_OFF` の有無（`OPEN_PENDING_RESTART` の outbound の時だけ。条件に `reference_type='NF05'` を必ず付け、`idx_auditlog_reference (reference_type, reference_id)` を使う。`application_code` で始まる索引は無く、付けないと起動の前に全表を走査して 5 秒の timeout で `READ_FAILED` になり、NF05 が開けなくなる。評価器の同じ照会も同じ条件にする。review P3-N8）、NF05 の保存期間の対象の表（`t_api_delivery`・`t_api_delivery_replay_audit`・`t_api_nonce_replay`・read snapshot の表）の行の有無。読取りだけで、書かない。
4. `Binder` で `IntegrationHubExternalApiProperties` を bind し（`@PostConstruct` は動かない）、§9.5 の純粋な検査の関数に、DB から読んだ決定 ID・digest を渡して違反を求める。
5. 方向ごとに、開放状態が `OPEN`・`OPEN_PENDING_RESTART`、要る署名が確認済みで今日が期間内、違反が無い、の全てが成り立つ時だけ開。outbound の barrier は、`OPEN` では行の tenant の指紋が現在の bound-tenant-id と一致することだけを見る（phase は問わない。既に開いている outbound の再起動で OFF を要求しない）。`OPEN_PENDING_RESTART` では `Purpose.BOOT_PENDING` として、加えて phase が `OFF` で `NF05_OUTBOUND_OFF` の監査があることを要する（開放の遷移の前提。review P1-1）。
5a. **purge 用の property**（review P2-5）: NF05 のどちらかの行の `opened_at` が非 NULL（一度でも開いた）か、保存期間の対象の表に行があれば、`integration.hub.retention-purge.enabled=true` を同じ property source に書く。`IntegrationHubRetentionPurgeCondition`（:15-22）・`ExternalApiReadSnapshotPurgeScheduler` の条件（現在は `IntegrationHubInboundCondition`）・`IntegrationHubDeliveryMetrics` の条件をこの property を読むものに改める（NF05 が閉じた後の起動でも purge が作られ、保存期間を過ぎた個人データを消し続ける。purge の経路は従来どおり `PrivacyHoldPathGuard` の D7 を通る）。property の源（dev・test）では、現在の「inbound か outbound が true」と同じ値を返す。**`READ_FAILED`（起動の前に DB を読めない）の時は `true`** にする（1 回の読取りの失敗で process の生存の間の purge を止めないため。purge の service は普通の bean で、行が無ければ何もしない。review P3-N3）。
6. 最も優先する `MapPropertySource`（名前 `featureEnablementNf05Boot`）に `integration.hub.public-api.enabled`・`integration.hub.external-transport.enabled` を書く。決定の結果も同じ property source に `app.feature-enablement.nf05-boot.inbound.result`（`OPENED` / `CLOSED:<code>` / `READ_FAILED`）等として置く（static な holder を使わない）。
7. 接続の失敗・例外は `READ_FAILED` で両方向を閉にする（fail-closed）。log は `DeferredLogFactory` で出す（code だけ）。

既知の制約: DB の account を context の前に使う（同じ account・同じ secret。新しい権限は要らない）。DBA・Security の受理を RB §1.2 に追記する。

### 9.2 起動の後の反映（`IntegrationHubBootDecisionRecorder`）

- `SmartLifecycle`（web server より前。§7.1 の評価器より前の phase）で、property source の結果を DB に反映する:
  - `OPEN_PENDING_RESTART` かつ `OPENED` → `OPEN`（`restart_applied_at`、履歴 `RESTART_APPLIED`、監査）。
  - `OPEN`・`OPEN_PENDING_RESTART` かつ `CLOSED:<code>` → `AUTO_CLOSED(<code>)`。
  - `READ_FAILED` → 状態は変えず、履歴 `BOOT_READ_FAILED` を残し、画面に「この起動では閉」と出す（runtime の判定は起動時の値で閉）。
- 法人の未解決（`LegalEntityReadinessStartupValidator` の NF05 の部分）: `ApplicationReadyEvent`（:21-27）は port を開いた後のため、NF05 の部分を `FeatureEnablementStartupEvaluator`（port を開く前。§7.1）へ移す。NF05 のどちらかが起動時の値で開なら `readinessService` の非例外の判定を呼び、未解決の行があれば例外にせず両方向を `AUTO_CLOSED(LEGAL_ENTITY_UNRESOLVED)` にする（書けなければ自動閉鎖の書込み待ちで閉じる。review P3-5）。management copilot の部分（`aiConfig.isManagementCopilotEnabled()`）は `ApplicationReadyEvent` のまま従来どおり例外。

### 9.3 閉じる（即時）

- `IntegrationHubRuntimeSwitch`（新設）: `inboundOpen() = 起動時の値 && featureSwitches.isEnabled(NF05_INBOUND)`、outbound も同じ。実行時の全ての読取りをこれに置き換える: `ExternalApiDisabledFilter:29-30`、`ExternalApiAuthenticationFilter:61-62`、`ExternalApiAuditBoundary.publicApiEnabled` :158-162、`BackupWriteGateFilter.publicApiEnabled` :87-91、`IntegrationHubResourceChangedProducer.changed` :37-39、`IntegrationHubWebhookDeliveryWorker` の :79-85・:111-114・:193-194。静的な試験 `IntegrationHubFlagReadGuardTest` で、`getPublicApi().getEnabled()`・`getExternalTransport().getEnabled()` を呼べるのを `@Conditional` の条件・起動時の検査・runtime switch・boot resolver だけに固定する。
- `IntegrationHubOutboundStartupBarrier`: 起動時の経路（`start` → `initializeSafely` :55・:72-101。phase `Integer.MIN_VALUE`）は起動時の値（`bootEnabled()`）で分岐し、実行時の `isReady` :103・`continueRecovery` :62-70・`quarantineBatch` :107-135 は runtime switch で判定する（現在は共に `enabled()` :137）。`continueRecovery` は **先に runtime switch を見て、閉じていれば初期化の再試行（`initializeSafely`）もしない**（現在は :64-67 で初期化を先に試す。起動時の初期化が失敗した後に確定の閉鎖で行が OFF に戻った場合、起動時の値で `QUARANTINING` に書き換えて `NF05_OUTBOUND_REOPEN` を記録し、再承認に要る OFF の記録を壊すため。review P3-N2）。
- outbound を閉じる transaction（手動・自動・総スイッチ）は、barrier の行を `selectByIdForUpdate` で lock し（§5.1 の (6)）、`v1|OFF|0|<sha256(bound-tenant-id)>` に書き直し、`NF05_OUTBOUND_OFF` を `recordDomainEventRequired` で監査する（新しい method `recordRuntimeOff`。現在の kill switch は行を `ON` のまま残すため、次に開く起動が隔離を飛ばす）。commit の後、memory の `ready` を false にする。
- **閉鎖の 2 種類**（review P2-N3）。`IntegrationHubRuntimeSwitch.outboundClosure()` が `NONE` / `TRANSIENT` / `DEFINITIVE` を返す:
  - **一時の閉鎖 `TRANSIENT`**: 開放状態は `OPEN` のままで、`unverifiable_since`（評価できない状態）・この instance の memory の評価できない状態・snapshot の古さだけで実効値が閉じている。
    - producer（`IntegrationHubResourceChangedProducer.changed` :37-39）は outbox への書込みを続ける。producer の判定は「起動時の値 ∧ 最後に読めた snapshot の開放状態が `OPEN` ∧ 総スイッチ」で、評価できない状態と snapshot の古さを見ない（閉じている間の業務の変更を失わない）。
    - worker（`dispatchDue` :75-100）は claim を止める。`dispatchOne`（:103-208）が処理の途中で閉鎖を見た時は、DLQ にせず PENDING へ戻す（`ApiDeliveryMapper` に、claim を外して `PENDING` に戻し試行の回数を増やさない method を足す。現在の `holdPending`（`ApiDeliveryMapper.java:81-86`）は `DLQ`・`KILL_SWITCH` の終端にする）。
    - 解ければ同じ process で送信を再開する。閉じていた間に積まれた配信も送る（at-least-once を保つ）。barrier の行は `ON` のままで、OFF の期間の配信を作らないため隔離は要らない。
    - 一時の閉鎖が続いて自動閉鎖（確定の閉鎖）になった場合は、その時点で PENDING の配信は下の確定の閉鎖の扱いになる（開き直しの起動の隔離の対象になる）。
  - **確定の閉鎖 `DEFINITIVE`**: `AUTO_CLOSED`・手動の閉鎖・総スイッチ（DB に書けない間の自動閉鎖の書込み待ち `AUTO_CLOSE_PENDING` を含む。条件の不成立が確かめられているため）。現在の kill switch と同じ: producer は積まず、worker は送信しない（未送信は PENDING のまま、処理中は DLQ `KILL_SWITCH`）。barrier の行を OFF に書き直し（上）、memory の `ready` を false にする。同じ process では開き直さず、開き直しは `OPEN_PENDING_RESTART` → 再起動 → 隔離を経る。
  - inbound は、どちらの閉鎖でも実効値が閉じている間は 404 `DISABLED_DENY`（client が再試行する。SES 側に失うものは無い）。
  - bean は次の起動まで残る（runtime switch で眠る）。
- **注記（micro review 88630af1 の P3-M2）**: 一時の閉鎖で処理中の配信を PENDING へ戻す更新は、claim の持ち主（lease の token）と状態を条件にした CAS で行い、`attempt_count` を増やさない（一時の閉鎖が続いても回数の上限で DLQ に落とさない）。`TRANSIENT` と `DEFINITIVE` の境の細部（`AUTO_CLOSE_PENDING` を確定の閉鎖として扱う時点、古さが続いて自動閉鎖に変わる時の処理中の配信の扱い）は段階 D（FE-D03）の着手時に確定する。
- 同じ process での再開は一時の閉鎖からだけ（上）。第 2 版の「一時の閉鎖は ON→ON と同じ」の説明は、producer が配信を捨て、処理中の配信が DLQ に入るため成り立たなかった（review P2-N3）。上の 2 種類の区別で置き換えた。
- `IntegrationHubApprovalDigest.canonicalInbound`・`canonicalOutbound`（:79・:104）は決定 ID を properties から読むため、`IntegrationHubApprovals`（§9.5）から受け取るように改める（DB の源では properties の決定 ID が空になり、digest が必ず合わなくなるため。review P3-15）。

### 9.4 outbound の barrier と RB の関係

- 規則は変えない: 初回の起動から全ての起動で同じ `INTEGRATION_HUB_BOUND_TENANT_ID` を設定する（RB §1.7 の「ON の起動までの順序と準備」の 1）。承認の前の process は outbound が閉のため、起動のたびに barrier が OFF の記録を書く。
- RB §1.7 で DBA・SRE が SQL で確かめていた「状態の行 `v1|OFF|0|<指紋>` と監査 `NF05_OUTBOUND_OFF`」は、2 つの条件に分けて自動検査に置き換わる（review P1-1）:
  - **継続の条件**: 行の tenant の指紋 = `sha256(現在の bound-tenant-id)`（phase は問わない）。開いた後の `QUARANTINING`・`ON` でも成り立つ。値を変えて再起動すると、boot resolver の検査（同じ比較）で閉じたまま起動し、`AUTO_CLOSED(TENANT_STATE_MISMATCH)` になる。
  - **開放の遷移の前提**（`Scope.OPEN_TRANSITION`）: phase が `OFF` で `NF05_OUTBOUND_OFF` の監査がある。申請・承認と、`OPEN_PENDING_RESTART` を反映する起動（`BOOT_PENDING`）でだけ判定する。既に `OPEN` の再起動は OFF を要求しない（開いている outbound の再起動は ON→ON で、queue を保つ）。
- 承認から再起動までの間に OFF の起動が何度あっても、同じ値なら行は変わらない。
- 開く起動の後は、現在どおり QUARANTINING → READY（`NF05_OUTBOUND_REOPEN`・`NF05_OUTBOUND_READY`）。
- truststore 等、DG-05-OUT の digest の内容を変える時の流れ（review P3-18）: (1) 新しい決定（新しい決定 ID・digest）を署名し登録・確認すると、現在の配備の設定と digest が合わず outbound は自動閉鎖する（barrier は OFF に書き直される）。(2) 配備の設定（truststore 等）を新しい決定の内容に改めて再起動する（OFF の起動。barrier は OFF のまま）。(3) outbound の開放を申請・承認する（`OPEN_PENDING_RESTART`）。(4) 再起動で開く（隔離が走る）。再起動は 2 回要る。RB に書く。

### 9.5 `IntegrationHubProductionEnablementPolicy` の改修

- 承認の値（決定 ID・digest）を `properties.publicApi.approval`・`properties.externalTransport.approval` から読むのをやめ、引数（`IntegrationHubApprovals`）で受ける。property の源（dev・test）では property から作り、DB の源では署名から作る。
- 検査する方向を引数で受ける（現在は flag を :49-50 で読む）。承認の時の評価は、閉じている process でも開いた場合の違反を求める。
- truststore の検査（:138-146）は bytes を読むだけの純粋な関数にし、`recordApprovedTruststore`（:140）の副作用は起動時の値が開の時の `validateBoundaries` だけで行う。
- `validateBoundaries`（:35-125）の flag に依る検査（public-id-key :42-46、DEDICATED_DATABASE と bound-tenant-id :47-50、HTTPS の truststore :92-96）を静的な関数に切り出し、boot resolver と評価器から呼ぶ。DB の源の prod では、起動時の値が開でも違反があれば例外にせず runtime を閉じて `AUTO_CLOSED` にする（boot resolver と同じ関数を使うため、通常は起きない安全網）。
- flag に依らない検査（`provider.mode` が STUB・LOOPBACK でない :51-54、loopback の port が空 :55-57）は残す。outbound が閉の時の mode の規則（:58-60）は MOCK か HTTPS を許す（承認の前に HTTPS と承認の内容を配備の設定に入れるため）。

## 10. 緊急の総スイッチと旧い変数

- `FEATURE_ENABLEMENT_ALLOWED`（`app.feature-enablement.allowed`、既定 `true`）。`application.yml`・`application-prod.yml` に `${FEATURE_ENABLEMENT_ALLOWED:true}`。値は原文の文字列で検査し、`true` / `false` 以外（Spring の bind が受け付ける `yes`・`on`・`1` 等を含む）は起動しない（設定の誤り。機能の条件ではない。review P3-13）。
- 総スイッチ以外の `app.feature-enablement.*`（`source`・`refresh-interval`・`max-staleness`・`evaluation-interval`・`journal-reverify-interval`）は `@Validated` で範囲を検査する（§3.3・§7.2・§8.5 の 3）。prod の配備の設定でこれらを上書きすることは拒否する（環境変数 `APP_FEATURE_ENABLEMENT_*`・`FEATURE_ENABLEMENT_*`（`ALLOWED` を除く）・`SPRING_APPLICATION_JSON` の `app.feature-enablement`・system property。D-5: 配備の設定は鍵と総スイッチだけ。`max-staleness` を大きくして fail-closed を外すことも防ぐ）。
- 閉の時（要件 FE-RQ-007）: snapshot の `masterAllowed=false`（最初の要求から閉）、boot resolver は NF05 を閉、`FeatureEnablementStartupEvaluator` が全てを `AUTO_CLOSED(MASTER_SWITCH_CLOSED)`・未決を `CANCELLED`、申請・承認は 409、署名の登録・確認・閲覧は可。ERROR log と gauge `ses_feature_enablement_master_allowed=0`。開に戻しても自動では開かない（§18 J-11）。
- 閉じる方向の操作のため、ticket を要求しない（`BACKUP_QUIESCE_DISABLED_TICKET` は保護を外す方向のため ticket を要求している。向きが逆）。配備の記録に理由を残す運用を RB に書く。
- 「即時に閉じる」は、配備の設定の変更が再起動を伴うため、再起動の後の最初の要求から閉じることを指す。実行中に 1 つの機能を即時に閉じるのは画面の閉鎖（管理者 1 名）で行う。

**旧い変数の拒否**（`FeatureEnablementProductionValidator`。`@Component`、prod の判定は `Environment.acceptsProfiles(Profiles.of("prod"))`。CLAUDE.md の規則）:

- `System.getenv()` の名前と `System.getProperties()` の key を、`validate-task-definition.ps1` の `NameKey`（`_`・`.`・`-` を除き大文字）と同じ正規化で照合する: `FEATURENF*ENABLED`、`APPFEATURES*`、`PRIVACYRETENTIONENABLED`、`PRIVACYDSARENABLED`、`PRIVACYDSARDUEDAYS`、`PRIVACYDSARPACKAGEMAXAGE`、`PRIVACYAPPROVAL*`、`IMPORTADMISSION*`・`APPIMPORTCENTERADMISSION*`、`INTEGRATIONHUB(PUBLICAPI|EXTERNALTRANSPORT)ENABLED`、`INTEGRATIONHUB(INBOUND|OUTBOUND)(DECISIONID|APPROVALDIGEST)`、`INTEGRATIONHUBPUBLICAPIAPPROVAL(DECISIONID|APPROVALDIGEST)` 等の正規名、`FEATURENF01DUALCONFIRMATIONPRIVACYSIGNOFFREF`、`FEATURENF02CUSTOMERPORTALVISIBLESINCE`。`SPRING_APPLICATION_JSON` に `app.features`・`privacy.retention`・`privacy.dsar.enabled`・`privacy.approval`・`app.import-center.admission`・`integration.hub.*.enabled`・`integration.hub.*.approval` があれば同じく拒否する。
- 1 つでもあれば `IllegalStateException`（変数名の一覧だけ。値を出さない）で起動を止める（§18 J-12）。
- 同じ validator が、prod の `app.feature-enablement.source` が `database` でなければ起動を止める。
- 段階 A〜D の間は、移行済みの開放単位の変数だけを拒否する（§3.1 の印）。段階 E で全てを拒否する。
- RB §1.0 に行を足し、`infra/ecs/task-definition.prod.json` の雛形に `FEATURE_ENABLEMENT_ALLOWED=true` を置く（CLAUDE.md: prod の fail-fast を足す change で同時に行う）。

**実装で決めた細部（FE-A08）**:

- 検査は `SmartInitializingSingleton`（port を開く前、`FeatureEnablementStartupEvaluator` の起動時の評価より前）で1回だけ行う。総スイッチの原文の検査は全 profile、源・運用値・旧い変数は prod だけ（`Environment.acceptsProfiles`）。
- 名前の正規化は ps1 の `NameKey` を広げた形（大文字にして英数字以外を全て除く。`app.features[nf01].enabled` のような綴りも同じ key になる）。
- 運用値の上書き: 環境変数と system property は `APPFEATUREENABLEMENT` で始まる名前（総スイッチの正規名 `APP_FEATURE_ENABLEMENT_ALLOWED` を含む）と、`FEATUREENABLEMENTALLOWED` 以外の `FEATUREENABLEMENT` で始まる名前を拒否する。`SPRING_APPLICATION_JSON`（環境変数）と `spring.application.json`（system property）の key は総スイッチを含めて全て拒否する（総スイッチは task definition の environment に置く）。
- 照合（`validate-task-definition.ps1`）は、`FEATURE_ENABLEMENT_ALLOWED` が environment に1つあり原文で `true`/`false`（雛形・実値の両 mode、両 phase）、運用値の名前が無く、`SPRING_APPLICATION_JSON` に `app.feature-enablement`・`FEATURE_ENABLEMENT_*` が無いことを見る。旧い変数の FAIL は FE-E01（段階 E）で全てに広げる。それまでは段階 A の7件の旧い変数を照合は通し、起動時検査だけが止める（RB §1.0 の「機能の開放」に書いた）。

## 11. 安全

### 11.1 二人の承認

- service の拒否（403）と DB の CHECK・複合 FK（§2.3〜§2.6.1）の二重。開放状態・設定の行は承認した申請の行と申請者・承認者が一致することを複合 FK で強制する（review P3-12）。DB は同じ利用者 ID を拒否するだけで、次の残る穴は防げない（review P3-11）:
  - 同じ人が 2 つの口座を持つ（口座の管理と OIDC の IdP に任せる）。
  - 管理者の role の付与は 1 名でできるため、盗まれた管理者が自分の別の OIDC 口座を管理者にして自分で承認する。対策として、利用者が管理者の role を得た時に有効な管理者全員へ通知する（`SysUserServiceImpl` の role の変更の経路。§7.5）。「承認者の管理者の role を申請者が付与した、または N 日以内に付与された」場合の拒否は、付与者と付与の時刻が `sys_user` に無く確かな記録が無いため本 batch では行わない（行うには DDL が要る。go-live の後の backlog）。 通知するのは利用者の作成と role の変更で管理者になった時と、無効の管理者を有効に戻した時（管理者の能力を新たに得る経路）。lock の解除は一時の状態の終わりで能力を新たに得ないため通知しない（実装 review rb-fe-a1 P3-8）。宛先は遷移の transaction の中で決め、送付は commit の後に新しい transaction で行う（P2-2）。
  - 両方を Security の既知の限界として RB §5.6 に書く。RB §5.6 に運用の規則「承認の前に、直近に管理者の role を得た利用者が承認者でないことを確かめる（全ての管理者への通知で知る）」を書き、付与の記録（付与者・時刻）の DDL を go-live の後の backlog の 1 項目として記録する（review P3-N10）。
- 承認者が申請の時と違う内容を承認しないよう指紋で守る（§4.5）。
- 開放には少なくとも 2 名の有効な管理者が要る。prod は起動時検査で OIDC 管理者 3 名以上（`ProductionSecurityConfigurationValidator.MIN_OIDC_ADMINISTRATORS` :59）を保証し、PR #130 の guard が人数を割る変更を拒否するため、常に 2 名を確保できる。

### 11.2 session の種別（推奨: break-glass と bootstrap は不可）

- 判定: OIDC の session（`OAuth2AuthenticationToken`＋`OidcLoginUser`）で、MFA 済み（`MfaEnforcementFilter.isVerifiedFor` :32-36）、break-glass の incident の属性が無い（`BreakGlassService.INCIDENT_ID_ATTRIBUTE`）、bootstrap の session でない（`ProductionBootstrapMode.isBootstrapSession` :328）、username が break-glass の名前でない（`OidcSecurityProperties.isBreakGlassUsername` :67）。先例は `PwaRestoreAuthorizationService.supportsPasswordReauthentication` :91-99。prod 以外は local の form login（`LoginUser`）も許す。
- 有効な管理者の判定は要求ごとに DB から読み直す（`SecurityContextCallerContextProvider.isCurrentActiveAccount` :130-139 と同じ: role・`status`・未削除・`locked_until`・tenant）。
- **break-glass を不可にする理由**: break-glass は IdP が使えない時の限られた操作のための口座で、incident の許可 action は 1〜20 の既知の action に限られ、`user.*`・`permission.manage`・`privacy.*` を既に拒否している（`BreakGlassServiceImpl.validateAndSerializeActions` :316-331）。機能の開放は緊急の操作ではなく（閉じる方向は通常の管理者 1 名でできる）、break-glass の口座で開けると、IdP の障害の最中に監視の少ない口座で公開範囲を広げられる。incident の許可 action で `feature-enablement.*` を拒否し、session の判定でも拒否する（二重）。
- **bootstrap を不可にする理由**: bootstrap は初回の IdP・管理者の登録のための期限付きの local の session で、使える入口は `user`・`identity-provider` と `permission` の参照だけ（`BootstrapSessionFilter` :64・:73・:135-156）。bootstrap の最中は OIDC の管理者が揃っておらず、承認者の独立性も無い。本機能の入口を許可の一覧に足さない（bootstrap の filter が 403 にする）。
- 閉鎖も同じ session の規則とする（break-glass の口座で閉じる必要がある場面は、配備の設定の総スイッチで代える）。
- 行為者の値（`FeatureEnablementActor`）を作れるのは `FeatureEnablementActorPolicy.resolve`（この session の規則を通した値）だけで、controller 等が利用者 ID から直接作れない（構築子は package の中だけ。有効な管理者の DB の読直しも同じ package の service だけが呼ぶ。実装 review rb-fe-a1 P3-7）。service は受け取った行為者を要求ごとに DB から読み直す。

### 11.3 敏感な機能の追加の条件

- **NF07 保持・保全・DSAR**: 申請者・承認者・閉鎖者と、NF07 の署名の種別の確認者を、privacy-admin に所属する有効な管理者（`UserPermissionGroupMapper#selectEffectivePrivacyAdminUsernames` と同じ条件。FS 4.7.4 の2）に限る（§18 J-05）。理由: privacy の操作は privacy-admin に分離されており（`'*'` を持つ他の group は `privacy.*` を拒否される。FS 4.7.1）、それ以外の管理者が privacy の入口の公開を決めると分離が崩れる。
- **DSAR**: 承認の時に D-06 の構造化された値（`due-days`・`package-max-age`）を承認者の画面に出し、決定書との照合を確認の checkbox で求める（FS 4.7.2 の人手の確認 (a) の置き換え）。
- **REL（hold 解除）・REG（policy の登録・有効化）**: 副入口の条件の署名（`NF07_D02`・`NF07_D03`・`NF07_D05`・`NF07_TRUST_*`）の確認者を privacy-admin に限る。要求ごとの既存の分離（解除の `error.privacy.holdReleaseSeparation`、有効化の `policyActivationSeparation`）は変えない。
- **NF02 顧客ポータル・公開範囲**: 顧客への公開を広げるため、公開範囲の変更も二人（§5.6）。
- **NF05**: 開ける変更は再起動を伴うため、再起動の前に「再起動待ち」を画面・通知・metric で示し、SRE が再起動の時刻を決める（自動の再起動はしない）。

### 11.4 監査

- `method`: `FEAT_EN`（7 文字。`t_audit_log.method` は `VARCHAR(10)`。`AuditLogMethodLengthGuardTest`）。
- `application_code`（`VARCHAR(64)`。A1 の実装の名前に揃えた。実装 review rb-fe-a1 P3-6）:
  - 開放の申請と開放単位: `FEATURE_OPEN_REQUESTED`・`FEATURE_OPEN_APPROVED`・`FEATURE_OPEN_REJECTED`・`FEATURE_OPEN_WITHDRAWN`・`FEATURE_OPEN_EXPIRED`（延長の申請の期限切れも同じ code）・`FEATURE_OPEN_STALE`・`FEATURE_REQUEST_CANCELLED`（閉鎖・総スイッチで未決を取り消した）・`FEATURE_CLOSED`・`FEATURE_AUTO_CLOSED`・`FEATURE_MASTER_CLOSED`（総スイッチで開放単位を閉じた回だけ）・`FEATURE_EVALUATION_UNVERIFIABLE`・`FEATURE_EVALUATION_RECOVERED`・`FEATURE_OPEN_CONDITION_EXPIRING`・`FEATURE_OPEN_CONDITION_LOST`・`FEATURE_OPEN_CONDITION_UNVERIFIABLE`（開放の時だけの条件を評価できない）・`FEATURE_SUBGATE_OPENED`・`FEATURE_SUBGATE_CLOSED`・`FEATURE_ADMIN_ROLE_GRANTED_NOTICE`（通知の送付の記録）。
  - 署名: `FEATURE_SIGN_OFF_REGISTERED`・`FEATURE_SIGN_OFF_CONFIRMED`・`FEATURE_SIGN_OFF_REJECTED`・`FEATURE_SIGN_OFF_REVOKED`・`FEATURE_SIGN_OFF_SUPERSEDED`・`FEATURE_SIGN_OFF_EXPIRED`・`FEATURE_SIGN_OFF_PURGED`・`FEATURE_SIGN_OFF_DOCUMENT_READ`（文書の取得）・`FEATURE_SIGN_OFF_EXTENSION_REQUESTED`・`FEATURE_SIGN_OFF_EXTENSION_STALE`・`FEATURE_SIGN_OFF_EXTENDED`。
  - 組: `FEATURE_SIGN_OFF_BUNDLE_REGISTERED`・`FEATURE_SIGN_OFF_BUNDLE_CONFIRMED`・`FEATURE_SIGN_OFF_BUNDLE_REJECTED`・`FEATURE_SIGN_OFF_BUNDLE_SUPERSEDED`・`FEATURE_SIGN_OFF_BUNDLE_REVOKED`。
  - 後の段階で足す: `FEATURE_RESTART_APPLIED`・`FEATURE_BOOT_READ_FAILED`（段階 D の NF05）。
  - 設定の変更（FE-B03 で足した）: `FEATURE_SETTING_REQUESTED`（申請）・`FEATURE_SETTING_CHANGED`（承認。本文は key・設定の有無・申請 ID だけ）・`FEATURE_SETTING_CHANGE_STALE`（承認の時の指紋の違い）。却下・取下げ・期限切れ・取消しは開放の申請と同じ共通の処理のため `FEATURE_OPEN_REJECTED`・`FEATURE_OPEN_WITHDRAWN`・`FEATURE_OPEN_EXPIRED`・`FEATURE_REQUEST_CANCELLED` を使い、申請の種類は参照する申請の行（`request_type`）で区別する（復審 rb-fe-b-656d71b8 の P3-7）。
- 本文（`before_state`・`after_state`）: 開放単位・状態・理由の code・申請 ID・署名 ID だけ。
- 自動の遷移は actor SYSTEM、`confirmationSource` は `SCHEDULER_POLL`（起動時の評価も同じ値で、uri `system:feature-enablement/startup`・`…/scheduler` で区別する。`ConfirmationSource` に `STARTUP` を足すと、`ExternalAccountReferenceDto`・`ExternalAccountReferenceMapper` の switch 式と V136 の `t_external_account_reference` の CHECK が既存の値の集合を前提にしているため、A1 では足さない。実装 review rb-fe-a1 P3-6）。
- 全ての遷移を INFO log `FEATURE_ENABLEMENT_EVENT`（code と ID だけ）にも出す（§2.7・§13.1）。
- 履歴（§2.7）と監査は同じ transaction で書く。

### 11.5 総スイッチ

§10。配備の設定にあるため、DB を書き換える権限を持つ者でも総スイッチは開けられない（閉じる方向の最後の手段）。

## 12. 失敗の形と fail-closed

| 失敗 | 挙動 | DB の状態 |
|---|---|---|
| 起動時に snapshot を読めない | 全ての DB 管理の開放単位を閉として起動する（他の起動時検査で止まらない限り）。poll で読めれば戻る | 変えない |
| snapshot が古い（> `max-staleness`） | 全て閉。WARN と metric | 変えない |
| commit の後の差替えに失敗 | 変更した開放単位を overlay で閉。次の poll | 変わっている（正） |
| 評価器の例外・照会の失敗（評価できない類の code を含む） | その開放単位を `unverifiable_since` で全 instance で閉（書けなければこの instance の memory）。評価の間隔の 3 倍続けば自動閉鎖（J-10）。承認・申請の時は 409 | `unverifiable_since`。続けば `AUTO_CLOSED` |
| 自動閉鎖の書込みの失敗 | 書込み待ち（sticky）で閉じたまま、条件の成否に関係なく次の周期で再試行 | 書けた時に `AUTO_CLOSED` |
| 評価器が無い | 申請・承認は 409。開いていれば起動時の評価で `AUTO_CLOSED(evaluator.missing)` | 自動閉鎖 |
| 監査の書込みの失敗 | 遷移ごと rollback。自動閉鎖は書込み待ち（sticky）のまま次の周期で再試行 | 変えない |
| PITR（backup からの復旧） | 開放状態・署名・barrier の行が復旧の時点へ戻る。§13.1 の手順（最初の起動は総スイッチを閉）で全て閉じてから二人で開き直す | 総スイッチの起動で `AUTO_CLOSED` |
| backup の静止の最中 | 書込みは静止の解除まで待つ（scheduler）か 503（API）。overlay は memory で効く | 解除の後に書く |
| 通知の失敗 | log だけ | 変わっている |
| NF05 の起動前の読取りの失敗 | NF05 を閉で起動。`BOOT_READ_FAILED` | 変えない |
| 総スイッチの値が解釈できない | 起動しない（設定の誤り） | ― |
| 旧い変数がある（prod） | 起動しない（§10） | ― |
| 時計の巻戻り | 期間は JST の業務日で判定し、署名日前は不成立（開く方向へ倒れない） | ― |

## 13. 移行と過渡

- **fresh install**: prod は空の DB への新規導入（共通規則 6 (l)）。V203 の表は空で、全ての開放単位は `CLOSED`。既存の値を移す data migration を作らない。
- **旧い変数**: prod では拒否（§10）。dev・test の property の源では従来どおり読む。
- **dev・test の既定**: `app.feature-enablement.source=properties`。test profile の全て開（`application-test.yml:208-239`）は変えない。本機能の H2 の試験は `@TestPropertySource(properties = "app.feature-enablement.source=database")` で DB の源を使い、`@Transactional` で rollback し、自分が入れた行だけを読む（`TestIsolationAuditTest`）。snapshot の refresher は試験では起動しない（試験が明示的に読み直す）。
- **local の Demo**: `dev` profile で `APP_FEATURE_ENABLEMENT_SOURCE=database` を付けて起動する（`application-dev.yml` に placeholder を置く）。prod 専用の評価器を持つ開放単位（NF01 二者確認以外の NF06・NF07・NF05）は dev では「評価器が無い」として申請できない（§4.4）。
- **段階の間**: §3.1 の印。go-live は段階 E の後。

### 13.1 PITR（backup からの復旧）の後（review P2-7）

以前は開閉が task definition にあり、DB を戻しても開閉は変わらなかった。本機能の後は開閉・署名・NF05 の barrier の行が DB にあり、復旧の時点へ一緒に戻る。復旧の時点より後に行った閉鎖・自動閉鎖・署名の取消しが消え、状態が `OPEN`・署名が `CONFIRMED` に戻り得る。NF05 は戻った `OPEN` で次の起動に開き、barrier の行が `v1|ON|…` に戻っていれば `initializeSafely`（:94-96）が `ready` を true にし、隔離を飛ばす。安全のための閉鎖を承認なしに戻さないため、次を必須にする:

1. `ops/backup/runbooks/restore-cutover.md` に手順を足す: 復旧の後の **最初の起動は `FEATURE_ENABLEMENT_ALLOWED=false`** で行う。restore の必須の checklist に「次の起動の task definition は `FEATURE_ENABLEMENT_ALLOWED=false`」を入れる（`cutover.sh` に確認の prompt を足せるかは RB-W の担当が判断する。人の手順だけに頼る点は残る。review P3-N4）。起動時の評価が全ての開放単位を `AUTO_CLOSED(MASTER_SWITCH_CLOSED)` にし、未決の申請を `CANCELLED` にし、boot resolver が NF05 を閉にし、barrier は OFF の起動で `v1|OFF|0|<指紋>` に書き直される（次に開く起動で隔離が走る）。
2. 復旧の時点より後の `FEATURE_ENABLEMENT_EVENT`（CloudWatch Logs。DB の外に残る。§2.7）を、復旧した DB の履歴と照合し、(a) 復旧の時点より後に取り消した署名・組と、(b) 復旧の時点より後に手動・自動で閉じた開放単位とその理由を一覧にする。(a) は取り消し直し（`REVOKED`）、(b) は理由が今も当てはまらないことを確かめてからでないと、開き直しを申請しない。照合の手順と Logs Insights の query の例を restore-cutover に書く。CloudWatch Logs の log group の保存期間は、PITR で戻れる期間（binlog の保存期間）以上にする（review P3-N4。SRE の設定項目として RB に書く）。
3. 総スイッチを `true` に戻して再起動し、各開放単位を二人で開き直す（J-11。誤って `false` にした場合と同じく、13 件の再承認が要る。RB に書く）。
4. `.kiro/specs/production-launch/dba-prelaunch-draft.md` §1 の `CONTENT_HASH_TABLES` の候補 B に `t_feature_enablement_state`・`t_feature_sign_off`・`t_feature_sign_off_extension`・`t_feature_sign_off_bundle`・`t_feature_sign_off_bundle_member` を足す（小さな表で、復旧の誤りが黙って機能を開く方向に働くため）。

自動の検出（復旧の印を DB に書いて起動時に閉じる）は採らない: 復旧の道具が書く印も同じ DB の復旧で消え得るうえ、印の書込みは手作業の DB の変更（RR-10）になる。配備の設定の総スイッチは DB の復旧に影響されない。

## 14. PR #130（管理者の継続性 guard）との関係

- PR #130 の `AdministratorContinuityGuard` は、prod で起動時検査（OIDC 管理者 3 名以上・break-glass 2 名・privacy-admin 2 名以上）を割る変更を拒否する。privacy-admin の条件は、現在「NF07 の flag に関係なく」判定している（`PrivacyProductionConfigurationValidator.checkAdministratorConditions` の javadoc :176-183。理由は「OFF の間に人数を割ると ON にする起動が止まるため」）。
- 本機能の後、privacy-admin の人数は起動を止める条件ではなく、NF07 の開放の条件（割れば NF07 だけが自動閉鎖）になる。そこで guard の privacy-admin の条件は、NF07 保持・保全か DSAR が開いている（`OPEN`）か未決の申請がある時だけ判定する（`evaluated`）ように改める（NF07 に再起動待ちの状態は無い。`ck_fe_state_restart` は NF05 だけ。review P3-21）（2026-11-22 の決定は「NF07 が ON の時に privacy-admin 2 名以上」を守る対象にしており、これに合う。§18 J-17）。閉じている間は `notEvaluated`。
- guard は開放状態を snapshot ではなく DB から読む（guard の transaction の中で最新を見るため。lock は取らない）。申請の作成と guard の並行は、承認の時の評価し直し（§5.3）と定期の評価で閉じる。
- guard が開放状態の表を読めない時は、人数を比べられないとして `unverifiable` を返し、guard は利用者の管理の変更（停止・削除・role の変更・privacy-admin の割当の解除）を全て拒否する（PR #130 の fail-closed と同じ。RB §5.6 に書く）。
- OIDC 管理者 3 名以上・break-glass 2 名の条件は機能の開放と関係しないため、変えない（起動時検査も guard も現状のまま）。二人の承認が常に可能なのはこの条件による（§11.1）。

## 15. 画面と API

### 15.1 API（全て `ApiResult`。`/api/feature-enablement`）

| method・path | 内容 | 行為者 |
|---|---|---|
| `GET /units` | 一覧（DB の状態・**実効値と閉じている理由**（一時の閉鎖・snapshot の古さ・期限の経過で自動閉鎖の書込みの前・この起動では閉）・申請中・再起動待ち・`openUntil`・最後の評価の時刻と成否の件数。review P3-10） | 管理者 |
| `GET /units/{key}` | 詳細（条件の一覧、要る署名と状態、鍵の有無、子、未決の申請、DUAL template の件数） | 管理者 |
| `POST /units/{key}/evaluate` | その場で 1 件を評価（1 分に 1 回まで。`Purpose.DISPLAY` で、結果を返すだけで状態を遷移させない。review P3-20） | 管理者 |
| `POST /units/{key}/close` | 閉鎖（理由） | 管理者（NF07 は privacy-admin） |
| `POST /requests` | 申請（`type`・`target`・`value`・理由） | 同上 |
| `POST /requests/{id}/approve`・`/reject`・`/withdraw` | 承認・却下・取下げ（`expectedVersion`） | 同上 |
| `GET /requests` | 未決と最近の申請（`PageUtils.safePage`） | 管理者 |
| `GET /sign-offs`・`GET /sign-offs/{id}` | 署名の一覧（氏名なし）・詳細（氏名あり） | 管理者 |
| `POST /sign-offs`（multipart） | 登録 | 管理者（NF07 の種別は privacy-admin） |
| `POST /sign-offs/{id}/confirm`・`/reject`・`/revoke`・`/extend` | 確認・却下・取消し・延長（証拠の類だけ。§6.5） | 同上 |
| `POST /sign-off-bundles`（multipart）・`POST /sign-off-bundles/{id}/confirm`・`/reject`・`/revoke` | 署名の組の登録（新しい署名と引き継ぐ署名）・確認・却下・取消し（§2.6.1） | privacy-admin |
| `GET /sign-offs/{id}/document` | 文書（hash を照合して返す） | 管理者 |
| `GET /events` | 履歴（`PageUtils.safePage`。subject・期間で絞る） | 管理者 |
| `GET /catalog` | 目録（種別・役割・上限。画面の選択肢） | 管理者 |

- action の key は `ActionPermissionResolver` の規則どおり（`GET` → `feature-enablement.view`、`POST` → `feature-enablement.create`）。行為者の細かな規則（§11）は service 層で判定する。
- 誤りの code は 4 つの bundle の `error.featureEnablement.*`。

**実装で決めた細部（FE-A09）**:

- 読取りは `FeatureEnablementQueryService`（一覧・詳細・再評価・申請と履歴の頁・組の一覧・目録）。閲覧も含め全ての API が `FeatureEnablementActorPolicy.resolve`（session の種別と有効な管理者の読直し）を通る（J-13）。
- 一覧の「最後の評価」は `FeatureEnablementEvaluationResults`（この instance の memory。起動時・定期・画面の再評価の結果を開放単位ごとに1件）から出す。開いていない開放単位は定期の評価をしないため、再評価を押すまで「未評価」。再評価は開放単位ごとに1分に1回（それより早ければ 429 `error.featureEnablement.evaluateTooSoon`）。
- `POST /requests` の `type` は段階 A では `OPEN` と `SIGN_OFF_EXTENSION`（`target` は `signoff:<id>`、`value` は新しい失効日）だけ。`SETTING_CHANGE` は FE-B03 で足した（`target` は設定の key、`value` は新しい値で null・空は未設定へ戻す。設定の一覧は `GET /settings`。§5.6）。延長は `POST /sign-offs/{id}/extend` からも申請できる。表に無い取下げ（`POST /sign-offs/{id}/withdraw`・`/sign-off-bundles/{id}/withdraw`。登録者だけ）も置く。組の一覧は `GET /sign-off-bundles`。
- 承認の結果が承認でない時は、service の transaction の commit の後に controller が 409（`Outcome.messageKey()`、成り立たない条件の code を引数）にする。
- 署名の詳細は氏名を含むが、保存名・元の file 名を返さず、`structuredValues` のうち名前が `digest` で終わる値と文書の SHA-256 は先頭 16 文字だけにする。一覧（`Summary`）は氏名を含めない。文書の取得の file 名は `<種別>-<id>.pdf`（`Cache-Control: no-store`）。
- 組の登録は multipart で、part `request` に JSON（`bundleKind`・新しい member・`carriedOver`）、新しい member ごとに文書の part `file.<種別>`。
- `FeatureSwitchRouteCoverageTest` の `UNGATED_ROUTE_PREFIXES` には登録しない: controller は NF の service package に依存せず、action の resource も NF に属さないため NF の入口として検出されず、対象外の表は NF 配下の入口を意図して遮断しない時のための表であるため（登録しても同試験の「古い登録」の検査は全ての controller の入口と比べるため落ちないが、意味が違う。実装 review rb-fe-a2 P3-4）。

### 15.2 画面

- `templates/feature-enablement/list.html`（`layout:fragment="content"`・`page-js`）と `static/js/modules/feature-enablement.js`。tab: 「開放単位」「申請」「署名・受理」「設定」「履歴」。
- 開放単位の tab: 状態の badge（未開放・申請中・開放中・再起動待ち・自動閉鎖（理由）・手動閉鎖・この起動では閉）と、**実効値の badge**（開・閉と閉じている理由: 評価できない・snapshot が古い・期限が過ぎて自動閉鎖の書込みの前・この起動では閉。DB の状態が「開放中」でも利用者に 404 が出ている状態を管理者が見分けられるようにする。review P3-10）、条件の ✓/✗（種類と scope ごと）、`openUntil`、操作の button（申請・閉鎖・再評価）。詳細は modal。
- 申請の tab: 未決の申請（申請者・時刻・条件の要約・期限）と承認・却下・取下げ。自分の申請には承認の button を出さない（API も 403）。
- 署名・受理の tab: 種別ごとの現在の署名（状態・類・期間・残り日数・登録者・確認者）、登録の modal（種別を選ぶと役割の選択肢と構造化された値の入力欄が変わる。digest を持つ種別は system の計算値の先頭 16 文字を出し、文書の値と照合させる。NF05 の DG-05 は現在の配備の設定から計算した値との一致・不一致を出す）、署名の組の登録の modal（NF07 の D-01・D-09（・D-06）を 1 画面で、変わらない署名は「引き継ぐ」を選ぶ）、確認・却下・取消し・延長（証拠の類だけ）。署名日が今日より後の署名は確認の button を出さず、署名日を示す。
- 履歴の tab: `.card > .card-body > form#searchForm` の構造で絞込みを置く（`SES.filterPanel` が phone で畳む）。
- 規約: `SES.csrf.header()`、`res.code === 200`、`Toast`、閉鎖・取消しは `Swal.fire` で確認、`/lib/...` の同梱の library だけ。
- 実装で決めた細部（FE-A10）: 「設定」tab は公開範囲の変更（FE-B03）と一緒に足す（段階 A では置かない）。署名の却下・取消しの理由は code の列（`^[A-Z][A-Z0-9_]{0,63}$`）のため、画面は区分の選択肢（却下: `DOCUMENT_MISMATCH`・`ROLE_MISMATCH`・`DATE_MISMATCH`・`REFERENCE_MISMATCH`・`OTHER`、取消し: `DECISION_WITHDRAWN`・`DOCUMENT_ERROR`・`SECURITY_CONCERN`・`OTHER`）を出す。申請の却下の理由は自由記述（必須）。自分の登録した署名・組には確認・却下の button を出さず取下げだけを出す。組に属する種別の1件ずつの署名には確認の button を出さない（API も 409）。NF05 の DG-05 の一致・不一致の表示（FE-D05）と NF01 二者確認の閉鎖の警告の DUAL template の件数（FE-B01）は各段階で足す。

## 16. 試験の方針

| 区分 | 試験 | 内容 |
|---|---|---|
| unit | `FeatureEnablementSnapshotTest`・`DatabaseFeatureEnablementStateSourceTest` | 総スイッチ・状態・`openUntil`（JST 0:00 の境）・overlay・古さ、照会 0 回 |
| unit | `FeatureConditionRegistryTest`・`SignOffConditionEvaluatorTest`・`FeatureConditionFingerprintTest` | 共通の評価器、例外の `UNVERIFIABLE`、評価器なし、指紋の正規形（golden vector） |
| unit | 各評価器の試験（`LifecycleDualConfirmationEnablementConditionsTest`・`ImportCenterEnablementConditionsTest`・`PrivacyProductionConfigurationValidatorTest` の追加・`IntegrationHubProductionEnablementPolicyTest` の追加） | 起動時検査と同じ入力で同じ code になること（判定の写しが無いこと） |
| H2 | `FeatureEnablementServiceH2Test` | 申請・承認・却下・取下げ・期限切れ（申請の時の遅延の期限切れを含む）・陳腐化（`STALE` が commit されてから 409）・閉鎖・cascade、行為者の規則、監査と履歴の行、CHECK・一意索引・複合 FK（H2）、要る署名が欠けると `openUntil` が閉、変更の transaction の最初の失効の閉鎖 |
| 非 transaction の試験 | `FeatureEnablementAfterCommitRefreshTest` | commit の後の snapshot の読直し（`@Transactional` の rollback の試験では `afterCommit` が動かないため、専用 DB で明示的に後始末する。`TestIsolationAuditTest`） |
| H2 | `FeatureSignOffServiceH2Test` | upload の scan（`CLEAN` でなければ実体を残さない）・hash・照合、二人の確認、署名日が未来の確認の拒否、差替え・取消しでの自動閉鎖、失効した旧い署名の差替えで先に自動閉鎖、汎用の取得・再 scan の拒否、延長（証拠の類）、上限（決定 366 日・証拠 1,095 日）、消去（氏名・元の file 名）と hold の gate |
| H2 | `FeatureSignOffBundleH2Test` | 組の登録・確認（1 回）・引継ぎ、組の一部だけの確認の拒否、組の中の digest の不一致の拒否、開いている NF07 保持・保全・DSAR の D-01 の更新で両方が開いたまま、D-06 の失効で DSAR だけが閉じる、**D-06 の行が `EXPIRED` になった後の次の定期の評価と、D-06 を 1 件だけ `REVOKED` にした後も、保持・保全は `OPEN` で DSAR は閉じている**（review P2-N1）、member の種別・tenant の複合 FK |
| H2 | `FeatureSignOffExtensionH2Test` | 延長の申請と別の管理者の承認で同じ行の期限が延び、延長の記録が 1 行（旧い期限・新しい期限・確認者）、申請者本人の承認 403、決定の類は延長できない、上限は承認の日から 1,095 日、延長の後に旧い期限から 3 年を過ぎても文書を取得できる（review P2-N2） |
| H2 | `FeatureEnablementConditionSchedulerH2Test` | 定期・日の始まりの評価、`void`、自動閉鎖と通知、`OPEN_ONLY`（NF02 の暦）が外れても閉じず監査と通知だけ、`OPEN_TRANSITION` を判定しない、評価できない類の code（`journal.unreachable`・`privacyAdmins.unverifiable`）で 3 回の間は `unverifiable_since` だけ、自動閉鎖の書込み待ちが条件の回復で外れない、DSAR が閉じている間の未完了の請求の通知 |
| unit | `FeatureConditionCodeClassificationTest` | 各評価器が出し得る code の一覧と「評価できない類」の分類の突合せ |
| MVC | `FeatureEnablementApiControllerTest` | 権限（管理者以外 403、group の有無）、`ApiResult`、CSRF |
| 既存 | `FeatureSwitchFilterTest`・`FeatureSwitchMenuAndViewTest`・`FeatureSwitchSchedulerGateTest`・`FeatureSwitchRouteCoverageTest`・`FeatureSwitchRuntimeConditionTest`・`ActionPermissionMatrixTest`・`MessageBundleConsistencyTest`・`StaticAssetLocalityTest`・`AuditLogMethodLengthGuardTest`・`ScheduledMethodsHaveSchedulerLockTest`・`SchedulerLockProxyInvocationTest`・`SchedulingDisabledDrainTest`・`TestIsolationAuditTest`・`MigrationScriptIntegrityTest`・`DeployRunbookMigrationCountTest` | 変えずに（または一覧の追加だけで）通る。DB の源でも同じ OFF の応答になる変種を足す |
| 静的 | `FeatureEnablementCatalogCoverageTest`・`PrivacyFeatureFlagReadGuardTest`・`IntegrationHubFlagReadGuardTest`・`FeatureEnablementLegacySettingTest` | 目録の網羅、直接の読取りの禁止、旧い変数の一覧と ps1 の一致 |
| MySQL（`@Tag("mysql")`。shard は統括が指定） | `FeatureEnablementSchemaMySqlTest` | V203 の CHECK（3819 は `MySqlConstraintRejections.assertRejectedBy`）・一意索引・trigger（更新・削除の拒否） |
| MySQL | `FeatureEnablementConcurrencyMySqlTest` | 同じ申請の承認の並行（1 件だけ成功）、承認と閉鎖の並行、署名の取消し・差替え × 承認、定期の自動閉鎖 × 承認、世代の lock の順、guard と申請の並行 |
| MySQL | `IntegrationHubDeliveryTransientClosureMySqlTest` | 一時の閉鎖（`unverifiable_since`）の間の業務の変更の配信が回復の後に送られる、閉じた時に処理中の配信が DLQ にならず PENDING に戻って送られる、確定の閉鎖の後は処理中が DLQ `KILL_SWITCH` で barrier が OFF・開き直しは再起動と隔離を経る（review P2-N3） |
| MySQL | `IntegrationHubEnablementRestartMySqlTest` | boot resolver の読取り、承認 → 再起動で `OPEN` → 定期の評価の後も `OPEN` → もう一度再起動しても `OPEN` で隔離が走らない（review P1-1）、閉鎖での barrier の書き直しと次の起動の隔離、`AUTO_CLOSED` の後の再起動で purge の scheduler が作られる（review P2-5）、schema が違うと `READ_FAILED` |
| H2 | `PrivacyJournalProtectionResultTest` | reader の principal で NF07 が閉の時に hold gate が AVAILABLE、appender の再検査の失敗で hold gate が下がらない、開放の条件は appender を要する（review P2-4） |
| MySQL | `FlywayProdFreshInstallMySqlTest`（期待値の更新） | V203 の表・trigger 数 |
| 実 Tomcat | `FeatureSwitchServletContainerIntegrationTest` の DB の源の変種 | 閉じた機能の 404 が property の源と同じ |
| browser（`-Pbrowser-tests`。統括が実行） | `FeatureEnablementScreenBrowserTest` | 一覧・登録・確認・申請・承認・閉鎖の画面の流れ、phone 幅の絞込み |
| ps1 | `infra/ecs/offline/test-validate-task-definition.ps1` の追加 | 旧い変数の FAIL（両 phase）、総スイッチの置き場所と値（原文の `true`/`false` だけ）、`APP_FEATURE_ENABLEMENT_*` 等の運用値の上書きの FAIL |
| 静的 | `ApprovalValueReadGuardTest` | 承認の値の properties の getter を property の源だけが呼ぶ（review P3-15） |

- 新しい `@Tag("mysql")` の試験は、足す commit と同じ commit で `scripts/test-suites/mysql-shard-{1,2,3}.txt` のどれかに登録する（`MySqlTestShardInventoryTest`。shard は着手の時に統括の指示を受ける）。

## 17. 文書・運用の更新箇所

- FS: 1 の一覧（環境変数の列を「配備の設定には無い。画面で開く」に、test profile の列は残す）、1 の「値は起動時に1回だけ読む」等の記述（:32・:131 の「必ず再起動」）を改める、3 の手順を画面の手順へ（3.1〜3.4）、4 の各機能の「ONの条件」「ONにする手順」を「開放の条件（画面の表示）と署名の種別」へ、4.1 に scheduler 無効の時の制約、5 の「新しい入口を追加するとき」に目録の登録を足す、6 の試験の表。
- RB: 冒頭の NO-GO の条件（機能の ON を go-live の前提から外す）、§1.0（総スイッチと、`FEATURE_ENABLEMENT_*`・`APP_FEATURE_ENABLEMENT_*` の運用値を配備の設定に置かないこと・旧い変数の「設定してはいけない値」・NF05 の起動前の DB の読取り）、trigger と表の件数が出る全ての箇所（§1.2・§1.7 の DBA-1・W-1 の行・§3.4 等）、総スイッチを誤って `false` にした代償（13 件の再承認）、NF05 の truststore 等の差替えの流れ（§9.4。再起動 2 回）、DSAR が閉じている間の法定の期限の扱い、「ONにした時だけ必須になる設定」を鍵と基盤の設定だけに、§1.2（起動前の読取りの受理）、§1.7（go-live の前提は基盤と中核、署名表は「system の中で開く前提」、ON の起動の準備のうち自動検査に置き換わる行）、§1.7.1（二者確認の手動確認は署名 `NF01_DUAL_MANUAL_CHECK` として登録）、§3.4（V203 の確認と trigger 数）、§4.4（D17: 署名の消去）、§4.2（bootstrap の間は本画面を使えない）、§5.5 の後（開放の手順）、§5.6（二人の承認と同一人物の複数口座の限界）。
- `infra/ecs/task-definition.prod.json`: `FEATURE_ENABLEMENT_ALLOWED=true`、注記の機能スイッチの記述を改める。
- `infra/ecs/offline/validate-task-definition.ps1`: :316-321 の機能スイッチの名前の照合を旧い変数の拒否（両 phase）に、:576・:589 以降の Initial phase の機能スイッチの検査を削除、`FEATURE_ENABLEMENT_ALLOWED` を `mustBeVisible` に。
- 各 spec の「ONにする手順」を参照している文書（`engineer-lifecycle-workflow/runbook.md`、`privacy-retention-dsar/production-bridge-phased-plan.md`、`integration-hub-public-api/dg-05-enablement-decision-draft.md`、`ops/security/runbooks/integration-hub-public-api.md`、`data-migration-import-center/a1a-ingress-operations.md`）は段階 E で参照先を本 spec と FS に改める。
- `ops/backup/runbooks/restore-cutover.md`: PITR の後の手順（§13.1）。`.kiro/specs/production-launch/dba-prelaunch-draft.md` §1: `CONTENT_HASH_TABLES` の候補 B に本機能の 5 表。`journal-witness-grant-design.md`: reader 方式を続けることと、開放の条件は appender を要することの注記（§8.5 の 3。方式は変えない）。
- `infra/ecs/README.md:27`（機能スイッチの記述）、`certification-learning-skill-gap/first-release-scope-confirmation.md`・`mobile-pwa-self-service/first-release-user-and-operations-notes.md`（ON の手順の参照）。
- `application-prod.yml:130-152` の機能スイッチの placeholder（`${FEATURE_NF0x_ENABLED}` 等）を削除する（prod は DB の源）。
- CLAUDE.md（NF01・NF07 の起動を止める gate の記述、Roadmap の表の記述）: worker は変えない。統括が段階 E の後に改める。

## 18. 判断が要る点

| # | 論点 | 選択肢 | 決定または推奨と理由 |
|---|---|---|---|
| J-01 | D-5「配備の設定には鍵と総スイッチだけ」の範囲 | (a) 機能スイッチと署名の値だけを移し、基盤の設定（NF05 の bound-tenant-id・CIDR・送信先・trusted proxies・mode・truststore の path、NF07 の Journal の接続・trust 文書の path・鮮度の上限、DSAR の通知日数）は残す。(b) 基盤の設定も DB に移す | **決定済み（2026-11-22、利用者）: (a)**。機能スイッチと署名の値だけを system へ移し、基盤の設定は配備の設定に残す（内容は署名の digest で決定に束縛する）。理由: 基盤の設定は bean・Tomcat・接続 pool の構成に起動時に使い、SRE が持つ値で、DB に移しても再起動は残る。NF05 の承認の内容（CIDR 等）は digest で決定に束縛されるため、配備の設定に残しても決定と食い違えば開かない |
| J-02 | dev・test の源 | (a) property のまま、DB は opt-in。(b) 全 profile で DB | 推奨 (a)。**独立 review で同意（2026-11-22）**。既存の試験の意味を保ち（test は全て開）、H2 の schema の 2 系統の負担を増やさない |
| J-03 | 副入口（hold 開始・REL・REG）を独立の開放単位にするか | (a) しない（署名の二人の確認で開く。条件が外れれば副入口だけ閉じて警告）。(b) NF07 保持・保全の子の開放単位にして、開放の申請と承認を別に要する | 推奨 (a)。**独立 review で同意（2026-11-22）**。依頼の分け方（保持・保全に REL・REG を含める）に合い、副入口の署名は privacy-admin の二人の確認を経る。(b) は二人の操作が二重になる。副入口の自動の再開は D-4 の例外として要件 FE-RQ-005 に理由を書き、`NF07_D05` は副入口の条件とした（review P3-16・P3-17）。**REL・REG についてこの例外は利用者が確認した（2026-11-23。要件 §2 の D-10）**: 技術の条件の回復で自動で開き、署名に依る回復は新しい署名の二人の確認を要する。hold 開始の自動の再開は J-03 の推奨（独立 review で同意）のままで、利用者の確認の範囲外 |
| J-04 | break-glass・bootstrap の session で申請・承認できるか | (a) どちらも不可。(b) break-glass は可 | 推奨 (a)。**独立 review で同意（2026-11-22）**。§11.2。独立 review の任意の改善案（閉鎖を `DELETE /units/{key}` にして `feature-enablement.delete` を break-glass の incident に許す）は採らない: IdP の障害の間の閉鎖は配備の設定の総スイッチで足り、break-glass の口座に本機能の action を一切許さない方が境界が単純なため |
| J-05 | NF07 の行為者を privacy-admin に限るか | (a) 限る。(b) 管理者なら可 | 推奨 (a)。**独立 review で同意（2026-11-22）**。§11.3 |
| J-06 | 署名の登録に二人の確認を求めるか | (a) 求める。(b) 1 名で登録、開放の承認でだけ二人 | 推奨 (a)。**独立 review で同意（2026-11-22）**。§6.4（差替えは開放の承認を経ないため）。互いに束縛し合う署名は組で 1 回確認する（§2.6.1。review P1-2） |
| J-07 | 未決の申請の期限 | 7 日 / 30 日 / 無期限 | 推奨 7 日。**独立 review で同意（2026-11-22）**。古い条件で承認させない。申請の時に遅延で期限切れにする（§5.2。review P3-7） |
| J-08 | 署名の有効期限の上限 | (a) 全種別 366 日。(b) 類ごと（決定 366 日、受理・実測・review の証拠 3 年、二人の再確認で延長） | **統括の決定（2026-11-22）: (b)**。**暫定で、最終は各 owner と Privacy が確認する**。決定の類（NF07 の D-xx、NF06 の admission、DG-05 等）は 366 日、証拠の類は 1,095 日で、同じ文書・同じ署名日のまま二人の再確認で延長できる（§6.5）。理由（独立 review）: 全て 366 日だと 50 余りの種別が毎年同じ時期に一斉に再署名・自動閉鎖になる |
| J-09 | 時間要因の扱いの変更 | 2026-11-14 の「失効は OFF 扱いで起動、署名日前は署名日に自動で開く」を D-4 で置き換え、自動では開かない。NF06 の期間外は「upload だけ 403」から「NF06 を閉じる（404）」に変わる | **決定済み（2026-11-22、利用者）: 新しい規則に置き換える**（D-4 の帰結）。署名の失効でその開放単位を自動閉鎖して警告し、NF06 の期間外は NF06 全体を閉じる（404）。署名日前の署名では開かず、署名日以降に管理者の申請と承認で開く。2026-11-14 の時間要因の規則（失効・署名日前は起動を止めず OFF 扱い、署名日に自動で開く。FS 4.7.2.1・`ImportAdmissionStartupValidator:75`）をこれで置き換える |
| J-10 | 評価できない時 | (a) 直ちに一時の閉鎖、3 回続けば自動閉鎖。(b) 直ちに自動閉鎖。(c) 一時の閉鎖のまま | **統括の決定（2026-11-22）: (a) に 3 項を加える**: ① 既存の検査が違反 code に変えて返す照会の失敗を「評価できない類」として分類する（§4.2）。② 自動閉鎖を DB に書けない時は、条件が戻っても一時の閉鎖を外さず書込みを再試行する（§3.5・§7.4）。③ 評価できない状態を `unverifiable_since` で DB に持ち、全 instance で一致させる（再起動・ShedLock の持ち主の移動で数え直しにならない。§7.4） |
| J-11 | 総スイッチを開に戻した後 | (a) 自動では開かない（申請と承認）。(b) 戻せば元どおり | 推奨 (a)。**独立 review で同意（2026-11-22）**。D-4 と同じ扱いで、緊急停止の後に各機能を確かめ直す。誤って `false` にした代償（13 件の再承認）と PITR の後の使い方（§13.1）を RB に書く |
| J-12 | prod の旧い変数 | (a) 起動を止める。(b) WARN で無視 | 推奨 (a)。**独立 review で同意（2026-11-22）**。無視すると運用者が開閉を誤解する。新規構築のため移行の負担は無い。system property も見て、総スイッチは原文の文字列で検査する（§10。review P3-13） |
| J-13 | 画面の閲覧者 | (a) 管理者だけ。(b) マネージャーにも参照 | 推奨 (a)。**独立 review で同意（2026-11-22）**。条件と署名の参照は安全上の構成を表す。必要になれば `feature-enablement.view` の seed を足す |
| J-14 | 署名者の氏名と文書の保存期間 | 失効・取消し・差替えから 3 年 / 5 年 / 無期限 | 推奨 3 年（暫定）。**独立 review で同意（2026-11-22）**。法務・Privacy の確認が要る。元の file 名も消す（§6.6。review P3-4）。開放の根拠の証拠に 3 年を超える法定の保存が要るかを確認の時に合わせて確かめる |
| J-15 | DSAR の `due-days`・`package-max-age` | (a) D-06 の署名の構造化された値（digest v1 には入れない）。(b) 運用の値として配備の設定に残す。(c) digest v2 に入れる | 推奨 (a)。**独立 review で同意（2026-11-22）**。D-06 の値で、差替えに再起動を要しないため（期限日は受付の時に `due_date` へ書かれるため、値を変えても受付済みの請求の期限は動かない）。(c) は決定書の digest の手順が変わるため別の判断 |
| J-16 | NF02 の暦の条件 | (a) 継続の条件（暦が切れれば NF02 を閉じる。30 日前から警告）。(b) 開放の時だけの条件 | **決定済み（2026-11-22、利用者）: (b)**。申請・承認の時に当年度の全社共通カレンダーを必須にし、その後は継続の条件にせず NF02 を自動閉鎖しない（`Scope.OPEN_ONLY`。§4.2）。暦の有効期間の終わりの 30 日前から毎日、監査と管理者への通知で警告する（§7.4）。暦が無い間は現行の WARN `missing_calendar` と、土日だけを休日とする計算を続ける。worker の推奨は (a) だった |
| J-17 | PR #130 の guard の privacy-admin の条件 | (a) NF07 が開いている・未決の申請の時だけ判定。(b) 現在どおり常に判定 | 推奨 (a)。**独立 review で同意（2026-11-22）**。§14（NF07 に再起動待ちは無い。guard が表を読めない時は利用者の管理の変更を全て拒否する） |
| J-18 | 親の閉鎖で子を閉じるか | (a) 子も自動閉鎖（再承認が要る）。(b) 子の状態は残し、実効値だけ閉 | 推奨 (a)。**独立 review で同意（2026-11-22）**。D-4（条件「親が開」が外れたら閉じる）に合う |
| J-19 | 閉鎖の人数 | (a) 管理者 1 名。(b) 二人 | 推奨 (a)。**独立 review で同意（2026-11-22）**。閉じる方向は安全側で、緊急に遅らせない。手動の閉鎖と署名の取消しは有効な管理者全員へ通知する（§7.5。review P3-9） |
| J-20 | NF05 の起動前の DB の読取り | (a) `EnvironmentPostProcessor` で生の JDBC 接続 1 本。(b) NF05 の bean を常に作り実行時に開閉する大改修 | 推奨 (a)。**独立 review で同意（2026-11-22。条件つき）**: §9.1 の 0 の実装の条件（順序、enabled を常に書く、schema の違いは `READ_FAILED`、test・H2 での挙動、判定は純粋な関数の共用）を満たし、`OPEN` の outbound は barrier の tenant の指紋だけを見る（review P3-14・P1-1）。D-2（NF05 は再起動で反映）の範囲で最小。DBA・Security の受理を RB §1.2 に足す |
| J-21 | NF02 の公開範囲の決定を差し替えた後の設定との食い違い（段階 B の復審 rb-fe-b-656d71b8 の P3-1） | (a) 顧客ポータルの継続の条件にする（範囲の外なら自動閉鎖）。(b) 警告だけ | **決定済み（2026-11-23、統括。D-4 に従う）: (a)**。今の設定が確認済みの決定の範囲の内でなければ顧客ポータルの問い合わせだけを自動閉鎖して警告し、開き直しも範囲の内を要する（§5.6） |

## 19. 独立 review（`96be3af4`）の指摘と対応箇所

| 指摘 | 対応 |
|---|---|
| P1-1 NF05 outbound が開いた直後に自分の条件で閉じる | `Scope.OPEN_TRANSITION`（§4.2）、barrier の条件の分割（§9.1 の 5・§9.4）、`OPEN` の再起動は指紋だけ、試験（§16 の `IntegrationHubEnablementRestartMySqlTest`） |
| P1-2 NF07 の署名の差替えで必ず閉じる・D-06 の失効で保持・保全も閉じる | 署名の組（§2.6.1・§6.5）、期間は開放単位の決定だけ（§3.2・§8.5 の 1・5）、試験（`FeatureSignOffBundleH2Test`） |
| P2-1 照会の失敗が違反 code で即自動閉鎖 | 評価できない類の分類（§4.2）、`FeatureConditionCodeClassificationTest` |
| P2-2 一時の閉鎖の 4 つの穴 | (a) 書込み待ちの sticky（§3.5・§7.4）、(b) 変更の transaction の最初に失効を閉じる（§5.1・§6.5）、(c) `unverifiable_since` の共有（§2.3・§7.4）、(d) NF05 の一時の閉鎖からの同じ process での再開の理由（§9.3） |
| P2-3 申請・承認を memory の snapshot で評価 | transaction の中の読直し（§3.2・§5.1〜§5.3）、要る署名の欠落で `openUntil` が閉（§3.2） |
| P2-4 Journal の appender 固定で hold gate が止まる | 結果を 2 つに分ける（§8.5 の 3）、`PrivacyJournalProtectionResultTest` |
| P2-5 NF05 を閉じた後の起動で purge が作られない | purge 用の property（§9.1 の 5a） |
| P2-6 署名日が未来の署名で旧い署名が差し替わる | 確認は署名日の当日から（§2.6・§6.5） |
| P2-7 PITR で閉鎖が承認なしに戻る | §13.1（総スイッチを閉で最初の起動、DB の外の履歴との照合、`CONTENT_HASH_TABLES`） |
| P3-1〜P3-21 | §1・§2・§3・§5・§6・§7・§8・§9・§10・§11・§14・§15・§16・§17 の各所（worker の報告に一覧する） |

`85840936` の delta review の指摘:

| 指摘 | 対応 |
|---|---|
| P2-N1 組の member の状態と digest の入力 | 入力は現在 `CONFIRMED` の組の全 member（状態に依らない）、member の有効性は開放単位ごとの署名の条件、member の 1 件の取消しで組は `CONFIRMED` のまま（§2.6.1・§6.5・§8.5 の 1）。試験（`FeatureSignOffBundleH2Test`） |
| P2-N2 延長と `uk_fe_sign_off_stored_name` | 同じ行の期限の更新と追記専用の延長の記録（§2.6.2・§6.5・§6.6）。試験（`FeatureSignOffExtensionH2Test`） |
| P2-N3 一時の閉鎖からの再開 | 一時の閉鎖と確定の閉鎖の区別（§9.3）。試験（`IntegrationHubDeliveryTransientClosureMySqlTest`） |
| P3-N1 書込み待ちが再起動で消える | WARN `FEATURE_AUTO_CLOSE_PENDING` と RB の注意（§7.4） |
| P3-N2 `continueRecovery` の順 | 先に runtime switch（§9.3） |
| P3-N3 `READ_FAILED` の時の purge | `true`（§9.1 の 5a） |
| P3-N4 PITR の手順 | checklist・閉鎖の一覧・log の保存期間（§13.1） |
| P3-N5 `dba-prelaunch-draft.md` の path | `.kiro/specs/production-launch/dba-prelaunch-draft.md` に直した |
| P3-N6 member と署名の種別・tenant | 複合 FK（§2.6・§2.6.1） |
| P3-N7 複合 FK の書く順 | 申請を先に `APPROVED`（§5.3） |
| P3-N8 監査の照会の索引 | `reference_type='NF05'` を付ける（§9.1 の 3） |
| P3-N9 用語 | 「独立 review で同意」「go-live の後」、§8.5 の番号の順 |
| P3-N10 残る穴の運用の規則 | RB §5.6 の規則と backlog（§11.1） |
