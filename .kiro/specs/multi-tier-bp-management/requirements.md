# Requirements Document — 多段階BP(協力会社)構造管理

## Introduction

現状の `t_bp_payment` は `work_record_id` に対して1:1（UNIQUE制約）で紐づく単一の支払レコードであり、「元請 → 一次請 → 二次請 → 技術者」のような多段階の商流・転貸構造を表現できない。実際のSES業界では、同一エンジニアの稼働に対して複数階層の協力会社へマージンを積んで支払う構造が一般的であり、各階層の原価・マージンを追跡できないと粗利分析（`DashboardServiceImpl`の利益計算）が不正確になる。

本specは `t_bp_payment` を拡張し、1件の稼働実績（`t_work_record`）に対して複数階層の支払チェーンを記録・可視化できるようにする。

### 確定済みの設計判断
- 既存の `t_work_record` / `SettlementCalculator` の精算計算ロジックには一切手を入れない（売上/原価の月次精算は現行仕様のまま）。本specは「原価をどの協力会社にどう配分して支払うか」という**支払側の内訳管理**にのみ関与する。
- `t_bp_payment` の `work_record_id UNIQUE` 制約は撤廃し、1つの`work_record`に対して複数の`bp_payment`行（階層ごとに1行）を許可する。
- 既存の `BpPaymentApiController` / `bp-payment.js` の一覧・支払確認フローとの後方互換を維持する（既存の単層データはそのまま`layer_order=1`として扱う）。

## Requirements

### Requirement 1: 支払チェーンの多階層表現

#### Acceptance Criteria
1. THE システム SHALL 1つの `t_work_record` に対して複数の `t_bp_payment` 行（階層ごと）を保持できる。
2. THE `t_bp_payment` SHALL 新規カラム `layer_order`（階層番号、1=技術者に最も近い一次請）、`payee_company_name`（支払先協力会社名）、`parent_payment_id`（自己参照FK、上位階層への参照。1階層目はNULL）を持つ。
3. WHEN 新規の階層を追加する場合、THE システム SHALL `layer_order` が同一work_record内で重複しないことを検証する（違反時 `BusinessException`）。
4. THE システム SHALL 各階層の `amount` の合計が、当該 `work_record.paymentAmount`（原価ベース精算額）を超えないことを警告表示する（ハード制約ではなく画面上の警告に留める。商流上、階層合計が原価と一致しない運用も許容するため）。
5. WHEN 既存データ（本spec適用前に作成された `t_bp_payment` 行）を参照する場合、THE システム SHALL `layer_order=1`・`parent_payment_id=NULL` として扱う（マイグレーションでデフォルト値を設定）。

### Requirement 2: マージン（階層別利益）の可視化

#### Acceptance Criteria
1. THE システム SHALL 各階層の `amount` と、直下階層（`parent_payment_id`が自分を指す行）の `amount` 合計との差分を「当該階層のマージン」として画面表示する。
2. THE 商流図（一覧画面） SHALL work_record単位で階層をツリー/インデント表示する。
3. WHERE 階層が1つのみ（従来通りの単純構造）の場合、THE 画面 SHALL 従来と同じフラットな一覧表示を維持する（多階層UIは複数行がある場合のみ表示）。

### Requirement 3: 支払ステータス管理の階層対応

#### Acceptance Criteria
1. THE 各階層 SHALL 個別に `status`（未払/支払済）と `paidDate` を持つ（既存フィールドを流用、階層追加による変更なし）。
2. THE システム SHALL 上位階層（`layer_order`が大きい側、＝元請に近い側）が「支払済」であっても、下位階層が「未払」のままであることを許容する（実務上、階層間で支払タイミングがずれるため、ステータスの自動連動は行わない）。

## 注意点（実装時の注意）
- `work_record_id UNIQUE` 制約をDBレベルで撤廃する際、既存の一意インデックスを`DROP INDEX`してから複合インデックス（`work_record_id, layer_order`のUNIQUE）に置き換える必要がある。MySQL 8では`ALTER TABLE ... DROP INDEX`と`ADD UNIQUE`を同一ステートメントにまとめないと、一瞬でも制約が外れた隙に矛盾データが入り得る点に注意。
- `parent_payment_id`の自己参照FKは、同一`work_record_id`内でのみ閉じていることをアプリ層でも検証すること（DBのFK制約だけでは異なるwork_record間の誤参照を防げない）。
- CLAUDE.mdの規約により、新規カラムは**V1のCREATE TABLEに直接追記**し、追加のALTER migrationを重複させないこと。既存の`db/migration-prod`のBCryptマイグレーション同様、V1は「統合済みベーススキーマ」なので、H2テスト用の`sql/`配下の対応スキーマファイルとENGINEER-SCHEMA等も同時更新が必要（`engineer-schema-h2.sql`など）。
