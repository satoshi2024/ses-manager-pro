# SES Manager Pro 改善ロードマップ 第2弾（工程レビュー起点）

2026-07 実施のコードレビューで検出したバグ修正・整合性強化・機能追加の全体計画である。
第1弾（`ROADMAP.md`, P1〜P8）と同じスペック駆動ワークフローに従う:
各ワークストリーム(WS)の詳細仕様は `.kiro/specs/<スペック名>/`(requirements.md / design.md / tasks.md)に置き、
実装時は各スペックの `tasks.md` を上から順に消化し、完了したタスクは `- [x]` にチェックする。

## 全体像

| WS | スペック | 内容 | 種別 | 新規マイグレーション | フェーズ |
|---|---|---|---|---|---|
| A | [billing-integrity](../.kiro/specs/billing-integrity/requirements.md) | 月次解除の支払保護、請求済み実績の保護、請求書取消(void)、採番・未請求クエリの論理削除対応、請求書/BP支払ステータス状態機械、実績入力の検証・並行安全性 | バグ修正 | なし | 1 |
| B | [referential-integrity](../.kiro/specs/referential-integrity/requirements.md) | 要員/顧客/案件/契約の削除ガード(サーバー側参照整合性)、契約番号採番の衝突修正 | バグ修正 | なし | 1 |
| C | [frontend-common-hardening](../.kiro/specs/frontend-common-hardening/requirements.md) | common.js セッション切れ誤判定修正、Toast の XSS 対策、オートコンプリートのユーザー名列挙制限 | バグ修正 | なし | 1 |
| D | [dashboard-and-contract-list](../.kiro/specs/dashboard-and-contract-list/requirements.md) | KPIトレンドの実データ化、退場予定リストの実データ化、N+1/O(n²)解消、契約一覧の検索条件と名称表示 | 改善 | なし | 1 |
| E | [self-service-password](../.kiro/specs/self-service-password/requirements.md) | 全ロール向け自身のパスワード変更(プロフィール) | 機能追加 | なし | 1 |
| F | [invoice-compliance](../.kiro/specs/invoice-compliance/requirements.md) | 支払期限(due_date)・期限超過通知・適格請求書(インボイス制度)対応 | 機能追加 | **V13** | 2(A完了後) |
| G | [proposal-to-contract](../.kiro/specs/proposal-to-contract/requirements.md) | 成約→契約ドラフト自動生成のサーバー側移管 | 機能追加 | **V14** | 2(B完了後) |

## フェーズと並行実行の境界

フェーズ1の A〜E は**編集対象ファイルが互いに素**になるよう設計してあり、並行実施できる。

```
フェーズ1(並行可):  A  B  C  D  E
                     │  │
フェーズ2:           F  G          ← F は A の後、G は B の後
```

各WSの編集対象(他WSはこれらのファイルに触れないこと):

- **WS-A**: `InvoiceService(Impl)` / `InvoiceApiController` / `InvoiceMapper` / `WorkRecordService(Impl)` / `WorkRecordApiController` / `dto/invoice/*`(追加) / `dto/workrecord/*`(追加) / `invoice.js` / `work-record.js` / 対応テスト
- **WS-B**: `EngineerServiceImpl` / `CustomerServiceImpl` / `ProjectServiceImpl` / `ContractServiceImpl` / `ContractMapper` / 対応テスト(**controllerは編集しない** — ガードは service の `removeById` オーバーライドで実装)
- **WS-C**: `common.js` / `AutocompleteApiController` / 対応テスト
- **WS-D**: `DashboardServiceImpl` / `ContractApiController` / `ContractMapper`※ / `dto/contract/*`(追加) / `dto/dashboard/*` / `contract.js` / `contract/list.html` / `dashboard.js` / 対応テスト
- **WS-E**: `ProfileApiController`(新規) / `layout/header.html` / `layout/base.html` / `static/js/profile.js`(新規) / 対応テスト

※ `ContractMapper` は WS-B(採番用 `@Select` 追加)と WS-D(一覧用 `@Select` 追加)の両方が触る唯一のファイル。
どちらも**メソッド追加のみ**なのでコンフリクトしても解決は自明だが、マージ順は B → D を推奨。

## 進捗

- **フェーズ1(A〜E)**: 実装・マージ済み。検証で判明したテスト不備(HTTP規約・テストプロファイル・
  監査テーブル欠落)を修正し全テストグリーン化済み。
- **フェーズ2(F, G)**: 実装完了・全325テストグリーン。
  - WS-F: V13(due_date + 設定シード)、支払期限自動設定、期限超過通知、一覧の期限列、適格請求書出力。
  - WS-G: **V14 は不要だった**(`t_contract.proposal_id` は V1 に既存)。ドラフト生成のサービス移管のみ実施。

## マイグレーション採番

| 番号 | ファイル | WS | 状態 |
|---|---|---|---|
| V13 | `V13__invoice_due_date.sql` | F | 作成済み |
| ~~V14~~ | ~~`V14__contract_proposal_link.sql`~~ | G | **不要**(proposal_id は V1 に既存) |

新マイグレーションは `src/test/resources/application-test.yml` の `spring.sql.init.schema-locations` にも**必ず追記**する
(テストは Flyway ではなくこのリストで H2 にスキーマ投入している)。H2 の MySQL モードで通る構文にすること。

## 共通の実装原則(第1弾から継承)

- 既存の層構造・命名に従う: `*PageController`(ビュー名のみ)/ `*ApiController`(`ApiResult<T>` 返却)/ `IService`+`ServiceImpl` / `BaseMapper`。XMLマッパーは作らず `LambdaQueryWrapper` か注釈 `@Select`。
- 期待されるエラーは `BusinessException`(日本語メッセージ)で表現し、`GlobalExceptionHandler` に処理させる。
- 画面は既存パターン(Bootstrapモーダル + SweetAlert2 確認 + `Toast` 通知)を踏襲。ユーザー入力由来の文字列は `SES.escapeHtml` を必ず通す。
- UI・コメント・ログ・コミットメッセージは日本語。
- テストは H2(`application-test.yml`)で `mvn test` が MySQL なしでグリーンになること。各タスクの「テスト要件」を満たすテストを必ず追加する。
- 各タスク完了時に該当 `tasks.md` のチェックボックスを `- [x]` に更新する。
