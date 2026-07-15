# Design — 業務ロジック整合性強化（business-logic-integrity-hardening）

この spec は lane 単位で並行実装する。各 lane は担当 file だけを編集する。
担当外の file 修正が必要になった場合は、主 agent に戻して調整する。
作業開始時点の user 変更は上書き・巻き戻ししない。

## Lane A — Flyway と DB 一意性

担当 file:

- `src/main/resources/application.yml`
- `src/main/resources/application-prod.yml`（必要な場合のみ）
- `src/main/resources/db/migration/V17__fix_bp_payment_unique_key.sql`
- 新規 migration（必要な場合は現在の最大 version の次を使う）
- `src/test/java/com/ses/migration/FlywayMigrationSmokeTest.java`
- 新規制約に直接関係する test schema

### A1. Flyway 復旧

`enabled: true`、共通 location、baseline-on-migrate、baseline-version=9 を戻す。
test profile は H2 初期化を使うため `enabled=false` を維持する。
prod は prod location 追加に留め、enabled の上書きはしない。

### A2. V10/V17 重複排除

V10 はすでに `idx_bp_payment_work_record` 作成と `uk_work_record_layer` 削除を行っている。
V17 は同じ処理を繰り返さず、version file として残したうえで副作用のない migration にする。

### A3. 最終一意性

論理削除済み record は同一関係の再作成を許可する必要がある。
`(work_record_id, layer_order, deleted_flag)` の unique では、複数の削除済み履歴同士が衝突するため不可。
MySQL generated column で有効 record のみ key を生成し、削除済み record は NULL にして UNIQUE を張る。

`proposal_id` と `renewed_from_contract_id` も有効契約だけを制約する。
migration には事前重複確認 SQL を残す。自動削除は業務データを失うため行わない。

## Lane B — 金額単位と営業指標

担当 file:

- `src/main/java/com/ses/service/billing/SettlementCalculator.java`
- `src/test/java/com/ses/service/billing/SettlementCalculatorTest.java`
- `src/test/java/com/ses/service/impl/WorkRecordServiceImplTest.java`
- `src/main/resources/templates/proposal/kanban.html`
- `src/main/resources/static/js/modules/proposal-kanban.js`
- 必要な金額 label/placeholder message key
- `src/main/java/com/ses/service/impl/SalesPerformanceServiceImpl.java` または `static/js/sales-performance.js`
- 対応 test

### B1. 円単位

`SettlementCalculator.calc` の base は `unitPriceYen` をそのまま使う。
10000 倍は行わない。範囲内は base を返し、超過/不足時は既存の `base / hoursMax`、`base / hoursMin` formula と切り捨て rule を維持する。

提案カードは円値に「万」を直接付けない。
優先表示は契約一覧と同じ `¥800,000` とし、submit value は常に円にする。

### B2. 成約率

backend を 0～100 の百分率にそろえるか、frontend 側で 100 倍するかのどちらかに統一する。
改修範囲が小さい方を選び、1/2、1/1、0/0 の test を追加する。

## Lane C — 契約・提案・候補者の関係状態

担当 file:

- `src/main/java/com/ses/service/impl/ContractServiceImpl.java`
- `src/main/java/com/ses/service/impl/ProposalServiceImpl.java`
- `src/main/java/com/ses/service/impl/CandidateServiceImpl.java`
- 必要な service/mapper 注入
- `src/main/resources/templates/proposal/kanban.html`（Lane B と衝突する場合は Lane B または主 agent が編集）
- `src/main/resources/static/js/modules/proposal-kanban.js`（同上）
- 対応 service/controller test

### C1. 契約関連 validation

1. `projectId` から Project を取得し、存在しなければ `BusinessException`。
2. `project.customerId` と `contract.customerId` が違う場合は拒否する。
3. `salesUserId` が非 null の場合は SysUser を取得し、role=`営業`、status=1、未削除を確認する。

### C2. 契約更新後の要員状態再計算

更新前に old.engineerId/old.status を保存し、更新後に new.engineerId/new.status を使う。

- old engineer と new engineer が違う場合、DB 更新後に old へ `releaseIfIdle` を呼ぶ。
- new status が `稼動中` の場合、new へ `onContractActive` を呼ぶ。
- old status が `稼動中` かつ new status が非 `稼動中` で要員が同じ場合、その要員へ `releaseIfIdle` を呼ぶ。

副作用は同一 transaction 内で、契約 row 更新後に実行する。
これにより `releaseIfIdle` は更新後の関係を見て判定できる。

### C3. 提案初期状態

service は新規提案を `書類選考中` に限定する。
request が非 blank かつ初期状態以外を指定した場合は `BusinessException` を返す。
通常 frontend は status を送らず、初期状態は server 側で決定する。

### C4. 候補者 stage allowlist

`CandidateServiceImpl` に immutable な stage set を定義する。
save では null/blank を `応募受付` に default し、未知値は拒否する。
changeStage は history/reason 処理の前に stage を検証する。

## Lane D — BP 支払権限と状態不変条件

担当 file:

- `src/main/java/com/ses/controller/api/BpPaymentApiController.java`
- `src/main/java/com/ses/controller/api/InvoiceApiController.java`
- `src/main/java/com/ses/service/BpPaymentService.java`
- `src/main/java/com/ses/service/impl/BpPaymentServiceImpl.java`
- 必要な場合 `SecurityConfig.java`
- BP/権限関連 test

### D1. 権限境界

汎用 `/api/bp-payments/{id}` write route は、既存権限 prefix 配下の `/api/invoices/bp-payments/{id}/layer` に寄せる。
互換 route を残す場合でも、未分類 write API として権限 filter を通過させない。

### D2. 状態と削除

状態更新 rule は service へ集約する。
階層編集では amount/remarks など構造 field だけ許可し、支払済 record の金額変更は拒否する。
削除前に status を確認し、`支払済` は拒否する。
paidDate の clear は `UpdateWrapper` を使い、global not-null update strategy による残存を避ける。

## Lane E — i18n と統合検証

担当 file:

- `src/main/resources/messages*.properties`
- `src/test/java/com/ses/i18n/MessageBundleConsistencyTest.java`
- `.kiro/specs/business-logic-integrity-hardening/tasks.md`

追加した business error / notification key は ja/zh/en/ko にそろえる。
完了 checkbox は test と demo 条件を実際に満たしたものだけ更新する。

統合検証:

1. 対象 unit/service/security/message test を実行する。
2. Docker が使える環境では `FlywayMigrationSmokeTest` を実行する。
3. Docker がない場合は skip として記録し、MySQL 8 実行待ち task は未完了のままにする。
4. `git diff --check` と `git status --short` を確認する。
5. task 開始前から存在した user 変更は巻き戻さない。
