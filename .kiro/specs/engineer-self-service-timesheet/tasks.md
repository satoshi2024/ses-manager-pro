# Implementation Plan — 作業報告書と要員セルフサービス勤怠（engineer-self-service-timesheet）

レーン構成: **F（基盤・共有資産凍結）→ A（要員ポータル）∥ B（承認側）∥ C（PDF・紐付けUI）→ M**。
A/B は `WorkRecordServiceImpl` をメソッド単位で分担（A=saveDaily/submit、B=approve/reject/BP共通化）。
衝突時は A 優先で B がリベース。**本 spec 完了までは contract-price-history(P6) に着手しない**
（saveHours 交差）。i18n は F で全キー先行投入し A〜C は参照のみ。詳細は design.md 参照。

- [ ] 0. spec ドキュメント
  - **Objective**: 本ディレクトリ3ファイル。
  - **Demo**: レビュー可能な状態でコミットされている。

- [ ] F1. 基盤（マイグレーション・エンティティ・セキュリティ・i18n 凍結）
  - **Objective**: 要員ロール・2新テーブル・status ENUM 拡張・メニューシード・
    `/my/**` の静的認可・`EngineerAccountLinkService`・全 i18n キー×4言語・H2 同期3点。
  - **実装ガイダンス**: design.md 1〜3.2章。番号=実装時点の最新+1。
    `CustomUserDetailsService` は汎用実装のため変更不要なことをテストで確認。
  - **テスト要件**: 紐付け検証3ケース（role≠要員/二重/正常）＋ smoke test への
    2テーブル・ENUM 値 assert 追加＋要員ロールユーザーが my-timesheet 以外のメニュー・API に
    403 となる `MenuPermissionFilter` 統合テスト。
  - **Demo**: 要員ロールのユーザーを作成→ログイン→サイドバーがマイ勤怠のみ。
    他画面 URL 直叩きで 403 統一エラーページ。

- [ ] F2. 要員削除・ユーザー削除の連動
  - **Objective**: 要員削除の成功後に unlink＋アカウント無効化。紐付け中ユーザーの削除は拒否。
  - **実装ガイダンス**: design.md 3.2章。lifecycle G3 の「削除成功後のみ」規約に従う。
    `LoginSuccessHandler` の要員ロール分岐（→ `/my/timesheet`）もここで実装（design.md 2章）。
  - **テスト要件**: 削除成功で unlink+無効化 / 削除失敗（false）で何もしない /
    紐付け中ユーザーの削除拒否、の3ケース。
  - **Demo**: 紐付け済み要員を削除→当該ユーザーでログイン不可。紐付け中ユーザーの削除は
    エラー。要員ログイン直後にマイ勤怠へ遷移する。

- [ ] A1. 日次入力サービス（saveDaily・手動合計との排他） ※F 後
  - **Objective**: 日次 upsert/削除→合計再計算→既存 saveHours 連動。日次あり月の手動合計拒否。
  - **実装ガイダンス**: design.md 3.1章。saveHours へ内部フラグ追加（fromDaily）。
    精算計算・各種ガードは既存を経由し再実装しない。
  - **テスト要件**: design.md 9章の saveDaily 5ケース＋排他2ケース（後方互換含む）。
  - **Demo**: curl で日次3日分登録→work_record の actual_hours/billing_amount が
    合計に追従。日次あり月にグリッドから手動入力するとエラー。

- [ ] A2. 要員ポータル画面と提出 ※A1 後
  - **Objective**: `/my/timesheet`（本人スコープ・日次テーブル・提出・差戻しコメント表示）。
  - **実装ガイダンス**: design.md 2章（本人スコープはコントローラ冒頭で解決）・4章・5章。
    submit は未入力日警告つき確認ダイアログ。
  - **テスト要件**: 本人スコープ2ケース（他要員403・未紐付け403）＋ submit 遷移テスト。
  - **Demo**: 要員ログイン→日次入力→合計・精算額の自動表示→提出→ステータス提出済。

- [ ] B1. 承認・差戻しとBP生成の共通化 ※F 後、A1 と並行可
  - **Objective**: approve/reject API と、confirmMonth の BP 生成部の単契約共通化。
  - **実装ガイダンス**: design.md 3.1章。`generateOrSyncBpFor(WorkRecord)` 抽出、
    approve は確定と同義の後続処理。通知2種は `NotificationLinks` 定数経由。
  - **テスト要件**: design.md 9章の submit/approve/reject 4ケース＋
    confirmMonth 対象拡張（入力中・提出済・差戻し）＋既存 confirmMonth テストのグリーン維持。
  - **Demo**: 提出済を承認→BP支払が生成される（BP要員）。差戻し→要員に通知が届き再提出できる。

- [ ] B2. 勤怠グリッドの承認UI ※B1 後
  - **Objective**: グリッドに状態バッジ・承認/差戻しボタン・日次明細展開を追加。
  - **実装ガイダンス**: design.md 5章。既存の一括確定/解除ボタンは不変。
  - **テスト要件**: 既存 work-record 系テストのグリーン維持（UI は Demo 検証）。
  - **Demo**: グリッド上で 提出済→承認→確定バッジ。差戻しコメントが要員側に表示される。

- [ ] C1. 作業報告書PDF ※F 後、A/B と並行可
  - **Objective**: 契約×月の作業報告書PDF（本人・管理側の両出力口）。
  - **実装ガイダンス**: design.md 6章。日次なし月は簡易様式。
  - **テスト要件**: PDF 生成2ケース（日次あり/なし）＋本人スコープ（他人の report 403）。
  - **Demo**: PDF を開き日次明細・合計・承認欄を目視確認。

- [ ] C2. アカウント紐付けカード ※F 後
  - **Objective**: 要員詳細に紐付け/解除カード（freee カードと同型）。
  - **実装ガイダンス**: design.md 5章。セレクトは role=要員かつ未紐付けのみ。
  - **テスト要件**: 紐付け API テストは F1 でカバー済み（UI は Demo 検証）。
  - **Demo**: 要員詳細から紐付け→要員ログインでマイ勤怠が使える→解除で 403。

- [ ] M. 統合回帰
  - **Objective**: 全レーン統合後の回帰と文書更新。
  - **テスト要件**: `mvn test` 全緑（Docker あり環境で smoke 含む）。
  - **Demo**: 要員が日次入力→提出→承認→（既存フロー）請求書生成→作業報告書PDF、の全通し。
    セルフサービス未使用の要員の従来運用（管理部の月次直接入力→一括確定）が不変であることも確認。
    `.kiro/specs/README.md` の状態列を更新。
