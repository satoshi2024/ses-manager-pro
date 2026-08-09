# Design — 承認ワークフロー・内部統制

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（S07正式migration V75/V76/V77/V78/V79）

S07の採番正本は、既存のV75/V76/V77/V78/V79と、R1.2/R1.3の不足を補うpatch migration **V79.1**である。V75〜V79は変更せず、V79.1はV79適用後かつV80より前に適用する。S09はV80を使用し、S09 R10 remediationは既適用V80を変更せずV81へ順方向追加する。S10=V84、S11=V83、S12〜S17=V85〜V90を使用する。V82はV83実在後の欠番として補填しない。

- `m_approval_route(id, tenant_id, request_type, applicant_role_condition, organization_id, min/max_amount, version_no,
  valid_from/to, active_flag)`。`applicant_role_condition IS NULL`は全role対象、値ありは申請者role一致。
- `m_approval_route_step(route_id, step_no, parallel_group, approver_type, approver_value, sla_hours)`。approver_typeはUSER/ROLE/APPLICANT_MANAGER/PERMISSION_GROUP/ORGANIZATION_MANAGER/FINANCE_MANAGER。
- `t_approval_responsibility(id, tenant_id, responsibility_type, organization_id, user_id, valid_from/to, active_flag)`。
  ORGANIZATION_MANAGERは組織単位、FINANCE_MANAGERは組織単位またはorganization_id=NULLの全社assignmentとしてas-of解決する。
- `t_approval_request(id, request_no, request_type, target_type/id, target_version, applicant_id,
  organization_id, amount_snapshot, payload_json, diff_json, route_snapshot_json, status, current_step,
  round_no, requested_at, finalized_at, idempotency_key, version)`。
- `t_approval_action(request_id, round_no, step_no, slot_index, approver_user_id, approver_slot_user_id,
  action, comment, delegated_from, acted_at)`。
- `t_approval_delegation(from_user_id, to_user_id, valid_from/to, request_types_json, approved_by)`。
- `t_approval_delegation_type(delegation_id, request_type)`（V78で新設する代理対象種別の正規化表）。

payload/diffはPII最小化し、対象全entityをserializeしない。adapterが許可fieldだけをsnapshotする。

### 1.1 V78（S07追加 migration。採番確定）

V75は既存の承認DDL 5テーブル、V76は既存の承認menu seed、V77は既存の
`current_step_started_at`追加であり、いずれも変更しない。S07が追加で使用するmigrationは
**V78の1本**と、B1で追加する**V79の1本**とする。V78は承認workflowのround/participant/version、
V79は通知Webhook outboxだけを担当する。S09はV80＋V81修復、S10=V84、S11=V83、S12〜S17=V85〜V90へ確定している（既存の欠番も
埋めない）。

V78は次の変更を同一migrationで行う。V75の`t_approval_action`には`round_no`が存在しないため、
UNIQUEキーの張替えに必要なaction側の`round_no`も追加する。`t_contract`はV1に`version`列が
存在しないことを確認済みのため、MyBatis-Plusの`@Version`が期待する`version`を追加し、
`approval_version`という別名は採用しない。

```sql
-- (A) 再申請ラウンド
ALTER TABLE t_approval_request
  ADD COLUMN round_no INT NOT NULL DEFAULT 1 AFTER current_step;
ALTER TABLE t_approval_action
  ADD COLUMN round_no INT NOT NULL DEFAULT 1 AFTER request_id;

-- (B) 同一slotの二重承認防止。前ラウンドのactionは残す
ALTER TABLE t_approval_action
  ADD COLUMN slot_index INT NOT NULL DEFAULT 0 AFTER step_no;
ALTER TABLE t_approval_action DROP KEY uk_approval_action_slot;
-- slot_indexは記録用の通常列。1人1slotを構造的に保証するためUNIQUEには含めない
ALTER TABLE t_approval_action ADD UNIQUE KEY uk_approval_action_slot
  (request_id, round_no, step_no, approver_slot_user_id);

-- (C) 承認一覧のSQL境界
CREATE TABLE t_approval_participant (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_id       BIGINT NOT NULL,
  user_id          BIGINT NOT NULL,
  participant_role VARCHAR(20) NOT NULL COMMENT 'applicant/approver',
  round_no         INT NOT NULL DEFAULT 1 COMMENT 'resubmitで更新されるラウンド番号',
  UNIQUE KEY uk_participant (request_id, round_no, user_id, participant_role),
  INDEX idx_participant_user (user_id, participant_role),
  CONSTRAINT fk_participant_request FOREIGN KEY (request_id)
    REFERENCES t_approval_request(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='承認参加者';

-- (C-2) 代理対象種別の正規化。子行が0件なら全種別対象
CREATE TABLE t_approval_delegation_type (
  delegation_id BIGINT NOT NULL,
  request_type  VARCHAR(50) NOT NULL,
  PRIMARY KEY (delegation_id, request_type),
  CONSTRAINT fk_delegation_type FOREIGN KEY (delegation_id)
    REFERENCES t_approval_delegation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='代理対象種別';

-- (D) 対象業務entityの楽観ロック
ALTER TABLE t_quotation
  ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '楽観ロックバージョン';
ALTER TABLE t_contract
  ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '楽観ロックバージョン';
ALTER TABLE t_invoice
  ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '楽観ロックバージョン';
ALTER TABLE t_bp_payment
  ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '楽観ロックバージョン';
```

