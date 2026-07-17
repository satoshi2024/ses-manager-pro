# Implementation Plan — 月次締めチェックリスト（monthly-closing-checklist）

単一レーン逐次（1→2→3、4は任意）。**前提: ar-management レーンA のマージ後に着手**
（`InvoiceMapper` の交差回避と、(e) の残高列フォローアップのため）。詳細は design.md 参照。

- [ ] 0. spec ドキュメント
  - **Objective**: 本ディレクトリ3ファイル。
  - **Demo**: レビュー可能な状態でコミットされている。

- [ ] 1. 集計サービスと API
  - **Objective**: 5チェック項目の summary と締め記録（confirm/reopen/isClosed）を実装する。
  - **実装ガイダンス**: design.md 2章。(a) はグリッドクエリ流用・(c) は全顧客版クエリを
    既存 WHERE と一字一句共有で追加・締め記録は JSON ヘルパ集約・ロール検査。
    メニューシードのマイグレーション（番号=最新+1）。V14 の H2 シード方式を確認して踏襲し、
    確認結果を完了メモに記録。
  - **テスト要件**: design.md 7章の summary 10ケース + confirm/reopen/isClosed 7ケース。
  - **Demo**: curl で summary が5項目を返す。工数未入力を1件作ると (a) に現れ、
    入力すると消える。confirm→isClosed=true→reopen→false。

- [ ] 2. 画面
  - **Objective**: `/monthly-closing` ページ（カード5枚・明細・締めボタン・警告帯）。
  - **実装ガイダンス**: design.md 4章。サイドバー追加・既定=前月・リンクは既存画面へ。
    i18n ×4言語。
  - **テスト要件**: 既存テストグリーン維持（画面は Demo 検証）。
  - **Demo**: ブラウザで前月を開き、(a)-(d) を実データで解消→締め完了→締め済み表示。
    HR ユーザーでは締めボタンが押せない（403 トースト）。営業にはメニュー自体が出ない。

- [ ] 3. (e) 残高列フォローアップ ※ar-management B1 マージ後
  - **Objective**: 期限超過一覧に未回収残高（total−入金合計）列を追加する。
  - **実装ガイダンス**: design.md 2章(e)。ar-management の残高付き一覧クエリを共用する
    （残高定義の再実装禁止）。
  - **テスト要件**: 一部入金済みの超過請求書の残高が正しいこと1ケース。
  - **Demo**: 一部入金した超過請求書の残高が締め画面とエイジングで一致する。

- [ ] 4.（任意・第2段）締め済み月のロック
  - **Objective**: 締め済み月の reopenMonth / saveHours / 請求書取消を拒否する（R4）。
  - **実装ガイダンス**: 判定は `MonthlyClosingService.isClosed` の単一参照。
    `WorkRecordServiceImpl`・`InvoiceServiceImpl` の各ガード列の**先頭**に追加
    （既存ガード（請求済み・支払済BP等）より手前で「締め解除が先」と伝えるため）。
    `error.closing.locked` ×4言語。
  - **テスト要件**: 締め済み月の reopen/saveHours/void が拒否・解除後は通る、の3ケース。
  - **Demo**: 締め→reopen 試行でエラー→締め解除→reopen 成功。

- [ ] M. 統合回帰
  - **Objective**: 回帰確認と文書更新。
  - **テスト要件**: `mvn test` 全緑。
  - **Demo**: 月次業務の通し（工数入力→確定→請求→締め完了）を確認し、
    `.kiro/specs/README.md` の状態列を更新。
