# 実装・Review収束ハンドブック v2.0

## 1. 目的・適用範囲・優先順位

本書は `customer-product-expansion-2026` の全17spec、T001〜T115、主実装対話、子Agent、独立Review、修正、
再Review、merge、次spec解放へ共通適用する。目的はReview件数だけを減らすことではなく、重大欠陥を最初の実装と
最初の独立Reviewで発見し、同一論点を再発・再起票せず、有限回で判定を収束させることである。

正本versionは **v2.0 / 2026-07-28**。旧copyable conversationも `shared-standards.md` を読むことで本書を
必須基線として継承する。矛盾時の優先順位は次のとおり。

1. 発注者の最新明示決定、`decision-log.md`、`gate-decisions-g1-g6.md`
2. specの `requirements.md`（顧客効果・acceptance）
3. specの `design.md`（技術契約）
4. specの `tasks.md`（実行順・完了条件）
5. 本書、`shared-standards.md`、dependency/parallel plan
6. 開工・Review対話の定型文

下位文書だけで上位要件を狭めてはならない。矛盾は表にして停止し、推測で選択しない。

## 2. S02から得た強制原則

根拠は `s02-review-retrospective.md` を正とする。

1. **現在値を過去日の代用にしない。** `asOf`、対象月、締め、異動、単価、所属、親子、状態を扱う時点で、
   current/history/snapshot/明示NULLを分離する。
2. **NULLと履歴不存在を区別する。** 履歴行ありNULLと履歴行なしを`COALESCE`だけで混同しない。
3. **認可母集団を再発明しない。** list/detail/count/options/autocomplete/export/download/PDF/notification/
   scheduler/async/cache/dashboardを同じscope契約へ対応付ける。
4. **更新とcache失効を同一transaction意味論で扱う。** commit前、rollback、複数transactionのcallback順序、
   request外thread、principal、共有cache keyを検証する。
5. **Migrationは空DBと旧DBの両方で実行する。** fresh、legacy、partial shape、repair、backfill、Linux改行を確認する。
6. **期間操作は区間代数として設計する。** 同日、未来、有限終了、部分/完全重複、隣接、空区間を列挙する。
7. **全緑を受入証明とみなさない。** mock回数や静的文字列でなく、実SQL・実レスポンス・失敗条件をassertする。
8. **Review対象を固定する。** Base/Head、task、変更file、既知issue、未検証環境を固定できなければ開始しない。
9. **再Reviewは差分Review。** OPEN issue、修正diff、直接影響、回帰だけを見る。
10. **P2/NOTEを無期限blockerにしない。** 明示acceptance違反でなければbacklogへ移す。

## 3. 状態・判定権限

```text
NOT READY → READY → IN PROGRESS → REVIEW ─┬→ PASS
                                           └→ FIX → REVIEW
外部環境待ち: REVIEW → CONDITIONAL PASS
発注者延期: NOT READY/IN PROGRESS → DEFERRED
```

- 実装AIは自己成果をPASSにしない。`REVIEW待ち`まで記録できる。
- 独立Review AIだけが対象commitへPASS / CONDITIONAL PASS / FAILを出す。
- 次spec解放はmerge後Headの独立Review結果を中央ledgerへ反映した後だけ行う。
- 未commit working tree Reviewは中間確認に限り、最終PASSには使わない。

CONDITIONAL PASSはP0=0/P1=0で、残件が環境・契約・実機だけに限定され、各残件にowner、期限、手順、
合格条件、rollback、次spec/block本番の区分がある場合だけ使用する。必須実MySQLやbrowser Demoを静的testで代替しない。

## 4. 開工前Readiness Gate

production fileを変える前に次を出力する。

