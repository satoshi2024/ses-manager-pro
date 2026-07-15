# Implementation Plan — 求人・候補者採用パイプライン管理

タスクは **F(基盤) → A/B(並行可) → M(統合)** の構成。完全新規テーブルのため他specとファイル交差なし。

- [ ] F1. DDL・エンティティ・Mapper・メニュー権限基盤
  - **Objective**: A/Bが消費する共有資産を確定させる。
  - **実装ガイダンス**: design.md 1章のV15全DDL、`Candidate`/`CandidateActivity`エンティティ、Mapper、`m_menu`/`t_role_menu`への`candidate`メニューシード。H2テスト用スキーマにも同一テーブルを追加。
  - **テスト要件**: `CandidateMapperTest`基本CRUD。
  - **Demo**: `mvn test`グリーン。空DBから`mvn spring-boot:run`相当でmigration適用確認(またはFlywayMigrationSmokeTest)。

- [ ] A. 候補者CRUD・ステージ管理API 【並行可・担当ファイル: entity/Candidate*, mapper/Candidate*, service/CandidateService*, controller/api/CandidateApiController, CandidateServiceImplTest】
  - **Objective**: 一覧検索・登録・ステージ変更(理由必須バリデーション込み)・`currentStage`同期を実装。
  - **実装ガイダンス**: design.md 2章。
  - **テスト要件**: ステージ同期、不採用理由必須、権限(HR/営業/管理者のみ許可)の3系統。
  - **Demo**: 管理者で全エンドポイントをcurl確認。営業以外のロールで403確認。

- [ ] B. 画面(一覧・詳細・ステージタイムライン) 【並行可・担当ファイル: candidate/list.html, candidate/detail.html, static/js/modules/candidate.js】
  - **Objective**: 一覧・検索・ステージバッジ・期限超過強調・詳細のステージ変更履歴タイムラインを実装。
  - **実装ガイダンス**: design.md 3章。既存モジュールJSの`Toast`/`Swal.fire`規約を踏襲。
  - **テスト要件**: 手動UI確認。
  - **Demo**: ブラウザで候補者登録→ステージ変更→履歴表示を一通り確認。

- [ ] M1. 入社→エンジニア変換連携
  - **Objective**: 「エンジニアとして登録」ボタンとエンジニア新規作成画面への初期値引き渡しを実装(A/B完了後に統合)。
  - **実装ガイダンス**: design.md 2.2章の`convert-to-engineer`エンドポイントと、`engineer/form.html`側での初期値受け取り。
  - **テスト要件**: 変換後`convertedEngineerId`が正しく設定されることの確認。
  - **Demo**: ブラウザで候補者を「入社」ステージにし、実際にエンジニア新規作成画面へ初期値付きで遷移することを確認。

## F層ギャップメモ(A/B記入欄)
- (なし)
