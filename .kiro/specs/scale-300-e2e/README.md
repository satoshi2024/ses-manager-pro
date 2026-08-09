# 300人規模 E2E / UI 回帰テスト

## 目的

300人規模（要員255 + 営業25 + HR8 + マネージャー10 + 管理者2）の実データで、
全5ロール × 全メニューのブラウザ操作をデスクトップ/モバイルの両方で検証し、
機能バグ・UI不具合・権限不整合を自動検出して保存する。

V2初期マスタの要員3名（田中/山田/伊藤）は300ユーザーのうちの「要員」に含め、
ログインアカウント・アカウント連携・BP所属・営業担当まで紐付けてシードする。

## データ

- 生成: `node scripts/seed-scale-300/generate-seed.mjs`
- 生成物: `sql/seed/r3-scale-300/seed.sql`
- dev起動時自動適用: `src/main/resources/db/migration-dev/V100__seed_r3_scale_300.sql`
- devプロファイルで `spring.flyway.locations` に `db/migration-dev` を追加済み

## 実行

```powershell
cd ops/e2e/scale-300
npm install
node run-e2e.mjs
```

前提: アプリが `http://localhost:8081` で起動していること。
環境変数 `BASE_URL` で変更可能。

## 成果物

- `evidence/` : ロール × ビューポート別のページスクリーンショット
- `e2e-issues.json` / `e2e-issues.jsonl` : 自動検出した問題一覧
- `e2e-report.md` : 自動検出サマリ
- `defect-catalog.md` : 手動確認を加えた最終障害・UI問題カタログ
- `round2/round2-report.md` : 第2ラウンド（深掘り）サマリ
- `round2/round2-issues.json` : 第2ラウンドの検出問題一覧

## テストデータ概要

| 対象 | 件数 |
|---|---:|
| ユーザー | 300（管理者2/営業25/HR8/マネージャー10/要員255） |
| 要員 | 255 |
| 顧客 | 35 |
| 案件 | 100 |
| 提案 | 150 |
| 契約 | 252 |
| 勤怠（月次/日次） | 555 / 8,415 |
| 請求 | 66 |
| 候補者 | 45 |
| CRM（リード/商機） | 60 / 60 |
| ToDo | 100 |
| スキルシート取込 / 案件メール取込 | 6 / 5 |

テスト用ログイン: `admin/admin123`、その他 `s300.*` / `Scale300!`

## 第2ラウンド（深掘り）

```powershell
cd ops/e2e/scale-300
node round2-deep.mjs
```

第2ラウンドでは、既知障害のスタックトレース取得、検索・ページング（最終ページ）、
モーダル、横断検索、エラーページ、API権限マトリクス、モバイルドロワー、
V2初期要員3名のログイン、34アカウント同時ログインを検証している。
全量スクリーンショットは `.kiro/specs/scale-300-e2e/round2/evidence/full/`
（gitignore対象）、代表例は `round2/evidence/selected/` に保存される。

## 修正状況（2026-08-09）

- D-002 カンバン描画 / D-003 ガント日本語ロケール / D-004 アカウント連携の
  JS障害を修正済み。
- V101 で未実装ページのメニュー行（search / tasks / skill-tag /
  saved-views / batch-operations）を `m_menu` / `t_role_menu` から撤去。
  APIは action permission（V66_1 / V74）で継続利用可能。
- `/tasks` は `/todo` への互換リダイレクトを追加。
- 修正後の第2ラウンドE2E再実行: **0問題 / 54チェック全てOK**。
  最新結果は `round2/round2-report.md` を参照。

## 修正状況（2026-08-10）

- D-007（favicon 404/403）を修正: `static/favicon.svg` 追加、
  base/login/error への icon 宣言、SecurityConfig の permitAll 追加。
- スキルシート取込6件 / 案件メール取込5件をシードへ追加し、
  取込レビュー画面（HR/マネージャー）の404と空リストを解消。
- 取込レビュー画面の中国語ボタン文言（删/経歴删除）を日本語へ修正。
- 修正後、第1ラウンド全メニューE2Eと第2ラウンド深掘りE2Eを再実行し、
  いずれも **0問題** を確認。