| 項目 | 必須証拠 | STOP条件 |
|---|---|---|
| decision | G番号、決定文書、未決事項 | blocking decision未決 |
| dependency | 先行specのmerge commitとReview | 未merge/P1残存 |
| migration | 実在latest、予約、永久欠番 | 衝突/過去migration変更が必要 |
| baseline | branch、base commit、dirty file | user変更と分離不能 |
| scope | task、requirements/AC、対象外 | ACを追跡不能 |
| environment | Java/Maven/Node/Docker/MySQL/browser/provider | 必須環境なし、代替条件なし |
| ownership | 主担当/子Agentの許可・禁止file | 共有fileのowner重複 |
| risk | data/security/time/money/external | rollback不能/高risk未設計 |

```text
READINESS
- spec/task:
- handbook version: v2.0
- requirements/acceptance:
- base commit / working tree:
- dependency merge/review evidence:
- migration latest/reserved/gaps:
- mandatory environments:
- file ownership:
- assumptions:
- blockers:
- decision: GO / STOP
```

STOPなら調査と必要回答だけを出し、code/SQL/checkboxを変更しない。

## 5. Task開始前契約

各taskの実装前に次の1枚を作る。重要項目がspecに無ければ、推測実装せずspecを具体化する。

```text
TASK CONTRACT
- task ID / objective:
- requirements ID / acceptance ID:
- 顧客が観測する効果:
- 変更予定file / 変更禁止file:
- database/API/UI/event/cache/file契約:
- 主体別の許可/拒否表:
- timezone/asOf/対象月/締め/履歴:
- NULL/未設定/不存在/fallback:
- concurrency/idempotency/transaction:
- backfill/reconciliation/rollback:
- test matrix:
- Demo手順:
- 完了条件:
```

## 6. 横断設計チェックリスト

### 6.1 時間・履歴・snapshot

| 概念 | 必須決定 |
|---|---|
| business date | LocalDate/Instant、timezone、inclusive/exclusive |
| current | 使用可能な処理 |
| history | 有効区間、重複、過去訂正、監査 |
| snapshot | 作成契機、immutable範囲、再実行、訂正 |
| explicit NULL | 業務値か、fallback可否 |
| missing history | legacy fallback/UNKNOWN/拒否 |
| late processing | 過去月を後日処理する基準時点 |

必須test: 前日/当日/翌日、月初/月末、同日変更、未来開始、有限終了、履歴なし、履歴ありNULL、再実行。

### 6.2 認証・認可・scope

- 管理者、マネージャー、営業、HR、要員、portal、scheduler/service principalを列挙する。
- DataScope、organization、tenant、file、menu/action permissionの和/積/bypassをロール別に固定する。
- 許可集合が空ならSQLで0件。画面後・取得後filterは禁止。
- ID詳細、更新、削除、downloadもquery boundaryで同じ規則を使う。
- `list/page/detail/count/options/autocomplete/summary/dashboard/export/CSV/Excel/PDF/ZIP/download/preview/
  notification/scheduler/async/cache/webhook/batch/bulk/reopen/retry` を検索しconsumer inventoryを作る。

### 6.3 Transaction・競合・cache

- version CAS、状態CAS、DB一意制約のどれで二重実行を防ぐかを書く。
- cache失効は原則afterCommit。rollbackで新状態へ進めない。
- 2 transactionのcommit/rollback順を入れ替えるtestを持つ。
- request情報をscheduler/asyncへ暗黙持込しない。ThreadLocalはfinally解除とthread再利用testを必須とする。
- idempotency keyのscope、保持、同key異payload、処理中/失敗後再送を定義する。
- optional dependency欠落時のfail-open/fail-closedを明記する。

### 6.4 金額・CSV・帳票

- 円、scale、rounding、税、負数、取消、上限を固定する。
- CSVはencoding/BOM/header/column/quote/comma/CR/LF/式注入をfixtureで往復検証する。
- 正常な負数を文字列化しない。式と数値はparse結果で区別する。
- PDF/Excel/downloadは画面/APIと同じscopeを使う。

### 6.5 Migration・既存データ

DDL taskは次の5形状を検証する。

