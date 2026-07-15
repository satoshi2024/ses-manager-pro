# Design Document — 求人・候補者採用パイプライン管理

## 1. DDL(新規migration `V15__create_candidate_tables.sql`。既存V1は変更しない — 完全新規テーブルのため)

```sql
CREATE TABLE t_candidate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    contact_email VARCHAR(200),
    contact_phone VARCHAR(20),
    skill_summary VARCHAR(1000),
    desired_rate DECIMAL(10,0),
    source VARCHAR(50) COMMENT '紹介/エージェント/自社応募等',
    current_stage VARCHAR(20) NOT NULL DEFAULT '応募受付' COMMENT '非正規化: 最新ステージのキャッシュ',
    next_action_date DATE,
    converted_engineer_id BIGINT COMMENT '入社後のt_engineer.idへの紐付け',
    remarks VARCHAR(1000),
    deleted_flag TINYINT NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE t_candidate_activity (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    candidate_id BIGINT NOT NULL,
    stage VARCHAR(20) NOT NULL,
    reason VARCHAR(500) COMMENT '不採用/内定辞退時は必須(アプリ層で検証)',
    changed_by BIGINT,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remarks VARCHAR(500),
    CONSTRAINT fk_candidate_activity_candidate FOREIGN KEY (candidate_id) REFERENCES t_candidate(id)
);
```
（完全新規テーブルなので、CLAUDE.mdの「V1に統合する」ルールの対象外。V12/V14と同様、通常のFlyway追加migrationとして扱う。）

## 2. バックエンド

### 2.1 新規
- `entity/Candidate.java`, `entity/CandidateActivity.java`
- `mapper/CandidateMapper`, `mapper/CandidateActivityMapper`
- `service/CandidateService`+`Impl`: ステージ変更時に`t_candidate.currentStage`を同期更新するトランザクション処理、不採用/内定辞退時の理由必須バリデーション、入社→エンジニア変換用の初期値DTO生成。
- `controller/api/CandidateApiController`(`/api/candidates`)

### 2.2 API一覧

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/api/candidates` | 一覧・検索(ステータス/スキルキーワード) |
| POST | `/api/candidates` | 新規登録 |
| PUT | `/api/candidates/{id}` | 基本情報更新 |
| GET | `/api/candidates/{id}/activities` | ステージ変更履歴 |
| POST | `/api/candidates/{id}/activities` | ステージ変更(`{stage, reason, remarks}`、`stage`が不採用/内定辞退の場合`reason`必須) |
| POST | `/api/candidates/{id}/convert-to-engineer` | 変換用初期値DTO取得(氏名・スキル概要をエンジニア新規作成フォームへ渡す) |

### 2.3 権限

新規メニュー`candidate`を`m_menu`/`t_role_menu`に追加(migration内でシード)。管理者/営業/HR に許可、既存の`GlobalControllerAdvice`/`MenuPermissionFilter`の仕組みをそのまま利用(新規コード不要、データ追加のみ)。

## 3. 画面

- 新規`templates/candidate/list.html`: ステータスバッジ表示、次アクション期限超過の強調(赤字)。
- 新規`templates/candidate/detail.html`: ステージ変更履歴タイムライン、「エンジニアとして登録」ボタン(ステージ=入社の場合のみ表示)。
- 新規`static/js/modules/candidate.js`: 既存モジュールJSの規約(`$.ajax`/`Toast`/`Swal.fire`確認)を踏襲。

## 4. テスト

- `CandidateServiceImplTest`: ステージ変更時の`currentStage`同期、不採用理由必須バリデーション、エンジニア変換DTO生成。
- `CandidateApiControllerTest`: 権限(HR/営業/管理者は許可、他ロールは403)。

## 5. リスク・確定口径(踩坑点)

- **`currentStage`非正規化のズレ**: `t_candidate_activity`への直接INSERTが将来バッチ等で行われた場合、`t_candidate.currentStage`との不整合が起きうる。**ステージ変更は必ず`CandidateService`経由のみ**とし、Mapperの直接呼び出しを禁止するコメントをエンティティに残す。
- **個人情報保護**: `t_candidate`は不採用者の個人情報も保持し続けるため、保存期間ポリシー(何年で削除するか)は本specでは未確定。運用開始前に法務/HRへの確認が必要（design未確定事項としてtasks.mdのDemoステップに含めない）。
- **SalesActivityとの意図的な非共有**: requirements.md記載の通り、コードは独立実装。レビュー時に「なぜ使い回さないのか」という指摘が出やすいので、このdesign.mdをレビューコメントの根拠として参照する。
