# Implementation Plan — 要員担当営業・営業成績/インセンティブ管理

タスクは **F(基盤) → A/B/C/D(並行可) → M(統合)** の構成。
A〜D は互いにファイル交差がなく並行実施できる。共有資産(i18n・マイグレーション・H2 スキーマ・
エンティティ・Mapper・Service インターフェース)は F で凍結済みとし、A〜D は**参照のみ**。
不足(キー欠落・Mapper メソッド不足など)を見つけた場合は自分で共有ファイルを直さず、
タスク末尾の「F 層ギャップメモ」に記録して M で一括対応する。

- [x] F1. spec ドキュメント
  - **Objective**: 本ディレクトリの requirements / design / tasks 3ファイル。
  - **Demo**: レビュー可能な状態でコミットされている。

- [x] F2. 基盤層(V14・エンティティ・Mapper・サービス・API・i18n・H2)
  - **Objective**: A〜D が消費する全共有資産を確定させる。
  - **実装ガイダンス**: design.md 1章の V14 全 DDL、`EngineerSales` エンティティ、`Contract` 3フィールド、
    `EngineerSalesMapper`(@Select 4本)、`EngineerSalesService`+impl(業務ルール)、
    `EngineerSalesApiController`(6エンドポイント)、`StatusConstants` 定数、
    `engineer-schema-h2.sql` 同期、i18n 4ファイルへ全キー先行投入。
  - **テスト要件**: `EngineerSalesServiceImplTest`(初回主担当/降格/重複拒否/役割拒否/解除ガード/released_at)。
  - **Demo**: `mvn test` グリーン。管理者で 6 エンドポイントを curl 確認。

- [ ] A. 要員担当 UI 【並行可・担当ファイル: engineer/*.html, engineer*.js, EngineerApiController, dto/engineer/】
  - **Objective**: 要員詳細の「担当営業」カードと要員一覧の列/絞り込み。
  - **実装ガイダンス**: design.md 4章。詳細ページにカード+`#salesRepModal`+履歴トグル、
    解除は SweetAlert2 確認。一覧は `EngineerListDto` + `mapPrimaryByEngineerIds` 一括取得、
    `salesUserId` 絞り込みは既存 skillIds の `inSql` 方式。
  - **テスト要件**: 既存 Engineer 系テストがグリーンのまま。
  - **Demo**: ブラウザで割当→主担当変更→解除→履歴表示、一覧の営業絞り込み。

- [ ] B. 契約帰属 【並行可・担当ファイル: Contract*(Impl/Renewal/Mapper/ListDto/ApiController), contract/list.html, contract.js, ContractServiceImplTest】
  - **Objective**: 成約→契約ドラフトへの主担当自動設定と契約画面の担当営業/個別インセンティブ設定。
  - **実装ガイダンス**: design.md 2.2章。`createDraftFromProposal` で
    `findPrimarySalesUserId` を既定値に。`ContractRenewalServiceImpl` は新3カラム引き継ぎ。
    `selectPageWithNames` に sys_user join + salesUserId 絞り込み。モーダルに担当営業 select
    (要員選択時に主担当プリセット)+ 折りたたみ個別設定。
  - **テスト要件**: `ContractServiceImplTest` に @Mock EngineerSalesService、
    主担当設定/主担当なし NULL の2ケース追加。
  - **Demo**: カンバンで成約→ドラフトに担当営業が入る。契約編集で上書き設定を保存できる。

- [ ] C. 営業成績サービス+画面 【並行可・担当ファイル: SalesPerformance*, dto/salesperformance/, templates/sales-performance/, sales-performance.js, sidebar.html】
  - **Objective**: `/api/sales-performance` 集計と営業成績画面。
  - **実装ガイダンス**: design.md 3章の口径どおり。ページ+JS+sidebar メニュー項目。
    既定規則の表示は `/api/sales-performance/commission-rule`。
  - **テスト要件**: `SalesPerformanceServiceImplTest`(既定規則/上書き率・基準/売上基準/
    work_record 優先/月窓/分母0/切捨て/マイナス0)。
  - **Demo**: 営業で画面表示可、HR はメニュー非表示+直接 URL 403。数値がシードデータと一致。

- [ ] D. 待機一覧強化 【並行可・担当ファイル: AnalyticsServiceImpl, BenchEngineerDto, analytics/index.html, analytics.js, AnalyticsServiceImplTest】
  - **Objective**: 待機一覧に主担当営業列とクライアントサイド絞り込み。
  - **実装ガイダンス**: design.md 4章。`benchList()` に `selectActivePrimaryByEngineerIds` 一括取得、
    未設定は「—」。API 署名は変えない。
  - **テスト要件**: `AnalyticsServiceImplTest` のコンストラクタ更新+主担当設定ケース。
  - **Demo**: Bench 要員に主担当が表示され、絞り込みが機能する。

- [ ] M. 統合・検証
  - **Objective**: A〜D を統合し全体を検証、ギャップメモを解消する。
  - **実装ガイダンス**: 4ラインのマージ → `mvn test` 全量 → F 層ギャップメモ対応 → 本ファイルのチェック更新。
  - **Demo**: E2E — 営業ユーザー作成 → 要員に主担当割当 → 提案 → 成約 → ドラフトに営業が帰属 →
    稼動+work_record 確定 → `/sales-performance` の件数/売上/粗利/インセンティブ確認 →
    `/system-config` で既定率変更+契約個別上書きの両方が反映 → HR で 403 確認。

## F 層ギャップメモ(A〜D 記入欄)

- (なし)