1. 空DB: V1 baseline→latest。
2. legacy: 公開済みlatest実形状→new。
3. partial: index/constraint/columnが存在/不存在/旧定義。
4. backfill: NULL、孤児、重複、無効user、履歴なし、一部移行済み。
5. repair: 途中失敗後の復旧とchecksum。

禁止: 公開migration変更、CRLF固定hash、ADD前の列参照、MySQL固有DDLをH2だけで合格、fresh成功だけでlegacy合格。

## 7. Test Matrix・証拠・Demo

各acceptance IDに最低1行を作る。

| AC | 正常 | 拒否/異常 | 境界 | 競合/再送 | 実DB/UI | 証拠 |
|---|---|---|---|---|---|---|

testはmock呼出しだけでなく、顧客が観測する出力/永続状態をassertする。Mapper SQLはH2実行、MySQL smoke、
実レスポンス再読を優先する。Mockitoのみなら未検証として記録する。

```text
TEST EVIDENCE
- command / profile / environment:
- exact result: tests / failures / errors / skipped / exit code
- skipped names and reasons:
- artifact/log:
- executed by: implementer / reviewer
- date / commit:
```

Demoはrole、data、操作、期待、実測、desktop/390px、keyboard、二重click、reload、戻る、拒否表示を記録する。
未実施をMockMvcで代替済みと言い換えない。

## 8. Task Definition of Done

次を全て満たすtaskだけ`- [x]`にする。

- requirements/AC traceが完成。
- TASK CONTRACTとdiffが一致し、範囲外変更を説明。
- 正常・異常・権限・境界・競合・rollback testが成功。
- migration/API/UI/notification/export等のconsumer inventoryが完成。
- 必須Demo済み、または正当なCONDITIONAL gateとしてowner/期限付き管理。
- skipを列挙しblocker判定済み。
- `git diff --check`、対象test、必要な全量回帰が成功。
- review-ledgerにcommit、証拠、未検証、rollbackを記録。

code作成、compile、主要test成功だけでは完了にしない。

## 9. Review開始契約

実装AIは次を提出する。不足時はReview AIが推測せず`NOT REVIEWABLE`とする。

```text
REVIEW PACKET
- handbook version:
- spec/tasks:
- base commit / head commit / merge status:
- changed files grouped by task:
- requirements/acceptance trace:
- migration latest/reserved/applied:
- test evidence / Demo evidence:
- skipped/unverified:
- known issue IDs:
- out-of-scope changes:
- rollback:
- requested verdict: intermediate / final
```

Base/Headはcommit hashで固定する。working tree Reviewはfile listとdiff hashを記録し、最終PASSにしない。

## 10. 独立Review方法

1. readiness、task順、decision、migrationを確認。
2. requirements→design→tasks→diff→testをtrace。
3. 変更された契約の全consumerを`rg`でinventory。
4. scope、time/history、transaction、migration/data、money/export、UIを横断確認。
5. test本文とassertを読み、必要なら独立最小再現を実行。
6. Issue Registerへ重複なしで登録し判定。

全指摘の必須形式:

```text
- issue ID: <spec>-R<round>-P<severity>-<number>
- severity: P0 / P1 / P2 / NOTE
- violated requirement/acceptance:
- file:line:
- reproduction: data / role / time:
- expected / actual:
- customer/security/operation impact:
- evidence:
- minimum acceptable fix:
- direct regression scope:
- discovered in: original head / fix delta
```

要件ID、再現可能性、影響のいずれも無い指摘はP0/P1にしない。

- **P0**: data破壊、scope/PII漏洩、認証回避、migration不能、本番停止、法定必須欠落。
- **P1**: 明示AC不達、主要結果誤り、重要な権限/期間/金額/競合欠陥。
- **P2**: 限定品質・UX・test不足。回避可能で主要ACを満たす。
- **NOTE**: 改善案・将来検討・好み。PASS非block。

