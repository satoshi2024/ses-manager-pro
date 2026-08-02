# Design — 承認ワークフロー・内部統制

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（予約V75）

- `m_approval_route(id, tenant_id, request_type, organization_id, min/max_amount, version_no,
  valid_from/to, active_flag)`。
- `m_approval_route_step(route_id, step_no, parallel_group, approver_type, approver_value, sla_hours)`。
- `t_approval_request(id, request_no, request_type, target_type/id, target_version, applicant_id,
  organization_id, amount_snapshot, payload_json, diff_json, route_snapshot_json, status, current_step,
  requested_at, finalized_at, idempotency_key, version)`。
- `t_approval_action(request_id, step_no, approver_user_id, action, comment, delegated_from, acted_at)`。
- `t_approval_delegation(from_user_id, to_user_id, valid_from/to, request_types_json, approved_by)`。

payload/diffはPII最小化し、対象全entityをserializeしない。adapterが許可fieldだけをsnapshotする。

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
| 代理設定 | `t_approval_delegation`の有効期間内 | `valid_from/to` | actionへ`delegated_from`を記録 | **承認操作の実行時点** | `request_types_json IS NULL`＝全種別対象 |
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

### 6.4 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback |
|---|---|---|---|---|
| draft | →requested / →withdrawn | 状態CAS | 二重申請click | 破棄 |
| requested | →in_review（step進行） | 状態CAS＋`current_step` | 二重承認 | — |
| in_review | →approved / →rejected / →returned / →withdrawn | **状態CAS＋`version`＋`current_step`の複合条件** | 並列group内の同時承認、代理と本人の同時承認 | returnedへ |
| returned | →requested（再申請） | 状態CAS | — | — |
| approved | 終端。`applyApproved`を**1回だけ**実行 | `UNIQUE(approval_request_id)`を対象側に持たせる | retry / 二重click | **業務rollbackは対象serviceの取消操作で表現** |
| rejected / withdrawn | 終端 | — | — | 新規申請を作る |

- **最終承認transactionの順序**（design §3を再掲・固定）:
  `request行をロック` → `target versionを再検証` → `adapter.applyApproved` → `request=approved` → `outbox insert`。
  外部API・メール送信はoutboxでcommit後に実行する（§3.3、R2.3）。
- **target version競合の扱い**（R2.1）: 最終承認時に対象entityの`version`が申請時`target_version`と
  異なる場合、承認を**失敗させ`returned`にはせず`conflict`として申請者へ差し戻す**。
  古いsnapshotを適用しない。自動再解決しない。
- **並列group**: 全員承認で次stepへ、1人却下で即終端（R1相当）。
  並列group内は`UNIQUE(request_id, step_no, approver_user_id)`で二重actionを防ぐ。
- **代理と本人の重複**: 同一stepに本人と代理者の両方が解決された場合、
  **先着1件を有効**とし2件目はCAS失敗で拒否する。承認者数を二重にカウントしない。
- **職務分離**（R1.4）: 申請者が承認者として解決された場合、そのstepは**次の候補者へ委譲**する。
  候補が居なければ**申請受付を拒否**する（承認者0で自動approvedにしない）。
- 直接endpointに`*.approve.bypass`権限を作らない（design §4）。migration以外はengine経由のみ。

## 7. テスト

route resolution、金額境界、自己承認、代理期間、並列、競合、二重承認、apply rollback、outbox、
対象5adapterの単件service回帰。

## 8. F1実装注記（逸脱と根拠）

T042(F1)実装時に確定した、§1/§6の記述だけでは一意に決まらない実装詳細。既定解・決定表と矛盾する
ものは「逸脱と根拠」形式で明記する。矛盾しないものは補足として残す。

### 逸脱: §6.4の二重action防止キー

- 既定解: `UNIQUE(request_id, step_no, approver_user_id)`
- 本specの解: `UNIQUE(request_id, step_no, approver_slot_user_id)`。
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

### 補足: approver_typeの実装範囲

R1.3は承認者解決元として「特定user、permission group、申請者の上長、組織責任者、財務責任者」を挙げる。
F1はG7推奨既定（組織上長→財務/管理者）を満たすために必要な3種——`USER`（固定user）、
`ROLE`（`sys_user.role`一致の全員）、`APPLICANT_MANAGER`（申請時点の`t_user_organization.manager_user_id`）
——のみ実装する。未対応の`approver_type`値は「承認者解決不能」としてfail-closedに扱う
（推測実装せず拒否する）。`permission group`/`組織責任者`個別/`財務責任者`個別の追加解決方式が
必要になった場合はA2（route/代理管理）で追加する。

### 補足: `resolveApprovers`の実現方法

design §3が挙げる`resolveApprovers`はengineの独立public methodとしてではなく、
`RouteResolverService.resolve(...)`が返す`ResolvedRoute`の各stepの承認者一覧として実現した
（route解決と承認者解決は同一トランザクション・同一呼び出しで行う必要があり、分離するとF1の
「route未設定/承認者解決不能は同じ理由で申請を拒否する」という単純さが崩れるため）。

### 補足: `target_version`とtarget entityの`@Version`

`t_approval_request.target_version`は本specが新設する対象5テーブル(`t_quotation`/`t_contract`/
`t_invoice`/`t_bp_payment`)への`@Version`列追加を伴わない。F1のengineはこの値を保存・通過させるだけで、
「最終承認時に対象entityの現在versionと比較する」実処理（design §3/§6.4の「target version再検証」）は
F2（5対象adapter）が対象ごとに定義する。対象4entityへの`@Version`追加が必要かどうかもF2の判断に委ねる
（T041 inventory §3の申し送り事項を参照）。

### 補足: `escalate`とB1の関係

design §3が挙げる`escalate`はF1では未実装。SLA期限監視・冪等scheduler・通知重複防止はB1
（通知/SLA/escalation）の担当であり、F1のtest matrixにも含まれない。`m_approval_route_step.sla_hours`
列はDDLとして用意済みで、B1がそのまま参照できる。
