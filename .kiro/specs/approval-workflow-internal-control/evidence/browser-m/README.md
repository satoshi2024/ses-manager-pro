# S07 M — desktop/390px 5業務 browser Demo 証拠（2026-08-05）

## 環境

- アプリ: `http://localhost:8080`（dev profile、Spring Boot 3.3.5、MySQL 8.0コンテナ `ses_app_mysql`、Flyway適用済み、`ORGANIZATION_SCOPE_ENABLED=false`）
- ブラウザ: 実Chrome `150.0.7871.187`（Playwright-core `1.54.0`、headless実ブラウザ）
- viewport: desktop `1440x900`、390px `390x844`
- ログイン: 申請者 `sales1`(営業)/`mgr1`(マネージャー)、承認者 `admin`(管理者)。dev profileのNoOpPasswordEncoderでplaintext認証。

## 検証対象と結果（10経路全てPASS）

| 業務 | request type | desktop | 390px | 対象（desktop / 390px） |
|---|---|---|---|---|
| 見積提出 | `quotation.submit` | PASS | PASS | Q-202608-0001 / Q-202608-0002 下書き→提出済 |
| 契約稼動化 | `contract.activate` | PASS | PASS | C-2026-0001 / C-2026-0002 準備中→稼動中 |
| 請求送付 | `invoice.send` | PASS | PASS | INV-202607-0001 / INV-202607-0002 未送付→送付済 |
| BP支払確定 | `bp_payment.confirm` | PASS | PASS | bp_payment（payeeCompanyName=株式会社BPデモ, layerOrder=1/2）未払→支払済 |
| 月次締め | `closing.confirm` | PASS | PASS | 2026-05 / 2026-04 open→closed |

各経路で確認した4項目（JSONの該当step）:

1. **申請者単独確定不可**（`applicant_alone_cannot_finalize.targetStateUnchanged=true`）: 申請操作後も対象状態が変化しない（例: 見積 下書き→下書き）。
2. **申請→承認→適用**（`business_operation_applied_once.targetStateChanged=true`）: 承認後に既存service単件methodが1回だけ呼ばれ、対象状態が変化する（下書き→提出済等）。
3. **申請時二重click/retryでも申請1件**（`approval_request_created_once.requestCount=1`）: 同一ボタンの再click/retryは`ApprovalTargetAdapterRegistry`のSHA-256 idempotency key（`uk_approval_request_idempotency`一意制約）で同一申請へdedupeされる。
4. **承認時二重click/retryでも業務操作1回**（`approval_final_state.approveActionCount=1`、`retry_approve_no_double_op.stateStable=true`）: 二重clickの2回目は`insertActionIdempotent`でno-op、完了後のretryは`error.approval.invalidState`(400)で安全に拒否され、業務操作は再適用されない。

最終DB状態（docker exec確認）: 見積2件=提出済、契約2件=稼動中、請求2件=送付済、BP支払2件=支払済、締め済み月=2026-05/2026-04、`t_approval_request` 10件全てapproved、APPROVE action合計10件（二重actionなし）。

## ファイル

- `summary.json` — 10経路の全step（before/after状態、申請数、action数、retry安定性）
- `<flow>-<viewport>.json` — 経路別JSON
- `<flow>-<viewport>/*.png` — スクリーンショット（1-business-page / 2-after-apply-retry / 3-request-detail-before-approve / 4-request-detail-after-approve）

## 再現

- seed: `seed-s07-browser-demo.sql` をアプリ起動（Flyway migration完了）後に適用する。適用例:
  `docker exec -i ses-app-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 ses_manager_db < seed-s07-browser-demo.sql`
  - 本seedは**S07固有business keyのみを子→親順で削除**する（見積番号`Q-202608-%`・契約番号`C-2026-000%`・請求番号`INV-202607-%`・BP支払key・S07 request_type集合のroute/request/action/participant・S07 Demo月のclosing記録）。**全route/request/action/participant削除は行わない**。
  - `sys_user`の`sales1`/`mgr1`は削除せずUPSERT（参照行のFKを壊さない）。
  - **AUTO_INCREMENT固定IDに依存しない**。業務オブジェクトのIDは毎回変わるため、`demo2.js`がbusiness key（見積番号/契約番号/請求番号/BP支払のpayee+layerOrder）からAPIでIDを解決する。
- 実行: `node demo2.js`（Playwright-core + 実Chrome、dialog accept付き、SweetAlert2/confirm/prompt対応）。
  - `demo2.js`は各経路でassertion（申請1件・申請者単独不変・適用1回・APPROVE action 1件・retry安定）を実行し、1経路でも失敗した場合は`process.exit(1)`で終了する。
  - 本evidenceはdynamic-ID解決版`demo2.js`で再生成済み（`resolved_business_keys` stepに各経路の解決IDとbusiness keyを記録）。
- 記録済みevidenceのfail-closed検証（再実行なし）: `node verify-evidence.js` が以下を検証し、1項目でも違反すれば`process.exit(1)`。
  - 期待する**5 flow × 2 viewport = 10件**の完全一致 / `summary.json`件数10 / 重複禁止
  - 各経路assertion（requestCount=1・applicantAloneUnchanged=true・appliedOnce=true・approveActionCount=1・retryStable=true）と`requestStatus=approved`
  - 経路別JSON 10件 / PNG 40枚
