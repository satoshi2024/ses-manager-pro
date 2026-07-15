# Implementation Plan — 業務ロジック整合性強化

Lane A/B/C/D は並行実装可能。Lane E と最終統合は各 lane 完了後に行う。

- [ ] A1. Flyway を復旧し、V17 の重複 migration を無害化する
  - **Objective**: R1。dev/prod の自動 migration を復旧する。
  - **実装ガイダンス**: design.md A1/A2。test profile の Flyway=false は維持する。
  - **テスト要件**: 設定確認、MySQL 8 Flyway smoke test、V10/V17 が同一 index 変更を二重実行しないこと。
  - **Demo**: 空の MySQL DB で migrate が全 version 成功し、`flyway_schema_history` に failed row がない。
  - **現在の状態**: 実装は反映済み。本機は Docker 不可のため `FlywayMigrationSmokeTest` は skipped。MySQL 8 検証待ちとして未完了。

- [ ] A2. 三種類の冪等関係に DB 最終制約を追加する
  - **Objective**: R6。BP 階層、提案契約、更新契約 draft の並行重複を防ぐ。
  - **実装ガイダンス**: design.md A3。重複データを先に確認し、有効 record だけを対象に unique 制約を張る。
  - **テスト要件**: 同一 business key の二重 INSERT が失敗すること、論理削除後は再作成できること、複数の削除済み履歴が共存できること。
  - **Demo**: 同一階層/契約 draft を同時投入しても、有効 record は 1 件だけ残る。
  - **現在の状態**: V18 は反映済み。論理削除後の再作成 semantics は保持。MySQL 8 実行と既存重複 precheck 待ち。

- [x] B1. SettlementCalculator と金額表示を円単位に統一する
  - **Objective**: R2。10000 倍の金額ずれをなくす。
  - **実装ガイダンス**: design.md B1。精算 formula は維持し、単位変換だけを除去する。
  - **テスト要件**: 800000 が範囲内で 800000 のまま返ること、超過/不足 formula、提案→契約→実績→請求の金額 chain。
  - **Demo**: 契約単価 800000、150h を確定すると請求金額が ¥800,000 と表示される。

- [x] B2. 営業成約率の百分率表示を修正する
  - **Objective**: R7。
  - **実装ガイダンス**: design.md B2。
  - **テスト要件**: 1/2=50%、0/0=null、1/1=100%。
  - **Demo**: 営業成績ページで 1 件成約 / 2 件提案が 50% と表示される。

- [x] C1. 契約関連 validation と要員状態再計算を追加する
  - **Objective**: R3。
  - **実装ガイダンス**: design.md C1/C2。
  - **テスト要件**: project/customer 不一致拒否、非営業担当拒否、稼動契約の要員変更・稼動終了・同時変更時の状態更新。
  - **Demo**: 稼動契約を A から B に変更すると、A は他業務がなければ Bench、B は稼動中になる。

- [x] C2. 提案新規作成で状態機械を迂回できないようにする
  - **Objective**: R4。
  - **実装ガイダンス**: design.md C3。UI 新規作成は初期状態のみ。
  - **テスト要件**: POST で成約/見送りを指定すると拒否されること、通常作成は書類選考中になること、合法な状態遷移では history/closedAt/契約 draft が作られること。
  - **Demo**: 新規提案 modal では終端状態を選べず、成約は Kanban の合法遷移でのみ発生する。

- [x] C3. 候補者 stage の固定集合 validation を追加する
  - **Objective**: R8。
  - **実装ガイダンス**: design.md C4。
  - **テスト要件**: 新規 default が応募受付になること、未知 stage は save/changeStage の両方で拒否され history が増えないこと、理由必須 rule が維持されること。
  - **Demo**: API で stage=任意文字列を送ると business error になり、一覧の状態は変わらない。

- [x] D1. BP 支払 route の権限境界を整理する
  - **Objective**: R5.1/R5.2。
  - **実装ガイダンス**: design.md D1。
  - **テスト要件**: invoice/work-record 権限のない role は PUT/DELETE が 403、有権限 role は成功。
  - **Demo**: HR account は BP 支払 write API を呼べず、admin は呼べる。

- [x] D2. BP 支払の状態、金額編集、削除 rule を集約する
  - **Objective**: R5.3～R5.6。
  - **実装ガイダンス**: design.md D2。
  - **テスト要件**: paidDate の設定/clear、支払済 record の金額編集拒否、支払済削除拒否、不正 status 拒否、存在しない ID の拒否。
  - **Demo**: 支払済 record の直接削除は失敗し、未払へ戻すと paidDate が clear され削除できる。

- [x] E1. 四言語の通知 resource を補完する
  - **Objective**: R9.1/R9.2。
  - **実装ガイダンス**: 不足していた notification key を補い、placeholder 番号をそろえる。
  - **テスト要件**: `MessageBundleConsistencyTest` と通知 template 解析 test が通る。
  - **Demo**: 英語/韓国語環境の通知 center で key ではなく翻訳 text が表示される。

- [ ] E2. 統合回帰と文書整理
  - **Objective**: R1～R9 の全体验収。
  - **実装ガイダンス**: design.md の統合検証。完了済み task だけ `[x]` にし、未検証 demo は完了扱いにしない。
  - **テスト要件**: 対象 business test、message consistency、`git diff --check`。Docker 使用可能時は MySQL smoke test。
  - **Demo**: 各 task の demo を確認し、自動検証できない環境制約を記録する。
  - **現在の状態**: 10 test class、合計 89 test は通過済み。`git diff --check` も通過済み。MySQL Flyway smoke は Docker 不可により skipped のため未完了。
