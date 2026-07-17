# Implementation Plan — 債権管理（ar-management）

レーン構成: **A（消込コア）→ B（エイジング+Excel）∥ C（督促メール）→ M**。
B/C は A のマージ後に並行可（担当ファイルは B=`ExcelExportService`+エイジングタブ、
C=督促モーダル+`sendReminder` で交差しない。`invoice.js`/`list.html` は行が離れるが
同一ファイルのため、並行時はどちらかを先にマージしてリベースすること）。
i18n はキー追加のみ。詳細は design.md 参照。

- [x] 0. spec ドキュメント
  - **Objective**: 本ディレクトリ3ファイル。
  - **Demo**: レビュー可能な状態でコミットされている。

- [x] A1. マイグレーションとエンティティ基盤
  - **Objective**: `t_invoice_payment`・請求ステータス「一部入金」・（必要なら）顧客メール欄を追加する。
  - **実装ガイダンス**: design.md 1章。番号は実装時点の最新+1。`m_customer` のメール項目有無を
    最初に確認し、結果（既存カラム名 or 追加した旨）を本タスク完了メモに記録。
    エンティティ `InvoicePayment`＋`InvoicePaymentMapper`（BaseMapper のみ）。
    H2 同期3点（engineer-schema-h2 / リプレイ用 `sql/schema-ar-management-h2.sql` / application-test.yml）。
  - **テスト要件**: `FlywayMigrationSmokeTest` へ `t_invoice_payment` と status ENUM 値の assert 追加。
    H2 の `@SpringBootTest` 群がグリーンのまま。
  - **Demo**: （Docker あり環境）smoke test 単体実行が通る。

- [x] A2. 入金消込サービス＋API
  - **Objective**: 入金の登録/削除/一覧と、ステータス自動遷移（recalc 一元化）を実装する。
  - **実装ガイダンス**: design.md 2章・3章。`recalcPaymentStatus` に判定を集約。
    `changeStatus` の `ALLOWED` から入金済系を除去（既存テストの期待値更新を同コミットで）。
    過入金・取消済み請求書ガード。i18n エラーキー3本×4言語。
  - **テスト要件**: design.md 8章の recalc / addPayment / changeStatus 各ケース
    （`InvoiceServiceImplTest` 追記、10ケース目安）。
  - **Demo**: curl で 部分入金→一部入金 / 追加入金で入金済＋paid_date / 行削除で送付済へ巻き戻り。

- [x] A3. 入金モーダル UI
  - **Objective**: 請求書一覧から入金履歴の参照・追加・削除ができる。
  - **実装ガイダンス**: design.md 4章-1,4。既存モーダル規約（Bootstrap+Toast+SweetAlert2）。
    ステータスバッジへ 一部入金 追加。**既存 UI の「入金済」手動ボタン（入金日入力）は
    入金モーダル導線へ差し替えて撤去**（R1-7 のサーバ側廃止と対で行う。送付済⇄未送付の
    ボタンは現行のまま）。i18n `invoice.payment.*`×4言語。
  - **テスト要件**: 既存 Invoice 系テストがグリーンのまま（UI は Demo 検証）。
  - **Demo**: ブラウザで入金2件登録→バッジが 一部入金→入金済 と変わる→1件削除→一部入金へ戻る。

- [x] B1. エイジング集計＋タブ＋Excel ※A マージ後
  - **Objective**: 顧客×経過区分の未回収マトリクスとドリルダウン・Excel出力。
  - **実装ガイダンス**: design.md 2章(AgingReportDto)・4章-2・5章。区分振り分けは Java 側。
    残高定義は A2 の `total−Σ(amount+fee)` と同一（定義の二重実装をしないこと——
    Mapper の残高付き一覧クエリを共用する）。
  - **テスト要件**: design.md 8章 aging の境界6ケース＋Excel ヘッダー/書式1ケース。
  - **Demo**: 期限超過データを作り、タブで区分表示→セルクリックでドリルダウン→Excel が開ける。

- [x] C1. 督促メール ※A マージ後
  - **Objective**: 期限超過請求書への督促メール送信と履歴参照。
  - **実装ガイダンス**: design.md 2章(sendReminder)・3章・4章-3。テンプレート変数6種、
    `t_mail_delivery` 記録は `MailService` 側の既存挙動に乗る（新規実装しない）。
  - **テスト要件**: design.md 8章 reminder の3ケース。
  - **Demo**: 期限超過請求書から送信→（dev はメールモック）`t_mail_delivery` に記録され、
    履歴がモーダルに出る。宛先未設定顧客はエラーメッセージ。

- [x] M. 統合回帰
  - **Objective**: 全レーン統合後の回帰と文書更新。
  - **テスト要件**: `mvn test` 全緑（Docker あり環境で smoke 含む）。
  - **Demo**: 請求→部分入金→督促→残額入金→入金済、の一連をブラウザで通し、
    エイジングから当該顧客が消えることを確認。`.kiro/specs/README.md` の状態列を更新。