**V78既存データ移行境界（A′）**:
V78の最初の実行処理は、既存申請のslot意味論を検査するstored procedureの`CALL`とする。
既存の`ALTER TABLE`/`CREATE TABLE`およびparticipant backfillを先に実行してはならない。
MySQLではV71と同じstored procedure方式を使用し、`SIGNAL SQLSTATE '45000'`で停止する。

検査対象の`route_snapshot_json`がNULL・空文字・JSON解析不能、または`steps`配列を持たない場合は、
条件に該当しないとみなさず、fail-closedで停止する。JSONのstep展開はMySQLのfilter式を使わず、
`JSON_TABLE(route_snapshot_json, '$.steps[*]' COLUMNS (...))`で展開してから`stepNo`を比較する。

participantの可視性backfillは、全非削除申請を対象に冪等実行する。

- `applicant_id`を`participant_role='applicant'`として登録する。
- `route_snapshot_json`の`$.steps[*].approverUserIds[*]`を展開し、
  `participant_role='approver'`として登録する。
- `round_no`は`COALESCE(round_no, 1)`を使用する。
- backfillは`INSERT IGNORE`を使用し、`uk_participant`による既存行との重複を
  migration失敗にしない。
- 終端済み申請も対象とし、完了一覧の可視性を復元する。

ただし、次の3条件をすべて満たす既存申請が1件でもある場合は、V78を停止する。

1. `route_snapshot_json`に`slots`形式が存在しない。
2. statusが`draft`、`requested`、`in_review`、`returned`または`conflict`である。
3. `current_step`以上のstepのうち少なくとも1つで、`approverUserIds`が2名以上である。

この条件は、旧snapshotのparallel groupが新実装で1slotへ縮退し、1名承認で成立する
内部統制の弱体化を防ぐためのfail-closed境界である。旧snapshotのslot境界を現在のroute定義から
再構成してはならない。検査で使用する対象申請ID・request_no・status・current_stepを停止メッセージに
含め、運用者が次のSQLで該当行を特定できるようにする。該当申請は取下げまたは旧workflowで完了させ、
Flyway repair後にV78を再実行する。

dev/stagingに非終端・旧形式・残りstepに多名承認者を持つ申請が存在しないことを確認し、
確認結果をreview-ledger.mdへ記録する。この条件が将来満たされない場合は、旧申請を残したまま
アップグレードする別designを承認するまでV78を適用しない。

このDDLは設計上の確定値であり、実装時に`approval_version`へ読み替えたり、action側の
`round_no`追加を省略したりしない。H2 schemaも同じ列・キー形状へ同期する。
`t_approval_delegation_type`の既存行移行はV78に含める。`request_types_json IS NULL`の代理には子行を
作らず（子行0件＝全種別対象）、NULLでない配列は要素ごとに`(delegation_id, request_type)`をINSERTする。
V78適用後の代理種別判定は子表を正本とし、既存の`request_types_json`列は移行互換のため保持するが、
一覧可視性のSQLではJSON関数を使って参照しない。

### 1.2 V79（B1 通知Webhook outbox）

V78は適用済み可能性のあるmigrationとして編集せず、B1の外部通知配信だけを**V79__notification_webhook_outbox.sql**へ追加する。
`t_notification_outbox`は通知本体のID・宛先・本文・dedupe keyをsnapshotし、`PENDING`/`PROCESSING`/`RETRY`/`SENT`/`FAILED`の状態、試行回数、次回時刻、claim時刻、直近エラーを保持する。`dedupe_key`は一意制約で保護する。

通知本体とoutbox行は同一のDB transactionで保存する。transaction中には外部Webhookを呼ばず、commit後callbackから1件workerを起動する。schedulerはdue行を取得し、worker beanの`REQUIRES_NEW` transaction内でclaim→Webhook送信→`SENT`または指数backoffの`RETRY`/上限到達の`FAILED`更新を行う。claim競合は送信せず、30分以上の`PROCESSING`は再送可能へ戻す。Webhook URL未設定または対象外種別は配信対象外として`SENT`扱いにする。

H2ではFlywayを実行しないため、`sql/schema-approval-h2.sql`へ同じ列・一意制約・due indexを反映する。V79はV78のchecksumやDDLを変更せず、適用済みV78の有無にかかわらずforward-onlyで適用できる追加migrationとする。

### 1.3 V79.1（R4-P1-01 route decision sources）

V75〜V79は公開済みのため変更せず、`V79_1__approval_route_decision_sources.sql`を追加する。`m_approval_route.applicant_role_condition VARCHAR(30) NULL`を冪等に追加し、`t_approval_responsibility`を新設する。組織責任者・財務責任者は既存の一般上長列を流用せず、assignmentの有効期間と組織scopeを申請時点の`asOf`で評価する。

`PERMISSION_GROUP`は既存の`m_permission_group`と`t_user_permission_group`を正本とし、enabled・論理削除・有効ユーザーだけを候補にする。`ORGANIZATION_MANAGER`は対象organization必須、`FINANCE_MANAGER`は対象organizationまたは全社(`organization_id IS NULL`)を候補にする。いずれも申請者自身を除外し、候補0件はfail-closedで申請受付を拒否する。

routeの該当順は、申請者role条件ありを汎用routeより優先し、その後に組織の具体性、金額帯の狭さ、`version_no`の新しさで決める。role条件なしはfallbackとして残す。H2 consolidated schema、管理API/UI、resolver、設定・snapshot回帰を同じ契約へ同期する。

## 2. Adapter

`ApprovalTargetAdapter`:

```java
RequestType type();
ApprovalSnapshot snapshot(targetId, command);
void validateBeforeRequest(...);
void applyApproved(ApprovalRequest request);
```

見積、契約、請求、BP支払、月次締めの5adapter。`applyApproved`は既存service methodへ委譲し、
`approval_request.id`を冪等sourceとして渡す。

## 3. Engine

- `request`, `approve`, `reject`, `returnForRevision`, `withdraw`, `resolveApprovers`, `escalate`。
- 条件付きUPDATEでcurrent step/status/versionをCAS。
- 並列groupは全員承認で次へ、1人却下で終端。代理は元承認者をactionへ残す。
- 最終承認transaction: request lock→target version再検証→adapter apply→request approved→outbox。

## 4. 対象API変更

対象操作buttonは「実行」から「申請」へ変更。直接endpointはpermission `*.approve.bypass`を作らず、
system migration以外はengine経由のみ。既存API互換が必要なら同URLが申請を返す形へ変更し、レスポンスに
`approvalRequestId/pending`を返す。

## 5. UI

- `/approval/inbox`, `/approval/requests`, `/approval/routes`。
- 差分はfield label、before/after、金額単位、機密mask。
- 対象画面に申請状態badgeと履歴link。

## 6. 決定表

既定解は `customer-product-expansion-2026/platform-invariants.md`。ここには本spec固有の行と逸脱だけを書く。

本specは「状態機械 × 期間 × 金額 × 権限」の四重交差であり、S02と同じ事故構造を持つ。
下記の境界定義は**確定済み**である。実装中に読み替えたり、その場で決め直したりしない。

### 6.1 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| route定義 | `active_flag=1`かつ有効期間内 | `m_approval_route.version_no`＋`valid_from/to` | `t_approval_request.route_snapshot_json` | **申請時点**で解決しsnapshotへ固定 | `valid_to IS NULL`＝無期限有効 |
| 承認者 | route snapshotから解決 | `t_approval_action` | 同上 | **申請時点のsnapshot**。後のroute変更で変えない | — |
| 対象データ | 業務entityの現在値 | — | `payload_json`＋`diff_json`＋`target_version` | 申請時点 | — |
| 代理設定 | `t_approval_delegation`の有効期間内 | `valid_from/to` | actionへ`delegated_from`を記録 | **承認操作の実行時点** | `request_types_json IS NULL`（移行後は子表0行）＝全種別対象 |
| 金額 | 業務entityの現在値 | — | `amount_snapshot` | **申請時点**。route判定にもこれを使う | 金額なし申請（月次締め等）。**金額帯routeの対象外** |
| SLA期限 | `requested_at`＋`sla_hours` | — | step開始時に固定 | step開始時点 | `sla_hours IS NULL`＝**期限なし**。escalation対象外 |

- **route snapshotは申請時に確定し、以後不変**（R3.2）。進行中申請の承認者はroute改版で変わらない。
  これはS02の「月次帰属snapshot」と同じ構造であり、同じ理由（過去の判断根拠を後から変えない）。
- 代理は**承認操作の実行時点**で評価する。申請時点ではない。
  申請から承認までの間に代理期間が始まった/終わった両方のcaseをfixture化する。
- `amount_snapshot IS NULL` を 0円 として金額帯routeに当てない。§1.1に該当する。
  金額を持たない申請種別（月次締め/reopen）は金額帯を持たないrouteへ流す。

### 6.2 金額帯の境界（確定済み。実装中に変更しない）

| 論点 | 決定 |
|---|---|
| `min_amount` | **inclusive**（`amount >= min_amount`） |
| `max_amount` | **inclusive**（`amount <= max_amount`） |
| `min_amount IS NULL` | 下限なし |
| `max_amount IS NULL` | 上限なし |
| 判定に使う金額 | `amount_snapshot`（税込。税抜と混在させない） |
| 複数routeが該当 | `organization_id`の**より具体的な方**→金額帯の**狭い方**→`version_no`の新しい方 の順で1件に決める |
| 該当routeなし | **申請を受け付けない**。管理者へ設定不足を通知（R3.4）。既定routeへ暗黙fallbackしない |
| 負の金額（取消/訂正） | **絶対値**で金額帯を判定する。取消が常に最低帯へ落ちるのを防ぐ |

境界fixtureは`min-1 / min / min+1 / max-1 / max / max+1`の6点を各routeで持つ。

### 6.3 主体 × 操作 × 可見母集団

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| 管理者 | 全件。route管理可 | 全件 | 設定不足・解決不能 | escalation batch |
| マネージャー | 自分が申請者/承認者/代理者の申請＋組織scope ∩ DataScope | 同左 | 自分宛のみ | — |
| 営業 | 自分が申請者/承認者/代理者の申請のみ。**組織で追加制限しない** | 同左 | 自分宛のみ | — |
| HR | 同上（対象種別が該当する場合） | 同左 | 自分宛のみ | — |
| 要員 | 自分が申請者の申請のみ | 同左 | 自分宛のみ | — |
| portal user | 不可視 | — | — | — |
| scheduler principal | 全件 | — | 宛先は**対象本人のみ**（R4.2） | SLA超過検出、escalation |

- 申請の可視性は`applicant_id` OR 現在解決される承認者 OR `delegated_from`/`to`。
  **組織scopeを重ねない**（§2.4と同じ理由。異動した申請者が自分の申請を見失う）。
- `diff_json`の表示は**field単位のpermissionに従う**。原価・給与・口座を承認画面で素通しにしない。
  承認者が対象fieldを見る権限を持たない場合、その行を「変更あり（値非表示）」で示す。