P2→P1昇格は新しい再現証拠と顧客影響を必須とする。

## 11. Issue Registerと再Review収束

```text
OPEN → FIXED_BY_IMPLEMENTER → VERIFIED_CLOSED
  └→ REJECTED（根拠付き）
  └→ DEFERRED（P2/NOTEのみ、owner/期限付き）
```

- 同じ根本原因を別番号で再起票しない。affected consumer一覧を1 issueへ付ける。
- 実装AIはFIXEDまで。Review AIだけがVERIFIED_CLOSEDにする。
- closed再開には同じHeadまたはfix deltaでの新しい再現証拠が必要。
- 再Review対象はOPEN issue、修正diff、direct regression、変更public contractのconsumer、新規導入P0/P1だけ。
- 全repo再監査を毎回行わない。別spec既存問題はbacklogへ分離する。
- 旧Headから見落としていた重大問題は起票可能だが`discovered in: original head`としprocess漏れを記録する。

Round運用:

- Round 1: 全面Review。
- Round 2: fix delta + direct regression。
- Round 3: OPEN P0/P1のみ。新規P0/P1は原因分類必須。
- Round 4以降: 通常Reviewを停止し、spec/temporal model/scope inventory/Migration fixture/test matrixを先に改訂する。

## 12. 最終判定・次spec解放

最終PASS条件:

- merge済みHeadの独立Review。
- P0=0/P1=0。
- AC traceに未管理UNVERIFIEDなし。
- 必須test failure=0。skipは名称、理由、release gateあり。
- DDL taskはfresh/legacy実MySQL証拠あり。
- browser必須taskはdesktop/390px証拠あり。
- OPEN P0/P1なし。
- central ledger、tasks、review-ledgerが一致。

出力は次のいずれかだけとする。

```text
PASS: P0=0 / P1=0 / P2=n / open release gates=0
CONDITIONAL PASS: P0=0 / P1=0 / P2=n / release gates=<IDs>
FAIL: open blockers=<issue IDs>
NOT REVIEWABLE: missing=<packet fields>
```

## 13. 既存対話の互換運用

既に開始済みの実装対話へ一度だけ送る:

```text
実行基線をexecution-review-handbook.md v2.0へ更新する。受入済み成果を再実装せず、OPEN issueと直接回帰だけを扱う。
最初にREADINESS、TASK CONTRACT、Issue Register、Base/Headを提示する。旧基線との矛盾は差分表にして停止する。
```

既に開始済みのReview対話へ一度だけ送る:

```text
Review基線をexecution-review-handbook.md v2.0へ更新する。再ReviewはOPEN issue、fix diff、direct regressionだけを扱う。
closed issue再開と新規P0/P1には新しい再現証拠を必須とし、4種類の明示判定で終了する。
```

未開始のcopyable conversationは継続利用可能。全promptが読む`shared-standards.md`から本書を必須継承する。
今後新規生成するpromptは本書を直接「最初に完全に読むもの」へ追加する。

## 14. 禁止運用

- commit未固定のworking tree全体を反復Reviewする。
- 実装者説明だけでcloseする。
- 旧Reviewの見落としをfix起因と偽る。
- P2/NOTEで次specを永久停止する。
- 未実施testを環境依存という理由だけでPASSにする。
- test件数増加や全量greenをscope/history/legacy/browserの代替にする。
- Reviewごとに理想設計を後付けしrequirementsを拡張する。

## 15. 必須成果物

- `requirements.md`: acceptance ID付き顧客要件。
- `design.md`: time/scope/transaction/migration/external契約。
- `tasks.md`: task順、test、Demo、DoD。
- `review-ledger.md`: Review Packet、Issue Register、証拠、判定。
- 中央ledger: 現在状態、Base/Head、次actionだけ。

review-ledgerはappend-onlyとし、先頭に現行判定、OPEN issue、最新Review Packetを置く。古い自己申告PASSを現行判定にしない。
