# SES Manager Pro 機能拡張ロードマップ

本ドキュメントは、現行モジュールの不足補完と新規モジュール追加をまとめた全体計画である。
各フェーズの詳細仕様は `.kiro/specs/<スペック名>/`(requirements.md / design.md / tasks.md)に置き、
実装時は各スペックの `tasks.md` を上から順に消化していく(リポジトリのスペック駆動ワークフローに従う)。

## 全体像

| フェーズ | スペック | 内容 | 新規テーブル | 依存 |
|---|---|---|---|---|
| P1 | [engineer-skill-career](../.kiro/specs/engineer-skill-career/requirements.md) | 要員スキル・経歴・案件要求スキルの実装(既存DDLの未使用テーブル解消)、スキル検索 | なし(既存 `t_engineer_skill` / `t_engineer_career` / `t_project_skill` を利用) | なし |
| P2 | [proposal-contract-workflow](../.kiro/specs/proposal-contract-workflow/requirements.md) | 提案ステータス遷移の状態機械化、成約→契約作成連携、契約番号採番、要員ステータス連動 | なし | なし |
| P3 | [notification-center](../.kiro/specs/notification-center/requirements.md) | 通知の永続化(既読管理)、定時バッチ生成、通知種別追加、ToDoセンター | `t_notification`(004) | なし |
| P4 | [rule-based-matching](../.kiro/specs/rule-based-matching/requirements.md) | ルールベースマッチング(`ai.provider: rule`)、AIログ記録、案件→要員の逆方向推薦 | なし | P1 |
| P5 | [work-record-billing](../.kiro/specs/work-record-billing/requirements.md) | 月次実績工数(勤怠)、精算計算、請求書、入金・BP支払管理、実績ベース売上 | `t_work_record` / `t_invoice` / `t_invoice_item` / `t_bp_payment`(005) | P2 推奨 |
| P6 | [sales-activity](../.kiro/specs/sales-activity/requirements.md) | 営業活動記録(商談・訪問タイムライン)、顧客詳細画面、フォローアップ連携 | `t_sales_activity`(006) | P3 推奨 |
| P7 | [analytics-report](../.kiro/specs/analytics-report/requirements.md) | 稼動率推移・Bench分析、Excel帳票出力(Apache POI) | なし | P5 推奨 |
| P8 | [platform-hardening](../.kiro/specs/platform-hardening/requirements.md) | 入力バリデーション、ファイルアップロード、CSV入出力、監査、メール送信、セキュリティ強化、システム設定画面、テスト拡充 | `sys_user` 列追加(007) | 随時(並行可) |

## 推奨実施順序と理由

```
P1 ──→ P4
P2 ──→ P5 ──→ P7
P3 ──→ P6
P8(横断・随時)
```

1. **P1 engineer-skill-career** … DDLに存在するのにコードが一切参照していないテーブルの解消。要員詳細画面のハードコードされたスキルバッジ(`engineer-detail.js`)を実データ化する。P4 のマッチングの土台。
2. **P2 proposal-contract-workflow** … 提案→契約→要員ステータスの業務閉ループを作る。新テーブル不要で費用対効果が最大。
3. **P3 notification-center** … 通知を「都度計算・既読なし」から「永続化・既読管理・バッチ生成」へ。P5/P6 が生成する通知の受け皿。
4. **P4 rule-based-matching** … P1 のスキルデータを使い、モック実装(`AiMatchingServiceImpl` の固定3件)を実用的なルールベース採点に置換。将来の実AI接続(`GeminiService`)時のフォールバックにもなる。
5. **P5 work-record-billing** … SES業務の最大の空白である「勤怠→精算→請求→入金」を実装。`t_contract` の `settlement_hours_min/max` / `fraction_rule` が初めて計算に使われる。
6. **P6 sales-activity** … 顧客マスタを「住所録」から軽量CRMへ。
7. **P7 analytics-report** … 稼動率の時系列分析と Excel 出力。P5 の実績データがあると精度が上がる。
8. **P8 platform-hardening** … 横断的品質(バリデーション・アップロード・CSV・監査・メール・セキュリティ・設定画面・テスト)。他フェーズと並行で少しずつ進めてよいが、`@Valid` 導入と `MetaObjectHandler`(created_by 自動設定)は早めに入れると P2 以降が楽になる。

## SQLマイグレーション採番

既存: `001_create_tables.sql` / `002_init_master_data.sql` / `003_add_engineer_station_columns.sql`

| 番号 | ファイル | フェーズ |
|---|---|---|
| 004 | `004_create_notification.sql` | P3 |
| 005 | `005_create_work_record_billing.sql` | P5 |
| 006 | `006_create_sales_activity.sql` | P6 |
| 007 | `007_user_security_columns.sql` | P8 |

※ 実施順を入れ替える場合は番号を繰り上げる。各マイグレーションには新画面の `m_menu` / `t_role_menu` シードも含める(`MenuPermissionFilter` / サイドバーが自動で追従する)。

## 共通の実装原則(全フェーズ)

- 既存の層構造・命名に従う: `*PageController`(ビュー名のみ)/ `*ApiController`(`ApiResult<T>` 返却)/ `IService`+`ServiceImpl` / `BaseMapper`。XMLマッパーは作らず `LambdaQueryWrapper` か注釈 `@Select` を使う。
- 新画面は `templates/<area>/list.html` + `static/js/modules/<area>.js` + Bootstrap モーダル + SweetAlert2 削除確認 + `Toast` 通知の既存パターンを踏襲。
- 新メニューは `m_menu`(menu_key / path_prefix / api_prefix)登録のみで権限制御が効く。管理者は常にバイパス。
- UI・コメント・ログ・コミットメッセージは日本語。
- テストは H2(`application-test.yml`)で動くこと。新テーブルは H2 用スキーマ(`src/test/resources/sql/`)にも追加する。