- 通知は**対象本人だけ**へ送る。組織一斉通知にしない。
- 承認画面のbank/account fieldは`bp-company.bank-account.view`を要求し、
  `bp-company.*`の許可だけでは口座値を表示しない。role-salesおよびrole-managerには
  V78とH2のpermission seedで同actionの`deny_flag=1`を明示登録し、管理者だけが
  既存の管理者bypassで閲覧できることを固定する。
- この変更は承認画面のdiff/payload表示だけを対象とし、BP会社マスタ画面の既存
  `bp-company.*`認可は変更しない。BP会社マスタでは表示され、承認画面ではマスクされる
  差は意図した境界であり、BP会社マスタ自体の口座fieldを締める変更は本specの範囲外とする。
  必要になった場合は別specで扱う。

#### §6.3.1 承認一覧の SQL 境界化方針（確定済み）

`t_approval_participant(request_id BIGINT, user_id BIGINT, participant_role VARCHAR(20), round_no INT)`
をV78で新設し、申請作成と同一トランザクションで承認者・申請者をINSERTする。

| participant_role 値 | 登録タイミング |
|---|---|
| `applicant` | `request()`で申請行INSERTと同時。現在roundの申請者を登録 |
| `approver` | `request()`で全stepの全slotについて、snapshot解決済み候補を登録 |
| `approver` | `resubmit()`で`round_no`更新と同時に前round分を削除し、現roundの候補を再INSERT |

これにより`ApprovalViewServiceImpl.list()`は、申請者または承認参加者であることをSQL側で
絞り込む。現在roundだけを対象にするため、participantとのJOINには`p.round_no = r.round_no`を
含める。

```sql
SELECT r.*
FROM t_approval_request r
WHERE r.deleted_flag = 0
  AND (
    EXISTS (
      SELECT 1
      FROM t_approval_participant p
      WHERE p.request_id = r.id
        AND p.round_no = r.round_no
        AND p.user_id = :userId
    )
    OR EXISTS (
      SELECT 1
      FROM t_approval_participant p
        JOIN t_approval_delegation d
          ON d.from_user_id = p.user_id
      WHERE p.request_id = r.id
        AND p.round_no = r.round_no
        AND p.participant_role = 'approver'
        AND d.to_user_id = :userId
        AND d.deleted_flag = 0
        AND d.valid_from <= :today
        AND (d.valid_to IS NULL OR d.valid_to >= :today)
        AND (
          NOT EXISTS (
            SELECT 1
            FROM t_approval_delegation_type dt_any
            WHERE dt_any.delegation_id = d.id
          )
          OR EXISTS (
            SELECT 1
            FROM t_approval_delegation_type dt
            WHERE dt.delegation_id = d.id
              AND dt.request_type = r.request_type
          )
        )
    )
  )
  AND <view 条件 (status フィルタ等)>
ORDER BY r.requested_at DESC
```

代理者の分岐はparticipantのapprover行と有効期間をSQL側で判定し、代理対象種別の限定も
**ページング前のSQL側で適用する**。V78以降は`t_approval_delegation_type`を正本とし、子行が0件なら
全種別対象、子行が1件以上なら`r.request_type`と一致する子行がある場合だけ代理可視とする。
`request_types_json`はV78の既存行移行元および互換保持列であり、一覧可視性のSQLでは参照しない。
したがって判定は`NOT EXISTS`/`EXISTS`だけで完結し、MySQL/H2で同じSQLを実行できる。取得後の
Java filterで除外してページング結果を壊してはならない。`EXISTS`で可視性を判定するため重複行は
生成されず、`SELECT DISTINCT`は不要である。

P2-14の代理対象種別の正本境界は次のとおり固定する。
- 代理の書き込み（`ApprovalAdministrationServiceImpl.java:137`）では、代理親行のINSERT/DELETEと同一トランザクションで、`t_approval_delegation_type`の子行もINSERT/DELETEする。
- 承認時の代理対象種別判定（`ApprovalEngineServiceImpl.java:367 requestTypeAllowed`）は、`t_approval_delegation_type`の子行を読む。
- `request_types_json`はV78適用時の移行元および互換保持列としてのみ保持し、以後の読み取り判定の正本にしない。

`PageUtils.safePage(current, size, mapper::selectPage, wrapper)`でページングし、Java側の
全件取得・filter・`subList`による手動ページングは廃止する。participantとdelegation typeは
承認一覧の可視性をSQL境界へ移すための正規化表であり、申請者・承認者・代理者のいずれも同じSQL
境界で扱う。

### 6.4 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback |
|---|---|---|---|---|
| draft | →requested / →withdrawn | 状態CAS | 二重申請click | 破棄 |
| requested | →in_review（step進行） | 状態CAS＋`current_step` | 二重承認 | — |
| in_review | →approved / →rejected / →returned / →withdrawn / →conflict | **状態CAS＋`version`＋`current_step`の複合条件** | 並列group内の同時承認、代理と本人の同時承認 | returnedへ |
| returned | →requested（`round_no`を+1して再申請） | 状態CAS | — | — |
| approved | 終端。`applyApproved`を**1回だけ**実行 | request行の`SELECT ... FOR UPDATE`＋`status/version/current_step` CAS | retry / 二重click | **業務rollbackは対象serviceの取消操作で表現** |
| rejected / withdrawn | 終端 | idempotency keyをNULLへクリア | 同一操作の再送 | 新規申請を作る |
| conflict | →requested（`resubmit()`による再申請のみ） | target version不一致を通知 | 対象entityの更新割込み | 現在値から新しいsnapshotを作って再申請 |

