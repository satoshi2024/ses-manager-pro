# Implementation Plan — 横断的品質強化(P8)

タスク1・2 は他フェーズより先行実施を推奨(P2 以降が楽になる)。それ以外は独立しており並行可。

- [x] 1. Bean Validation 導入
  - **Objective**: 全 CRUD API に入力検証を効かせる。
  - **実装ガイダンス**: design.md 1章。starter-validation 追加 → エンティティ注釈 → `@Valid` 付与 → `GlobalExceptionHandler` に `MethodArgumentNotValidException` ハンドラ。
  - **テスト要件**: 氏名空の Engineer POST が code=400 + 日本語メッセージ。
  - **Demo**: 画面から氏名空で保存 → エラー Toast に「氏名は必須です」。

- [ ] 2. `MetaObjectHandler` による created_by 自動設定 + 操作ログ
  - **Objective**: 監査情報の自動化。
  - **実装ガイダンス**: design.md 4章。P2 Task1 の `SecurityUtils` が前提(未実装なら先に実施)。`@TableField(fill = FieldFill.INSERT)` を対象エンティティに付与。`ApiAuditFilter` 追加。
  - **テスト要件**: H2 で insert 後 createdBy が入っていること。
  - **Demo**: 要員を登録 → DB の created_by にログインユーザー ID。ログに操作行。

- [ ] 3. ファイルアップロード
  - **Objective**: スキルシート・顔写真の保存/配信。
  - **実装ガイダンス**: design.md 2章。パストラバーサル検証(`Path.normalize().startsWith(basePath)`)を必ず実装。要員詳細に UI。
  - **テスト要件**: 拡張子/サイズ違反・トラバーサル(`../`)拒否のテスト。
  - **Demo**: 要員詳細で写真アップ → 表示。pdf 12MB は拒否。

- [ ] 4. CSV 入出力
  - **Objective**: 要員/案件エクスポート + 要員インポート(部分成功)。
  - **実装ガイダンス**: design.md 3章。`CsvUtils` は RFC4180 最小実装 + テスト。BOM 付与を忘れない。
  - **テスト要件**: エスケープ(カンマ・引用符・改行入り)往復、混在 CSV で成功N/失敗行レポート。
  - **Demo**: 3行中1行不正の CSV をインポート → 2件登録 + 失敗行理由がモーダル表示。Excel で出力 CSV が文字化けしない。

- [ ] 5. メール送信
  - **Objective**: テンプレート差し込み送信(非同期・ドライラン対応)。
  - **実装ガイダンス**: design.md 5章。`@EnableAsync`。提案メールモーダル + `POST /api/proposals/{id}/send-mail`。
  - **テスト要件**: `{{変数}}` 置換の単体テスト、SMTP 未設定でドライランになること。
  - **Demo**: SMTP 未設定のままカンバンから提案メール送信 → ログに件名/本文が出て画面は成功 Toast。

- [ ] 6. CSRF 方式の移行
  - **Objective**: `/api/**` の CSRF 無効化を廃止し cookie-to-header へ。
  - **実装ガイダンス**: design.md 6.1。`common.js` の `beforeSend` 追加後、全画面の CRUD を一通り手動確認。既存 API テストに `csrf()` ポストプロセッサ追加。
  - **テスト要件**: トークン無し POST が 403、トークン付きが 200。
  - **Demo**: 各画面の登録・更新・削除が従来通り動く。

- [ ] 7. アカウントロック + パスワードポリシー + 接続情報の環境変数化
  - **Objective**: 認証まわりの強化。
  - **実装ガイダンス**: design.md 6.2〜6.4。`sql/007_user_security_columns.sql` + H2 スキーマ更新。`LoginFailureHandler` / 成功時リセット。
  - **テスト要件**: 5回失敗でロック・30分後解除・成功でリセット(時計は `Clock` 注入でテスト可能に)。
  - **Demo**: 誤パスワード5回 → ロックメッセージ。`DB_PASSWORD` 環境変数で起動できる。

- [ ] 8. システム設定サービス + 管理画面
  - **Objective**: `m_system_config` の実用化とハードコード値の設定化。
  - **実装ガイダンス**: design.md 7章。キーのシード投入。P3/P5 実装済みならそれらの定数を置換。
  - **テスト要件**: 型付きアクセサのデフォルト値/キャッシュ更新。
  - **Demo**: 管理者メニュー「システム設定」で `notice.contract-end-days` を 60 に変更 → 通知バッチの対象が変わる。

- [ ] 9. API テスト空白地帯の解消
  - **Objective**: 主要6モジュールの API テスト整備。
  - **実装ガイダンス**: design.md 8章。1モジュールずつ、一覧・登録・代表エラー系の3ケースを最低ライン。
  - **Demo**: `mvn test` が MySQL なしで全件グリーン、テストクラス数が 6 以上増えている。
