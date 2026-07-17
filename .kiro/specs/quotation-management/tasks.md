# Implementation Plan — 見積書発行（quotation-management）

レーン構成: **F（基盤）→ A（画面）∥ B（PDF）→ M**。
`ContractServiceImpl` を触るのは F のみ（`buildAndSaveDraft` 抽出）。着手前に
lifecycle-status-consistency 系ブランチのマージ済みを確認すること。詳細は design.md 参照。

- [ ] 0. spec ドキュメント
  - **Objective**: 本ディレクトリ3ファイル。
  - **Demo**: レビュー可能な状態でコミットされている。

- [ ] F1. マイグレーション・エンティティ・サービス基盤
  - **Objective**: `t_quotation`＋`t_contract.quotation_id`＋メニューシード、採番・状態機械・検証。
  - **実装ガイダンス**: design.md 1章・2章。番号=実装時点の最新+1。H2 同期3点。
    削除は下書きのみ（removeById オーバーライド）。i18n エラーキー×4言語。
  - **テスト要件**: design.md 7章の採番4＋状態機械4ケース。smoke test へ
    `t_quotation`/`t_contract.quotation_id` の assert 追加。
  - **Demo**: curl で 登録→採番確認→提出済→受注、不正遷移が 409 系エラー。

- [ ] F2. ドラフト生成の共通化と見積受注連携
  - **Objective**: `createDraftFromProposal` の既定値ロジックを `buildAndSaveDraft` へ抽出し、
    `createDraftFromQuotation`（冪等・要員必須）を実装する。
  - **実装ガイダンス**: design.md 2章「ドラフト生成の共通化」。既定値規約と主担当
    フォールバックを一箇所へ。受注遷移とドラフト生成は別 API。
  - **テスト要件**: design.md 7章のドラフト生成5ケース＋**既存 createDraftFromProposal
    テスト全緑維持**（リファクタ回帰）。
  - **Demo**: 見積を受注→ドラフト生成→契約一覧に単価・精算幅が引き継がれた準備中契約。
    再実行しても2件目ができない。

- [ ] A1. 見積画面（一覧・モーダル・状態遷移・カンバン連携） ※F 完了後
  - **Objective**: `/quotation` の標準 CRUD 画面と提案カンバンからのプリセット起票。
  - **実装ガイダンス**: design.md 4章。セレクトは size=1000、状態遷移ミラー定数＋
    相互参照コメント、期限切れバッジ、サイドバー追加、i18n ×4言語。
    受注ダイアログに「続けてドラフト生成」チェック（既定 ON）。
  - **テスト要件**: `QuotationApiControllerTest`（page 検索・不正遷移・削除ガード）。
  - **Demo**: カンバンの提案から見積作成（単価プリセット）→提出済→PDF→受注→
    契約一覧へ、の通し。営業ロールで一連操作可・HR はメニュー非表示。

- [ ] B1. 見積書PDF ※F 完了後、A と並行可
  - **Objective**: 客先提出可能な見積書PDF。
  - **実装ガイダンス**: design.md 3章。イニシャル表記・登録番号未設定は省略・税抜注記。
  - **テスト要件**: design.md 7章 PDF 2ケース。
  - **Demo**: ダウンロードした PDF を開き、会社情報・イニシャル・精算幅・注記を目視確認。

- [ ] M. 統合回帰
  - **Objective**: 回帰確認と文書更新。
  - **テスト要件**: `mvn test` 全緑（Docker あり環境で smoke 含む）。
  - **Demo**: 提案→見積→受注→ドラフト→（既存フロー）稼動化→勤怠→請求、の全通し。
    `.kiro/specs/README.md` の状態列を更新。