- **最終承認transactionの順序**（design §3を再掲・固定）:
  `request行をロック` → `adapter.currentVersion()`でtarget versionを再検証 → `adapter.applyApproved` →
  `request=approved` → `outbox insert`。外部API・メール送信はoutboxでcommit後に実行する（§3.3、R2.3）。
  対象業務entity側に`UNIQUE(approval_request_id)`は追加しない。request行のロックと状態/version/current_step
  のCASを同一transactionで行い、approved済みのretryは`applyApproved`へ再到達させないことで二重適用を防ぐ。
- **target version競合の扱い**（R2.1）: 最終承認時に対象entityの現在`version`が申請時の
  `target_version`と異なる場合、承認を失敗させたり`returned`へ遷移させたりせず、`conflict`へ遷移して
  申請者へ通知する。古いsnapshotを適用せず、自動再解決もしない。
- **conflictからの再申請**: `resubmit()`の受付状態は`returned`または`conflict`とする。
  `conflict`からの再申請では、対象entityの現在値を読み直して`target_version`を取り直し、
  `payload_json`と`diff_json`も現在値から再生成してから`round_no`を+1する。古いsnapshotを
  再利用しない。`conflict`から許可する遷移はこの再申請だけとし、`approve()`や`withdraw()`は
  引き続き受け付けない。
- **並列group**: 同一`step_no`の各route step行をslotとして扱う。各slotは候補のうち1名のAPPROVEで充足し、
  **全slotが充足したときだけ**次stepへ進む。1件のREJECTで即終端とする。
  actionの二重記録は`UNIQUE(request_id, round_no, step_no, approver_slot_user_id)`で防ぐ。
- **代理と本人の重複**: 同一stepの同一slotに本人と代理者の両方が解決された場合、
  **先着1件を有効**とし2件目はCASまたはUNIQUE競合で冪等returnする。承認者数を二重にカウントしない。
- **職務分離**（R1.4）: 申請者が承認者として解決された場合、そのstepは**次の候補者へ委譲**する。
  候補が居なければ**申請受付を拒否**する（承認者0で自動approvedにしない）。
- 直接endpointに`*.approve.bypass`権限を作らない（design §4）。migration以外はengine経由のみ。

#### §6.4.1 step 成立条件（quorum）詳細

stepの承認者解決は**slot単位**で行う。1つの`m_approval_route_step`行が1 slotであり、
`RouteStepGroup`は`List<RouteSlot>`として保持する。

| approver_type | slot内 quorum | step成立条件 |
|---|---|---|
| USER | 指定user 1名（=解決後の候補が1名以下）で成立 | 全slotが充足（all-of over slots） |
| ROLE | 解決集合のうち**1名**で成立（any-of within slot） | 同上 |
| APPLICANT_MANAGER | 解決集合のうち**1名**で成立（any-of within slot） | 同上 |
| 並列group（同一`step_no`に複数行） | 各slotはany-ofで成立 | **全slotが充足**で次stepへ（any-of within slot × all-of over slots） |

実装上の型は次のとおりとし、`requiredCount`は将来のslot内複数承認拡張に備えるが、F1/F2/A1/A2の
確定値は1とする。

```java
record RouteSlot(
    int slotIndex,
    String approverType,
    List<Long> candidateUserIds,
    int requiredCount // = 1
) {}
```

**ROLEがany-ofである根拠**: G7推奨既定の「財務/管理者」を
`approver_type=ROLE, value=管理者`で表現する場合、全管理者ユーザーの捺印を要求すると社内稟議として
成立不能になる。`m_approval_route_step`を複数行書けばall-of並列を表現できるため、any-ofを1行に
留めることで両方のユーザビリティを確保する。

**同一人物が同一stepの複数slotの候補として解決された場合**:
その人物の1回の承認で充足できるのは**1slotのみ**（snapshot解決順で最初の未充足slot）とする。
残りのslotは別の人物による承認が必要であり、職務分離（R1.4）の観点から全slotを1名が単独充足することは
許容しない。実装では`t_approval_participant`の候補をslot単位で扱い、`slot_index`は監査・表示用の
通常列として保持する。`t_approval_action`のUNIQUEは
`UNIQUE(request_id, round_no, step_no, approver_slot_user_id)`とし、`slot_index`を含めない。
これにより同一人物（代理時は委任元の承認者）が同一request・round・stepの複数slotを埋めることを
DBレベルで拒否し、1人1slotを構造的に保証する。

#### §6.4.2 対象 version 口径（確定済み。実装中に変更しない）

対象4テーブルへ`version INT NOT NULL DEFAULT 0`（MyBatis-Plus `@Version`）を追加する（V78）。
`@Version`はMyBatis-Plusの`updateById`が自動インクリメントするため、秒精度・マイクロ秒精度の問題を
生じさせず、同一秒内の更新も確実に検知できる。

