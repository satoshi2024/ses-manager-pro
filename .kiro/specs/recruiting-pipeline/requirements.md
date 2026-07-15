# Requirements Document — 求人・候補者採用パイプライン管理

## Introduction

既存の`t_sales_activity`（`SalesActivity`）は`customerId`必須・`/api/customers/{id}/activities`に固定されており、案件営業（顧客向け）専用の設計である。SES事業のもう一方の柱である「技術者の採用（BP新規開拓・自社雇用エンジニア採用）」の候補者パイプライン（応募〜書類選考〜面談〜内定〜入社）を追跡する仕組みは存在しない。本specは`SalesActivity`と対になる、新規の候補者管理エンティティ・画面を追加する。

### 確定済みの設計判断
- `t_sales_activity`は変更しない。候補者管理は完全に独立した新規テーブル（`t_candidate`, `t_candidate_activity`）とする（`SalesActivity`と同型のパターンを踏襲するが、コードは共有しない — 顧客営業と採用は別ライフサイクルであり、無理に共通化すると将来の変更で両者が引きずられるため）。
- 入社確定後、候補者を正式な`t_engineer`へ変換する導線を設ける（手動トリガー、自動化はしない — 入社時の労務手続き確認が必要なため）。
- 採用ステータスは固定ENUMとする: `応募受付`/`書類選考`/`一次面談`/`最終面談`/`内定`/`内定辞退`/`入社`/`不採用`。

## Requirements

### Requirement 1: 候補者マスタ管理

#### Acceptance Criteria
1. THE システム SHALL 候補者の氏名・連絡先・スキル概要・希望単価・情報源(紹介/エージェント/自社応募等)を登録できる。
2. THE システム SHALL 候補者一覧に対し、ステータス・スキルキーワードでの検索/フィルタを提供する。
3. THE `t_candidate` SHALL 論理削除(`deletedFlag`)に対応する(既存の`mybatis-plus`グローバル設定に準拠)。

### Requirement 2: 採用パイプライン(ステージ管理)

#### Acceptance Criteria
1. THE システム SHALL 候補者ごとに`t_candidate_activity`でステージ変更履歴(いつ・誰が・どのステージへ)を記録する。
2. THE システム SHALL 現在のステージを候補者一覧にKanban風またはステータスバッジで表示する(既存の`proposal-kanban.js`のパターンを参考にしてよいが、コード共有はしない — 案件カンバンと候補者カンバンはドメインが異なるため独立実装とする)。
3. WHEN ステージが「不採用」または「内定辞退」に変更された場合、THE システム SHALL 理由(自由記述)の入力を必須とする。
4. THE システム SHALL 次アクション予定日(`nextActionDate`)を持ち、期限超過の候補者を一覧上で強調表示する。

### Requirement 3: 入社時のエンジニア登録連携

#### Acceptance Criteria
1. WHEN 候補者のステージが「入社」になった場合、THE システム SHALL 「エンジニアとして登録」ボタンを表示する。
2. WHEN ユーザーが「エンジニアとして登録」を実行した場合、THE システム SHALL 候補者の氏名・スキル概要を初期値とした`t_engineer`新規作成画面へ遷移する(自動保存はせず、必ず確認・補完の手動操作を挟む)。
3. THE システム SHALL 変換後も`t_candidate`レコードを削除せず、変換済みフラグ(`convertedEngineerId`)で紐付けを残す(採用実績のトレーサビリティのため)。

## 踩坑点（実装時の注意）
- `SalesActivity`と見た目が似ているため、実装者が「既存の`SalesActivityApiController`を`customerId`ではなく`candidateId`に置き換えて使い回そう」としがちだが、要件2.3（不採用理由必須）・要件3（エンジニア変換連携）など候補者固有の分岐が今後増える前提のため、**コード共有はせず最初から独立実装にする**こと。
- 候補者の個人情報（連絡先・希望単価）は`t_engineer`より機微度が高い（不採用者の情報も残るため）。既存の`AuditLog`の対象に`t_candidate`のCRUDも含めるかどうかを実装前に確認する（個人情報保護方針次第）。
- ステージ変更履歴(`t_candidate_activity`)を「現在ステージ」の単一ソースとして扱うか、`t_candidate.currentStage`という非正規化カラムを別途持つかで実装コストが変わる。一覧表示のパフォーマンスを優先するなら`t_candidate.currentStage`を非正規化カラムとして持ち、`t_candidate_activity`挿入時に同期更新する設計を推奨(design.mdで確定)。
