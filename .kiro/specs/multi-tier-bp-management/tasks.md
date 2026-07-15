# Implementation Plan — 多段階BP(協力会社)構造管理

タスクは **F(基盤) → A/B(並行可) → M(統合)** の構成。他specとファイル交差なし。

- [x] F1. DDL・エンティティ・Mapper基盤
  - **Objective**: `t_bp_payment`のスキーマ拡張とバックエンド基盤を確定させる。
  - **実装ガイダンス**: design.md 1章のDDLをV1の`CREATE TABLE t_bp_payment`に直接反映（新規ALTER migrationは作らない）。`entity/BpPayment.java`に`layerOrder`/`payeeCompanyName`/`parentPaymentId`追加。`mapper/BpPaymentMapper`に`selectByWorkRecordIdOrderByLayer`追加。H2テスト用スキーマ（`sql/engineer-schema-h2.sql`等、`t_bp_payment`を含むファイル）も同時更新。
  - **テスト要件**: `BpPaymentMapperTest`で階層取得・複合UNIQUE制約の動作確認。
  - **Demo**: `mvn test`グリーン。H2起動時に`t_bp_payment`が新カラム込みで作成されることを確認。

- [x] A. バックエンドAPI・サービスロジック 【並行可・担当ファイル: service/impl/BpPaymentServiceImpl, controller/api/BpPaymentApiController, dto/bp/BpPaymentTreeDto, BpPaymentServiceImplTest】
  - **Objective**: 階層CRUD・重複/循環参照検証・マージン計算を実装。
  - **実装ガイダンス**: design.md 2〜3章。`layerOrder`重複チェック、`parentPaymentId`の同一work_record内整合性検証、`calculateMargin()`実装。
  - **テスト要件**: 階層重複拒否、親子不整合拒否、マージン計算、既存単層データの後方互換動作の4系統。
  - **Demo**: `mvn test -Dtest=BpPaymentServiceImplTest`パス。管理者で新規4エンドポイントをcurl確認。

- [x] B. 画面(商流ツリー表示) 【並行可・担当ファイル: bp-payment/list.html, static/js/modules/bp-payment.js】
  - **Objective**: 階層が複数ある場合にツリー/インデント表示、階層追加モーダルを実装。
  - **実装ガイダンス**: design.md 4章。既存の単層表示は変更しない（後方互換）。
  - **テスト要件**: 手動UI確認（自動テストは対象外、既存パターンに準拠）。
  - **Demo**: ブラウザで2階層以上のwork_recordを作成し、インデント表示とマージン表示を確認。

- [x] M. 統合・検証
  - **Objective**: F/A/Bの統合確認と`FlywayMigrationSmokeTest`によるMySQL実DDL検証。
  - **実装ガイダンス**: `FlywayMigrationSmokeTest`(Testcontainers、Docker必須)を実行し、空DBからのV1適用でスキーマが問題なく作成されることを確認。
  - **テスト要件**: 全体`mvn test`グリーン。
  - **Demo**: 既存の単層`t_bp_payment`データを持つシナリオで、支払確認フローが従来通り動作することを確認。

## F層ギャップメモ(A/B記入欄)
- (なし)