| 対象 | version列 | 申請時の取得 | 最終承認時の検証 | 競合時の遷移 |
|---|---|---|---|---|
| `t_quotation` | `version INT NOT NULL DEFAULT 0` | `Quotation.version`をそのまま`target_version`へ保存 | `target_version` ≠ 現在の`Quotation.version`なら競合 | `status=conflict`、申請者へ通知 |
| `t_contract` | `version INT NOT NULL DEFAULT 0` | `Contract.version`をそのまま`target_version`へ保存 | `target_version` ≠ 現在の`Contract.version`なら競合 | 同上 |
| `t_invoice` | `version INT NOT NULL DEFAULT 0` | `Invoice.version`をそのまま`target_version`へ保存 | `target_version` ≠ 現在の`Invoice.version`なら競合 | 同上 |
| `t_bp_payment` | `version INT NOT NULL DEFAULT 0` | `BpPayment.version`をそのまま`target_version`へ保存 | `target_version` ≠ 現在の`BpPayment.version`なら競合 | 同上 |
| `m_system_config`（月次締め） | `@Version`列は追加しない。`payload_json`へ申請時点の`closedMonths`配列を保存 | 締め済み月のリストを`payload_json`へ保存 | 申請時点の`closedMonths`と現在値が不一致なら競合 | 同上 |

**対象行ロックとlock order（P2-16）**:
`approve()`は常に`request row → target row`の順でロックする。最終承認時の
`currentVersion()`は対象業務行を`SELECT ... FOR UPDATE`で取得し、`applyApproved()`および
requestのapproved確定まで対象行ロックを保持する。`currentVersion()`と`applyApproved()`の間に
対象業務行の更新を許可しない。
月次締めは既存`MonthlyClosingServiceImpl.lockConfig()`が使用する`m_system_config`の同一行を
対象とし、承認engineも`request row → m_system_config row`の順序を守る。
`SystemConfigServiceImpl`のJVM内`ConcurrentHashMap` cacheは最終承認時のversion再検証で
経由しない。`t_bp_payment`についても対象行を`FOR UPDATE`で取得する。これは
`operation-inventory.md` §3が記録した「行レベルpessimistic lockがなく状態CASのみ」という
申し送りを閉じるものである。直接更新経路と承認経路は同じ対象行・同じlock orderを使用し、
request rowを先に取得する承認engineとの逆順ロックを作らない。

`@Version`の自動増分は`updateById(entity)`系に限られる。既存の対象4表には`UpdateWrapper`や生SQLで
更新する経路もあるため、次の7経路ではversion増分を明示的に同じUPDATEへ組み込む。ここにない
新しい更新経路を追加する場合も、`updateById`へ寄せるか、同じ表へ追記して増分方法を固定する。

| 既存更新経路 | 対象 | versionを増分する方法 |
|---|---|---|
| `ContractMapper.java:222` の `revisePrice` 生SQL（`selling_price`/`cost_price`更新） | `t_contract` | `SET selling_price = ..., cost_price = ..., version = version + 1` とする |
| `ContractServiceImpl.java:581` の `UpdateWrapper<Contract>` | `t_contract` | wrapperへ`.setSql("version = version + 1")`を追加する |
| `InvoiceServiceImpl.java:371` の `UpdateWrapper<Invoice>` | `t_invoice` | wrapperへ`.setSql("version = version + 1")`を追加する |
| `InvoiceServiceImpl.java:556` の `UpdateWrapper<BpPayment>` | `t_bp_payment` | wrapperへ`.setSql("version = version + 1")`を追加する |
| `InvoiceServiceImpl.java:565` の `UpdateWrapper<BpPayment>` | `t_bp_payment` | wrapperへ`.setSql("version = version + 1")`を追加する |
| `BpPaymentServiceImpl.java:191` の `UpdateWrapper<BpPayment>` | `t_bp_payment` | wrapperへ`.setSql("version = version + 1")`を追加する |
| `WorkRecordServiceImpl.java:502` の `UpdateWrapper<BpPayment>` | `t_bp_payment` | wrapperへ`.setSql("version = version + 1")`を追加する |

上表以外の4対象表の`updateById(entity)`経路はMyBatis-Plusの`@Version`自動増分を使用する。
`UpdateWrapper`経路でJava側の古いversion値を`.set("version", ...)`するだけでは競合検知を
すり抜けるため禁止し、必ずDB式`version = version + 1`を使う。生SQLにも同じ増分を含める。

V1を確認した結果、`t_contract`に既存の`version`列はないため、V78では`version`を新設する。
`approval_version`という別名は採用しない。`m_system_config`の月次締めadapterは、対象IDのversionではなく
snapshotした`closedMonths`を比較する。

`ApprovalTargetAdapter`のcontractは次のとおり変更する。default実装は設けず、全adapterに実装を要求する。

```java
long currentVersion(Long targetId);
```

未実装adapterはコンパイルエラーとなり、fail-openになる事故を構造的に防ぐ。

**`ApprovalEngineServiceImpl.approve()`の最終step処理（確定順序）**:
1. request行を`SELECT ... FOR UPDATE`でロック（既存）
2. `adapter.currentVersion(request.getTargetId())`で現在versionを取得
3. `request.getTargetVersion()`と現在versionを比較し、不一致なら`status=conflict`でCAS UPDATEして申請者へ通知しreturn
4. バリアを越えた後のみ`adapter.applyApproved(request)`を呼ぶ
5. `status=approved`でCAS UPDATEし、`idempotency_key = NULL`へクリアする

#### §6.4.3 idempotency 境界（確定済み。実装中に変更しない）

`t_approval_request.idempotency_key`にはV75の
`UNIQUE KEY uk_approval_request_idempotency (idempotency_key)`が存在する。終端行を単に検索対象外に
するだけでは、却下後の同一key再申請で`DuplicateKeyException`が発生するため、次の境界を採用する。

| シナリオ | 期待動作 | 採用する機構 |
|---|---|---|
| 同一ユーザーが同一操作を2回連続申請（二重click） | 進行中の申請をそのまま返す | `selectByIdempotencyKey`が非終端状態にのみマッチ（in_review / returned） |
| **却下/取下げ後に同一操作を再申請** | **新しい申請を作る** | **reject/withdraw到達時に`idempotency_key = NULL`へクリア**。MySQLのUNIQUEはNULLの重複を許容する |
| 最終承認後の再送 | 既存のapproved申請を再適用しない | `approve()`の`status=approved` CASと同時にkeyをNULLへクリア |
| 差戻し後の再申請（resubmit） | 既存申請の`round_no`を+1する | `t_approval_request.round_no INT NOT NULL DEFAULT 1`。actionのUNIQUEにもround_noを含める |
| 承認操作の二重click（同一round内） | 1件目のみ記録し、2件目は冪等return | `UNIQUE(request_id, round_no, step_no, approver_slot_user_id)`の競合を捕捉 |
| 前roundのaction残存 | 新roundのactionと衝突せず共存する | UNIQUE keyに`round_no`を含める |

`selectByIdempotencyKey`は次のSQL条件へ変更する。

```sql
SELECT * FROM t_approval_request
WHERE idempotency_key = ?
  AND deleted_flag = 0
  AND status NOT IN ('approved', 'rejected', 'withdrawn', 'conflict')
```

終端到達時のkeyクリアは、`reject()`と`withdraw()`の`casUpdate`、および`approve()`の
`status=approved` CAS UPDATEへ`idempotency_key = NULL`を含める。`conflict`遷移ではkeyをクリアしない。
conflictは再申請待ちであり、入口は`resubmit()`とする。ただしkeyを保持したままのため、別の新規申請と
同じkeyを使うとUNIQUE制約に衝突する。この制限は今回許容し、将来の複合UNIQUE（`idempotency_key`,
`status`）検討事項として§8に残す。

#### §6.4.4 締め済み月と承認申請受付の非対称性（確定済み）

`operation-inventory.md` §3が観測した「締め済み月への更新をどう扱うか」の非対称性について、
以下を承認engineの設計として確定する。実装中に変更しない。

| 対象 | 申請受付時の`assertOpenForUpdate` | 理由 |
|---|---|---|
| 3a 請求送付 / 3b 請求取消 | **呼ぶ（既存設計を維持）** | `InvoiceServiceImpl.changeStatus/voidInvoice`が既に`assertOpenForUpdate`を内包し、`applyApproved`がこれを呼ぶため最終承認時に自動的に検証される |
| 4 BP支払確定 | **呼ぶ（既存設計を維持）** | `InvoiceServiceImpl.changeBpPaymentStatus`が内包し、同上 |
| 1a 見積提出 / 1b 受注 | **呼ばない（非対称性を意図的に維持）** | 見積は締め済み月の概念を持たず、`t_quotation`に`work_month`がない。engineが横断的に呼ばない |
| 2a 契約稼動化 / 2b 単価改定 | **呼ばない（非対称性を意図的に維持）** | 契約の`start_date`/適用月は承認後にwork_recordを生成する時点で締め判定を行う。承認step自体を締め済み月でブロックしない |
| 5a 月次締め / 5b reopen | `applyApproved`内で`confirmClosing`/`reopenClosing`が自動実行 | これらは`lockConfig()`を内包するため、承認engineが追加チェックをしない |

**engineの役割**: 承認engineは申請受付時に`assertOpenForUpdate`を横断的に呼ばない。各`applyApproved`が
委譲先の既存service methodを1回だけ呼ぶため、締め済み月の検証は自然に委譲先serviceに委ねられる
（R2.2の設計原則と一致）。

## 7. テスト

route resolution、金額境界、自己承認、代理期間、並列、競合、二重承認、apply rollback、outbox、
対象5adapterの単件service回帰。

V78のlegacy migration回帰には次の両方を含める。

1. 終端済み申請だけが存在するfixtureではV78が成功し、applicantおよびapproverの
   participant backfill件数を検証する。
2. `slots`なし・非終端・`current_step`以上の残りstepのいずれかで
   `approverUserIds`が2名以上のfixtureでは、V78が実MySQL上で指定メッセージを返して
   停止することを検証する。

H2ではFlyway migrationを実行しないため、上記停止経路はDockerを利用した
`FlywayMigrationSmokeTest`でのみ検証する。

P2-11の回帰では、H2 seedと`ActionPermissionMatrixTest`で
`bp-company.bank-account.view`のdeny actionを名指しで検証し、
`ApprovalViewServiceImplTest`では営業/マネージャーで口座値がマスクされることと、
管理者で値が表示されることの両方をassertする。

## 8. F1実装注記（逸脱と根拠）

T042(F1)実装時に確定した、§1/§6の記述だけでは一意に決まらない実装詳細。既定解・決定表と矛盾する
ものは「逸脱と根拠」形式で明記する。矛盾しないものは補足として残す。

### 逸脱: §6.4の二重action防止キー

- 既定解: `UNIQUE(request_id, step_no, approver_user_id)`
- 本specの解: `UNIQUE(request_id, round_no, step_no, approver_slot_user_id)`。
  `approver_slot_user_id = COALESCE(delegated_from, approver_user_id)`（本人操作時は`approver_user_id`と同値）。
- 根拠（design §6.4「代理と本人の重複」との整合）: 本人と代理者は**別のuser id**で操作する。
  `approver_user_id`をそのままUNIQUEキーにすると、本人(A)と代理(B)がそれぞれ別行としてinsertでき、
  「先着1件を有効とし2件目はCAS失敗」を構造的に保証できない。「どちらが操作しても同じslot」を表す
  `approver_slot_user_id`（=解決されたstepの原承認者id）をキーにすることで、本人・代理のどちらが
  先着してもDB UNIQUE制約1つで二重カウントを防げる。
- 影響するconsumer: `t_approval_action`のみ。F2のadapter実装・A1のUI表示（`delegated_from`で代理表示）に影響しない。
- 追加テスト: `ApprovalEngineServiceTest.本人と代理の同時解決は先着1件だけが有効になる`。

### 補足: draft/requestedの扱い

対象5業務は既存entityの現在値からsnapshotを作るだけで、複数fieldを画面上で時間をかけて
下書き編集するUXを持たない（design §1のpayload/diffは対象adapterが機械的に組み立てる）。
そのため`request()`は`draft`→`requested`→`in_review`を1つのtransaction内で連続遷移させ、
外部から観測できる状態は最初から`in_review`になる。`draft`状態のAPIは現状提供しない。
§6.4の状態表自体は変更しない（`draft`はDBの`status`列が取り得る値として維持し、将来UIが
下書き編集を必要とする場合はそこへ差し込む）。

### 補足: approver_typeの実装範囲（R4-P1-01反映後）

R1.3の承認者解決元は、`USER`（特定user）、`PERMISSION_GROUP`（既存permission groupの有効メンバー）、
`APPLICANT_MANAGER`（申請時点の`t_user_organization.manager_user_id`）、`ORGANIZATION_MANAGER`
（期間付き組織責任者assignment）、`FINANCE_MANAGER`（期間付き組織単位/全社assignment）を実装する。
`ROLE`は既存互換として`sys_user.role`一致の候補集合を解決する。未対応または設定不備のtypeは空集合とし、
resolverがfail-closedで申請受付を拒否する。責任者assignmentの管理はA2 route管理API/UIから行う。

### 補足: `resolveApprovers`の実現方法

design §3が挙げる`resolveApprovers`はengineの独立public methodとしてではなく、
`RouteResolverService.resolve(...)`が返す`ResolvedRoute`の各stepの承認者一覧として実現した
（route解決と承認者解決は同一トランザクション・同一呼び出しで行う必要があり、分離するとF1の
「route未設定/承認者解決不能は同じ理由で申請を拒否する」という単純さが崩れるため）。

### 補足: `target_version`とtarget entityの`@Version`

Round 2の決定により、F2で対象entityのversionを推測実装することは許容しない。V78で
`t_quotation`/`t_contract`/`t_invoice`/`t_bp_payment`へ`version INT NOT NULL DEFAULT 0`を追加し、
各adapterが`currentVersion(Long targetId)`を実装する。申請時の対象versionを`t_approval_request.target_version`
へ保存し、最終承認時に§6.4.2の順序で比較する。`t_contract`はV1にversion列が無いため、V78で
`version`を新設する。`approval_version`への読み替えは禁止する。

月次締めは対象entityの`@Version`を追加せず、申請時の`closedMonths`配列を`payload_json`へ保存して
現在値と比較する。version不一致またはclosedMonths不一致は`conflict`へ遷移し、古いsnapshotを適用しない。

### 制限: `conflict`時のidempotency key

`conflict`遷移では`idempotency_key`をクリアしない。`resubmit()`を再申請の入口として残す一方、
同じkeyを使う別の新規申請は既存の単列UNIQUEと衝突する。この制限は今回の確定範囲として許容し、
将来`(idempotency_key, status)`の複合UNIQUEへ変更する場合に、申請APIとmigrationを一括で拡張する。

### 補足: `escalate`とB1の関係

design §3が挙げる`escalate`はF1では未実装。SLA期限監視・冪等scheduler・通知重複防止はB1
（通知/SLA/escalation）の担当であり、F1のtest matrixにも含まれない。`m_approval_route_step.sla_hours`
列はDDLとして用意済みで、B1がそのまま参照できる。

### R1.2の申請者role条件

`m_approval_route.applicant_role_condition`はNULLを全role対象、値ありを`sys_user.role`完全一致として扱う。
`RouteResolverServiceImpl.resolve()`は既存API互換のため`applicantId`から申請者roleを取得し、該当するrole条件routeを汎用routeより優先する。
同一role条件内では、組織の具体性→金額帯の狭さ→`version_no`の新しさで1件に決める。
申請者が存在しない、またはrole条件に一致しない場合は汎用routeだけをfallback候補とする。

### 補足: 承認一覧 SQL 化の変遷（R2 P1-08対応）

F1/A1 実装では `ApprovalViewServiceImpl` が `selectList()` 全件取得 → Java フィルタ →
`subList` 手動ページングを行っており、platform-invariants §2.2 に違反していた。
Round 2 指摘（P1-08）により V78 で `t_approval_participant` を追加し SQL 境界を確立した（§6.3.1）。
旧実装の全件取得・Java フィルタ・手動ページングは廃止。新実装は`t_approval_participant`による
可視性`EXISTS`判定と`PageUtils.safePage`を組み合わせる。500件制限（暫定）は採用しない。

### 補足: 独立Review証跡の格納先

独立Review AIが生成したtop-levelの一時成果物は正式な判定資料ではない。
観測事実と再現条件だけを`.kiro/specs/approval-workflow-internal-control/review-ledger.md`へ転記し、
判定は独立Reviewの結果として明記する。転記後はtop-levelの`semantic-review/`成果物を削除し、
review-ledger.mdを本specのReview記録の正本とする。
